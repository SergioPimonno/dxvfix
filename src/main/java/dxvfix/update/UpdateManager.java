package dxvfix.update;

import dxvfix.i18n.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads a selected release's {@code dxvfix.jar} and applies it in place.
 * <p>
 * The tricky part is that the currently-running JVM has its own jar open, and Windows won't let
 * anything overwrite a file that's still open elsewhere -- so the swap can't happen while this
 * process is still alive. Instead {@link #applyAndRestart} writes a small batch script that:
 * waits for this process to exit, retries copying the new jar over the old one for up to ~30s
 * (Windows needs a moment after process exit to actually release the file handle), relaunches the
 * app either way, then deletes itself. The caller is expected to call this last and then exit the
 * application immediately afterward.
 */
public final class UpdateManager {

    /** Matches the --name passed to jpackage in build-app-image.ps1. */
    private static final String APP_IMAGE_EXE_NAME = "DXVFrameDoctor.exe";

    public interface ProgressListener {
        /** total may be -1 if the server didn't report Content-Length. */
        void onProgress(long downloadedBytes, long totalBytes, String phase);
    }

    private UpdateManager() {
    }

    /** Downloads the given version's dxvfix.jar to a temp file and sanity-checks it. Doesn't apply it. */
    public static Path download(VersionManifest version, ProgressListener listener) throws IOException, InterruptedException {
        Path tempJar = Files.createTempFile("dxvfix-update-", ".jar");
        try {
            report(listener, 0, -1, Messages.get("update.connecting", version.downloadUrl()));
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(version.downloadUrl()))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Download failed: HTTP " + response.statusCode());
            }
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);

            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(tempJar)) {
                byte[] buf = new byte[1 << 16];
                long downloaded = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    report(listener, downloaded, total, Messages.get("update.downloading"));
                }
            }

            validateJar(tempJar);
            report(listener, total, total, Messages.get("update.done"));
            return tempJar;
        } catch (Exception e) {
            Files.deleteIfExists(tempJar);
            throw e;
        }
    }

    private static void validateJar(Path jar) throws IOException {
        long size = Files.size(jar);
        if (size < 1024) {
            throw new IOException("Downloaded file is suspiciously small (" + size + " bytes) -- not applying it");
        }
        byte[] header = new byte[2];
        try (InputStream in = Files.newInputStream(jar)) {
            int n = in.read(header);
            if (n < 2 || header[0] != 'P' || header[1] != 'K') {
                throw new IOException("Downloaded file is not a valid jar/zip archive");
            }
        }
    }

    /**
     * Locates the jar this app is actually running from right now, on disk.
     * Works whether launched via {@code java -jar dxvfix.jar} or via the jpackage app-image
     * launcher (jpackage's generated launcher still loads classes from a real dxvfix.jar file on
     * disk, so the code source resolves the same way either way).
     */
    public static Path currentJarPath() throws IOException {
        CodeSource source = UpdateManager.class.getProtectionDomain().getCodeSource();
        if (source == null) {
            throw new IOException("Could not determine the running jar's location");
        }
        try {
            return Path.of(source.getLocation().toURI());
        } catch (Exception e) {
            throw new IOException("Could not resolve the running jar's path", e);
        }
    }

    /**
     * Writes and launches (detached) the relaunch script described in the class doc. Call this
     * last, then exit the application -- the script only starts doing its work once this JVM
     * process actually terminates.
     */
    public static void applyAndRestart(Path downloadedJar) throws IOException {
        Path currentJar = currentJarPath();
        Path appDir = currentJar.getParent();

        List<String> relaunchCommand = resolveRelaunchCommand(appDir, currentJar);

        Path script = Files.createTempFile("dxvfix-update-", ".bat");
        Files.writeString(script, buildRelaunchScript(downloadedJar, currentJar, relaunchCommand), StandardCharsets.UTF_8);

        // "DXVUpdate" here is just the new console window's title, required by `start` whenever
        // the next argument is itself quoted (otherwise it mistakes the quoted path for the
        // title) -- a plain non-empty word sidesteps that ambiguity entirely, unlike the more
        // common "" empty-title idiom, which risks getting double-quoted into "\"\"" once Java's
        // ProcessBuilder and then cmd.exe /c each re-parse the argument list.
        new ProcessBuilder("cmd", "/c", "start", "/min", "DXVUpdate", script.toAbsolutePath().toString())
                .start();
    }

    /**
     * Prefers relaunching via the app-image's native .exe (one directory up from the jar, per the
     * layout build-app-image.ps1 produces) when present, since that's the "real" way the app was
     * started for anyone using the packaged distribution; falls back to {@code javaw -jar} for a
     * plain jar+lib install.
     */
    private static List<String> resolveRelaunchCommand(Path appDir, Path currentJar) {
        if (appDir != null && appDir.getParent() != null) {
            Path exeSibling = appDir.getParent().resolve(APP_IMAGE_EXE_NAME);
            if (Files.isRegularFile(exeSibling)) {
                List<String> cmd = new ArrayList<>();
                cmd.add(exeSibling.toAbsolutePath().toString());
                return cmd;
            }
        }
        String javaHome = System.getProperty("java.home");
        Path javaw = Path.of(javaHome, "bin", "javaw.exe");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaw.toAbsolutePath().toString());
        cmd.add("-jar");
        cmd.add(currentJar.toAbsolutePath().toString());
        return cmd;
    }

    /** Package-visible for testing -- builds the batch script text without touching the filesystem. */
    static String buildRelaunchScript(Path newJar, Path targetJar, List<String> relaunchCommand) {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("setlocal\r\n");
        sb.append("set /a count=0\r\n");
        sb.append(":retry\r\n");
        sb.append("set /a count+=1\r\n");
        // NOT `timeout /t 1` -- it refuses to run ("Input redirection is not supported") when
        // launched non-interactively, which is exactly how this script itself is started (via
        // ProcessBuilder / `start`, with no real console attached). `ping` to localhost is the
        // standard workaround: each of the 2 pings is paced about a second apart regardless of
        // console availability, giving an effective ~1s delay per retry.
        sb.append("ping 127.0.0.1 -n 2 > nul\r\n");
        sb.append("copy /Y \"").append(newJar.toAbsolutePath()).append("\" \"").append(targetJar.toAbsolutePath()).append("\" > nul 2>&1\r\n");
        sb.append("if not errorlevel 1 goto relaunch\r\n");
        sb.append("if %count% lss 30 goto retry\r\n");
        sb.append(":relaunch\r\n");
        sb.append("del \"").append(newJar.toAbsolutePath()).append("\" > nul 2>&1\r\n");
        sb.append("start \"\" ");
        for (String part : relaunchCommand) {
            sb.append('"').append(part).append("\" ");
        }
        sb.append("\r\n");
        sb.append("del \"%~f0\"\r\n");
        return sb.toString();
    }

    private static void report(ProgressListener listener, long downloaded, long total, String phase) {
        if (listener != null) listener.onProgress(downloaded, total, phase);
    }
}
