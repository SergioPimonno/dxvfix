package dxvfix.ffmpeg;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/** Finds (or lets the user configure) the ffmpeg executable used by Deep verification mode. */
public final class FfmpegLocator {

    private static final String PREF_KEY = "ffmpegPath";

    private FfmpegLocator() {
    }

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(FfmpegLocator.class);
    }

    public static String getConfiguredPath() {
        return prefs().get(PREF_KEY, null);
    }

    public static void setConfiguredPath(String path) {
        if (path == null || path.isBlank()) {
            prefs().remove(PREF_KEY);
        } else {
            prefs().put(PREF_KEY, path);
        }
    }

    /** Returns a working ffmpeg executable path, or null if none could be found. */
    public static String find() {
        String configured = getConfiguredPath();
        if (configured != null && works(configured)) {
            return configured;
        }
        if (works("ffmpeg")) {
            return "ffmpeg";
        }
        for (String candidate : commonCandidates()) {
            if (works(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Iterable<String> commonCandidates() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("C:\\ffmpeg\\bin\\ffmpeg.exe");
        out.add("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe");
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path wingetRoot = Path.of(localAppData, "Microsoft", "WinGet", "Packages");
            if (Files.isDirectory(wingetRoot)) {
                try (var pkgDirs = Files.list(wingetRoot)) {
                    pkgDirs.filter(p -> p.getFileName().toString().toLowerCase().contains("ffmpeg"))
                            .forEach(pkgDir -> findExeUnder(pkgDir, out));
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }

    private static void findExeUnder(Path dir, java.util.List<String> out) {
        try (var stream = Files.walk(dir, 4)) {
            stream.filter(p -> p.getFileName().toString().equalsIgnoreCase("ffmpeg.exe"))
                    .forEach(p -> out.add(p.toAbsolutePath().toString()));
        } catch (IOException ignored) {
        }
    }

    public static boolean works(String exePath) {
        try {
            Process p = new ProcessBuilder(exePath, "-version").redirectErrorStream(true).start();
            try (var in = p.getInputStream()) {
                in.readAllBytes();
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static File asFile(String exePath) {
        return "ffmpeg".equals(exePath) ? null : new File(exePath);
    }
}
