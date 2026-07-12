package dxvfix.watch;

import dxvfix.generate.FrameGenerator;
import dxvfix.repair.Mp4Repairer;
import dxvfix.repair.RepairSummary;
import dxvfix.scan.ScanEngine;
import dxvfix.scan.ScanResult;
import dxvfix.util.VideoFileFinder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches a "show content" directory: periodically re-walks it (recursively), scans any file
 * that's new or changed since the last pass, keeps a live list of corrupted files, and optionally
 * auto-repairs them into a dedicated subfolder that is itself always excluded from scanning.
 * <p>
 * Deliberately polling rather than {@code java.nio.file.WatchService}: content arrives as files
 * get copied in over time, WatchService fires a burst of events mid-copy that would need the same
 * "wait for the file to stop changing" handling anyway, and per-directory watch registration is
 * fiddly to keep correct as new subfolders appear/disappear. A plain periodic walk is simpler,
 * more predictable in resource use (bounded bursts of work every {@link #POLL_INTERVAL_MS}, not a
 * live event stream), and easier to reason about under load — see the class using this for the
 * load-testing notes.
 */
public final class ShowWatcher {

    public static final String FIXED_SUBFOLDER_NAME = "_DXVFrameDoctor_Fixed";
    public static final String LOG_FILE_NAME = "watch_log.txt";
    private static final long POLL_INTERVAL_MS = 20_000;
    private static final long STABILIZE_DELAY_MS = 1500;
    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public interface Listener {
        void onFileUpdated(WatchedFile file);
        void onFileCleared(File sourceFile);
        void onLog(String message);
    }

    private final Listener listener;
    private final java.util.Map<String, String> knownSignatures = new ConcurrentHashMap<>();
    private final java.util.Map<String, WatchedFile> badFiles = new ConcurrentHashMap<>();
    private final Set<String> fixingNow = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private Thread thread;

    private File contentDir;
    private ScanEngine.Mode mode;
    private boolean useGenerateStrategy;
    private boolean autoFix;
    private String ffmpegPath;

    public ShowWatcher(Listener listener) {
        this.listener = listener;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void start(File contentDir, ScanEngine.Mode mode, boolean useGenerateStrategy,
                                    boolean autoFix, String ffmpegPath) {
        if (running) return;
        this.contentDir = contentDir;
        this.mode = mode;
        this.useGenerateStrategy = useGenerateStrategy;
        this.autoFix = autoFix;
        this.ffmpegPath = ffmpegPath;
        knownSignatures.clear();
        badFiles.clear();

        running = true;
        thread = new Thread(this::runLoop, "show-watcher");
        thread.setDaemon(true);
        // Slightly below normal priority: this is background housekeeping, not the show itself.
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    /**
     * Manually (re)fixes one already-detected file right now, independently of the poll loop and
     * of the auto-fix setting — for the "Исправить" button shown per row when auto-fix is off. Runs
     * on its own short-lived background thread so it doesn't block the UI or the watcher's own
     * loop; guarded against firing twice concurrently for the same file (e.g. a double click).
     */
    public void fixNow(WatchedFile wf) {
        if (contentDir == null) return;
        String rel = relativePath(wf.sourceFile);
        if (!fixingNow.add(rel)) return; // already in progress

        wf.fixing = true;
        listener.onFileUpdated(wf);

        Thread t = new Thread(() -> {
            try {
                ScanResult scan = ScanEngine.scan(wf.sourceFile, mode, ffmpegPath, null);
                if (scan.badCount() == 0) {
                    badFiles.remove(rel);
                    wf.fixing = false;
                    listener.onFileCleared(wf.sourceFile);
                    return;
                }
                fixFile(wf.sourceFile, rel, scan, wf);
            } catch (Exception e) {
                appendLog("Не удалось исправить " + rel + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                wf.fixing = false;
                listener.onFileUpdated(wf);
                fixingNow.remove(rel);
            }
        }, "show-watcher-manual-fix");
        t.setDaemon(true);
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        t.start();
    }

    private void runLoop() {
        listener.onLog("Сопровождение запущено: " + contentDir.getAbsolutePath());
        while (running) {
            try {
                reconcile();
            } catch (Exception e) {
                listener.onLog("Ошибка сопровождения: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        listener.onLog("Сопровождение остановлено.");
    }

    private void reconcile() {
        Set<String> excluded = Set.of(FIXED_SUBFOLDER_NAME.toLowerCase(Locale.ROOT));
        List<File> files = VideoFileFinder.find(contentDir, excluded);

        Set<String> currentRel = new HashSet<>();
        for (File f : files) {
            if (!running) return;
            String rel = relativePath(f);
            currentRel.add(rel);

            String sig = signature(f);
            String known = knownSignatures.get(rel);
            if (sig.equals(known)) continue; // unchanged since last pass

            if (!isStable(f)) continue; // still being copied in; revisit next poll
            if (!running) return;
            if (fixingNow.contains(rel)) continue; // a manual fix is in flight for this file right now

            knownSignatures.put(rel, sig);
            processFile(f, rel);
        }

        for (String rel : new ArrayList<>(knownSignatures.keySet())) {
            if (!currentRel.contains(rel)) {
                knownSignatures.remove(rel);
                WatchedFile removed = badFiles.remove(rel);
                if (removed != null) {
                    listener.onFileCleared(removed.sourceFile);
                }
            }
        }
    }

    private void processFile(File f, String rel) {
        listener.onLog("Проверка: " + rel);
        try {
            ScanResult scan = ScanEngine.scan(f, mode, ffmpegPath, null);
            if (scan.badCount() == 0) {
                WatchedFile removed = badFiles.remove(rel);
                if (removed != null) listener.onFileCleared(f);
                return;
            }

            WatchedFile wf = new WatchedFile(f);
            wf.badCount = scan.badCount();
            wf.totalFrames = scan.frames.size();
            wf.detectedAt = LocalDateTime.now();
            badFiles.put(rel, wf);
            listener.onFileUpdated(wf);
            appendLog("ОБНАРУЖЕНО: " + rel + " (" + wf.badCount + " из " + wf.totalFrames + " кадров битые)");

            // If monitoring was already run against this folder before (app restarted, or this is
            // the first pass after a fresh start), a current fix might already exist on disk --
            // reuse it instead of redoing the (possibly expensive, with Generate strategy) repair.
            File existingFix = findExistingCurrentFix(f, rel);
            if (existingFix != null) {
                wf.fixed = true;
                wf.fixedFile = existingFix;
                wf.fixedAt = java.time.Instant.ofEpochMilli(existingFix.lastModified())
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
                listener.onFileUpdated(wf);
                appendLog("УЖЕ ИСПРАВЛЕНО РАНЕЕ: " + rel + " -> " + existingFix.getAbsolutePath());
            } else if (autoFix) {
                fixFile(f, rel, scan, wf);
            }
        } catch (Exception e) {
            WatchedFile wf = new WatchedFile(f);
            wf.errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            wf.detectedAt = LocalDateTime.now();
            badFiles.put(rel, wf);
            listener.onFileUpdated(wf);
        }
    }

    /**
     * A fix in {@link #FIXED_SUBFOLDER_NAME} counts as "current" if it was written at or after the
     * source's last modification time -- i.e. nothing has touched the source since it was fixed.
     * If the source was replaced with a newer file after that, the old fix is stale and this
     * returns null so the file gets a fresh scan/fix.
     */
    private File findExistingCurrentFix(File source, String rel) {
        File candidate = new File(new File(contentDir, FIXED_SUBFOLDER_NAME), rel);
        if (candidate.isFile() && candidate.lastModified() >= source.lastModified()) {
            return candidate;
        }
        return null;
    }

    private void fixFile(File source, String rel, ScanResult scan, WatchedFile wf) {
        try {
            if (useGenerateStrategy && ffmpegPath != null) {
                FrameGenerator.generateForScan(source, scan.videoTrack, ffmpegPath, scan, null);
            }
            File outFile = new File(new File(contentDir, FIXED_SUBFOLDER_NAME), rel);
            File parent = outFile.getParentFile();
            if (parent != null) parent.mkdirs();

            RepairSummary summary = Mp4Repairer.repair(source, scan, outFile);

            wf.fixed = true;
            wf.fixedFile = outFile;
            wf.fixedAt = LocalDateTime.now();
            listener.onFileUpdated(wf);
            appendLog("ИСПРАВЛЕНО (" + summary.framesReplaced + " кадров): " + rel + " -> " + outFile.getAbsolutePath());
        } catch (Exception e) {
            appendLog("ОШИБКА ИСПРАВЛЕНИЯ: " + rel + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * A single running log of every detection/fix event, appended to across the life of the
     * watched folder (including across app restarts) -- deliberately not one report file per
     * fixed video, which would otherwise clutter {@link #FIXED_SUBFOLDER_NAME} with as many report
     * files as there are repaired videos.
     */
    private void appendLog(String message) {
        try {
            File fixedDir = new File(contentDir, FIXED_SUBFOLDER_NAME);
            fixedDir.mkdirs();
            File logFile = new File(fixedDir, LOG_FILE_NAME);
            String line = "[" + LocalDateTime.now().format(LOG_TIME_FMT) + "] " + message + System.lineSeparator();
            Files.writeString(logFile.toPath(), line, java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Logging is best-effort; never let it break the actual scan/fix flow.
        }
        listener.onLog(message);
    }

    private boolean isStable(File f) {
        long s1 = f.length();
        try {
            Thread.sleep(STABILIZE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!running) return false;
        long s2 = f.length();
        return s1 == s2 && s2 > 0;
    }

    private String relativePath(File f) {
        return contentDir.toPath().relativize(f.toPath()).toString();
    }

    private static String signature(File f) {
        return f.length() + ":" + f.lastModified();
    }
}
