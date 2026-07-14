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
 * Downloads a selected release's full app package (a zip of the platform's app-image, matching
 * what {@code build-app-image.ps1}/{@code build-app-image-mac.sh} produce) and applies it in
 * place, replacing every file the running install uses.
 * <p>
 * The tricky part is that the currently-running JVM has its own jar (and, for an app-image
 * install, its own launcher executable and bundled runtime) open, and Windows won't let anything
 * overwrite a file that's still open elsewhere -- so the swap can't happen while this process is
 * still alive. Instead {@link #applyAndRestart} writes a small relaunch script (a Windows batch
 * file, or a POSIX shell script elsewhere) that: waits for this process to exit, extracts the
 * downloaded zip, retries for up to ~30s until the extraction lands, replaces the install (either
 * mirroring the whole app-image directory/bundle, or -- for a bare jar+lib install with no
 * app-image -- just swapping the jar and FlatLaf lib), relaunches the app either way, then deletes
 * itself. The caller is expected to call this last and then exit the application immediately
 * afterward.
 */
public final class UpdateManager {

    /** Matches the --name passed to jpackage in build-app-image.ps1. */
    private static final String APP_IMAGE_EXE_NAME = "DXVFrameDoctor.exe";
    /** The jpackage app-image's root folder name on Windows -- also the zip's top-level entry. */
    private static final String WINDOWS_APP_IMAGE_ROOT_NAME = "DXVFrameDoctor";
    /** The jpackage app bundle's name on macOS -- also the zip's top-level entry. */
    private static final String MAC_APP_BUNDLE_NAME = "DXVFrameDoctor.app";
    /** How deep to walk up from the running jar looking for a jpackage macOS app bundle (Contents/app/<jar> -> Contents -> <Name>.app). */
    private static final int MAC_APP_BUNDLE_SEARCH_DEPTH = 4;

    public interface ProgressListener {
        /** total may be -1 if the server didn't report Content-Length. */
        void onProgress(long downloadedBytes, long totalBytes, String phase);
    }

    private UpdateManager() {
    }

    /** Where the running install actually lives, and how to relaunch it once updated. */
    private record InstallLayout(Path installRoot, List<String> relaunchCommand) {
        boolean isAppImage() {
            return installRoot != null;
        }
    }

