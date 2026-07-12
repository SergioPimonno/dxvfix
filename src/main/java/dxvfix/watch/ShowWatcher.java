package dxvfix.watch;

import dxvfix.generate.FrameGenerator;
import dxvfix.repair.Mp4Repairer;
import dxvfix.repair.RepairSummary;
import dxvfix.report.ReportWriter;
import dxvfix.scan.ScanEngine;
import dxvfix.scan.ScanResult;
import dxvfix.util.VideoFileFinder;

import java.io.File;
import java.time.LocalDateTime;
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
    private static final long POLL_INTERVAL_MS = 20_000;
    private static final long STABILIZE_DELAY_MS = 1500;

    public interface Listener {
        void onFileUpdated(WatchedFile file);
        void onFileCleared(File sourceFile);
        void onLog(String message);
    }

    private final Listener listener;
    private final java.util.Map<String, String> knownSignatures = new ConcurrentHashMap<>();
    private final java.util.Map<String, WatchedFile> badFiles = new ConcurrentHashMap<>();

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

            if (autoFix) {
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

    private void fixFile(File source, String rel, ScanResult scan, WatchedFile wf) {
        try {
            if (useGenerateStrategy && ffmpegPath != null) {
                FrameGenerator.generateForScan(source, scan.videoTrack, ffmpegPath, scan, null);
            }
            File outFile = new File(new File(contentDir, FIXED_SUBFOLDER_NAME), rel);
            File parent = outFile.getParentFile();
            if (parent != null) parent.mkdirs();

            RepairSummary summary = Mp4Repairer.repair(source, scan, outFile);
            File report = new File(parent, stripExt(outFile.getName()) + "_report.txt");
            ReportWriter.write(report, source, scan, true);

            wf.fixed = true;
            wf.fixedFile = outFile;
            wf.fixedAt = LocalDateTime.now();
            listener.onFileUpdated(wf);
            listener.onLog("Исправлено (" + summary.framesReplaced + " кадров): " + rel + " -> " + outFile.getAbsolutePath());
        } catch (Exception e) {
            listener.onLog("Не удалось исправить " + rel + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
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

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