    /** Downloads the given version's app package zip to a temp file and sanity-checks it. Doesn't apply it. */
    public static Path download(VersionManifest version, ProgressListener listener) throws IOException, InterruptedException {
        Path tempZip = Files.createTempFile("dxvfix-update-", ".zip");
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
                 OutputStream out = Files.newOutputStream(tempZip)) {
                byte[] buf = new byte[1 << 16];
                long downloaded = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    report(listener, downloaded, total, Messages.get("update.downloading"));
                }
            }

            validateZip(tempZip);
            report(listener, total, total, Messages.get("update.done"));
            return tempZip;
        } catch (Exception e) {
            Files.deleteIfExists(tempZip);
            throw e;
        }
    }

    private static void validateZip(Path zip) throws IOException {
        long size = Files.size(zip);
        if (size < 1024) {
            throw new IOException("Downloaded file is suspiciously small (" + size + " bytes) -- not applying it");
        }
        byte[] header = new byte[2];
        try (InputStream in = Files.newInputStream(zip)) {
            int n = in.read(header);
            if (n < 2 || header[0] != 'P' || header[1] != 'K') {
                throw new IOException("Downloaded file is not a valid zip archive");
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
    public static void applyAndRestart(Path downloadedZip) throws IOException {
        Path currentJar = currentJarPath();
        Path appDir = currentJar.getParent();
        InstallLayout layout = resolveInstallLayout(appDir, currentJar);
        Path stageDir = downloadedZip.resolveSibling("dxvfix-update-stage");

        if (dxvfix.util.Platform.WINDOWS) {
            Path script = Files.createTempFile("dxvfix-update-", ".bat");
            Files.writeString(script,
                    buildRelaunchScriptWindows(downloadedZip, stageDir, currentJar, appDir, layout),
                    StandardCharsets.UTF_8);

            // "DXVUpdate" here is just the new console window's title, required by `start`
            // whenever the next argument is itself quoted (otherwise it mistakes the quoted path
            // for the title) -- a plain non-empty word sidesteps that ambiguity entirely, unlike
            // the more common "" empty-title idiom, which risks getting double-quoted into "\"\""
            // once Java's ProcessBuilder and then cmd.exe /c each re-parse the argument list.
            new ProcessBuilder("cmd", "/c", "start", "/min", "DXVUpdate", script.toAbsolutePath().toString())
                    .start();
        } else {
            Path script = Files.createTempFile("dxvfix-update-", ".sh");
            Files.writeString(script,
                    buildRelaunchScriptUnix(downloadedZip, stageDir, currentJar, appDir, layout),
                    StandardCharsets.UTF_8);
            script.toFile().setExecutable(true);

            // Wrapped in its own `sh -c '... &'` (rather than passed straight to ProcessBuilder)
            // so the script is backgrounded and detached from this JVM's process group -- otherwise
            // it would be a direct child that could get signaled/reaped alongside this process on
            // exit, before it ever gets to relaunch the app.
            new ProcessBuilder("/bin/sh", "-c", "nohup /bin/sh \"" + script.toAbsolutePath() + "\" >/dev/null 2>&1 &")
                    .start();
        }
    }

    /**
     * Determines whether this is running from a packaged app-image (and where its root is), or a
     * bare jar+lib install, and how to relaunch it either way. Prefers the app-image's own
     * launcher -- the native .exe on Windows, the enclosing .app bundle via {@code open} on macOS
     * -- since that's the "real" way the app was started for anyone using the packaged
     * distribution; falls back to a plain {@code java}/{@code javaw} invocation otherwise.
     */
    private static InstallLayout resolveInstallLayout(Path appDir, Path currentJar) {
        if (dxvfix.util.Platform.MAC) {
            Path bundle = findMacAppBundle(currentJar);
            if (bundle != null) {
                return new InstallLayout(bundle, List.of("open", bundle.toAbsolutePath().toString()));
            }
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
            return new InstallLayout(null, List.of(java, "-jar", currentJar.toAbsolutePath().toString()));
        }

        if (appDir != null && appDir.getParent() != null) {
            Path exeSibling = appDir.getParent().resolve(APP_IMAGE_EXE_NAME);
            if (Files.isRegularFile(exeSibling)) {
                return new InstallLayout(appDir.getParent(), List.of(exeSibling.toAbsolutePath().toString()));
            }
        }
        String javaw = Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toAbsolutePath().toString();
        return new InstallLayout(null, List.of(javaw, "-jar", currentJar.toAbsolutePath().toString()));
    }

    /**
     * jpackage's macOS app-image layout nests the jar at {@code <Name>.app/Contents/app/<jar>} --
     * walks up from there looking for the {@code .app}-suffixed bundle root. Returns null for a
     * bare jar+lib install (no enclosing bundle within the search depth).
     */
    private static Path findMacAppBundle(Path currentJar) {
        Path dir = currentJar.getParent();
        for (int i = 0; i < MAC_APP_BUNDLE_SEARCH_DEPTH && dir != null; i++) {
            Path name = dir.getFileName();
            if (name != null && name.toString().endsWith(".app")) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Package-visible for testing -- builds the batch script text without touching the
     * filesystem. Extracts the zip via PowerShell's Expand-Archive (more reliable across Windows
     * versions than relying on bsdtar's zip support), then either mirrors the whole app-image
     * directory (robocopy /MIR) or, for a bare jar+lib install, swaps just dxvfix.jar and the
     * FlatLaf jar.
     */
    static String buildRelaunchScriptWindows(Path newZip, Path stageDir, Path targetJar, Path appDir, InstallLayout layout) {
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
        sb.append("rmdir /s /q \"").append(stageDir).append("\" > nul 2>&1\r\n");
        sb.append("powershell -NoProfile -Command \"Expand-Archive -Path '").append(newZip)
                .append("' -DestinationPath '").append(stageDir).append("' -Force\" > nul 2>&1\r\n");
        sb.append("if exist \"").append(stageDir).append("\\").append(WINDOWS_APP_IMAGE_ROOT_NAME)
                .append("\" goto extracted\r\n");
        sb.append("if %count% lss 30 goto retry\r\n");
        sb.append("goto cleanup\r\n");
        sb.append(":extracted\r\n");

        Path extractedRoot = stageDir.resolve(WINDOWS_APP_IMAGE_ROOT_NAME);
        if (layout.isAppImage()) {
            sb.append("robocopy \"").append(extractedRoot).append("\" \"").append(layout.installRoot())
                    .append("\" /MIR /NFL /NDL /NJH /NJS /R:3 /W:1\r\n");
        } else {
            sb.append("copy /Y \"").append(extractedRoot.resolve("app").resolve("dxvfix.jar"))
                    .append("\" \"").append(targetJar.toAbsolutePath()).append("\" > nul 2>&1\r\n");
            Path libDir = appDir.resolve("lib");
            sb.append("if not exist \"").append(libDir).append("\" mkdir \"").append(libDir).append("\"\r\n");
            sb.append("for %%F in (\"").append(extractedRoot.resolve("app")).append("\\flatlaf-*.jar\") do copy /Y \"%%F\" \"")
                    .append(libDir).append("\\\" > nul 2>&1\r\n");
        }

        sb.append("start \"\" ");
        for (String part : layout.relaunchCommand()) {
            sb.append('"').append(part).append("\" ");
        }
        sb.append("\r\n");
        sb.append(":cleanup\r\n");
        sb.append("rmdir /s /q \"").append(stageDir).append("\" > nul 2>&1\r\n");
        sb.append("del \"").append(newZip.toAbsolutePath()).append("\" > nul 2>&1\r\n");
        sb.append("del \"%~f0\"\r\n");
        return sb.toString();
    }

    /**
     * Package-visible for testing -- builds the shell script text without touching the
     * filesystem. Extracts via {@code ditto} (correctly preserves a macOS .app bundle's
     * permissions/symlinks, unlike a plain zip extractor), then either replaces the whole bundle
     * or, for a bare jar+lib install, swaps just dxvfix.jar and the FlatLaf jar.
     */
    static String buildRelaunchScriptUnix(Path newZip, Path stageDir, Path targetJar, Path appDir, InstallLayout layout) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/sh\n");
        sb.append("count=0\n");
        sb.append("extracted=0\n");
        sb.append("while [ \"$count\" -lt 30 ]; do\n");
        sb.append("  count=$((count + 1))\n");
        sb.append("  sleep 1\n");
        sb.append("  rm -rf \"").append(stageDir).append("\"\n");
        sb.append("  mkdir -p \"").append(stageDir).append("\"\n");
        sb.append("  if ditto -x -k \"").append(newZip.toAbsolutePath()).append("\" \"").append(stageDir)
                .append("\" 2>/dev/null && [ -d \"").append(stageDir).append('/').append(MAC_APP_BUNDLE_NAME)
                .append("\" ]; then\n");
        sb.append("    extracted=1\n");
        sb.append("    break\n");
        sb.append("  fi\n");
        sb.append("done\n");

        Path extractedBundle = stageDir.resolve(MAC_APP_BUNDLE_NAME);
        sb.append("if [ \"$extracted\" = \"1\" ]; then\n");
        if (layout.isAppImage()) {
            sb.append("  rm -rf \"").append(layout.installRoot()).append("\"\n");
            sb.append("  mv \"").append(extractedBundle).append("\" \"").append(layout.installRoot()).append("\"\n");
        } else {
            Path extractedApp = extractedBundle.resolve("Contents").resolve("app");
            sb.append("  cp -f \"").append(extractedApp.resolve("dxvfix.jar")).append("\" \"")
                    .append(targetJar.toAbsolutePath()).append("\" 2>/dev/null\n");
            Path libDir = appDir.resolve("lib");
            sb.append("  mkdir -p \"").append(libDir).append("\"\n");
            sb.append("  cp -f \"").append(extractedApp).append("/\"flatlaf-*.jar \"").append(libDir)
                    .append("/\" 2>/dev/null\n");
        }
        sb.append("fi\n");

        sb.append("rm -rf \"").append(stageDir).append("\"\n");
        sb.append("rm -f \"").append(newZip.toAbsolutePath()).append("\"\n");
        sb.append("nohup");
        for (String part : layout.relaunchCommand()) {
            sb.append(" \"").append(part).append('"');
        }
        sb.append(" >/dev/null 2>&1 &\n");
        sb.append("rm -f \"$0\"\n");
        return sb.toString();
    }

    private static void report(ProgressListener listener, long downloaded, long total, String phase) {
        if (listener != null) listener.onProgress(downloaded, total, phase);
    }
}
