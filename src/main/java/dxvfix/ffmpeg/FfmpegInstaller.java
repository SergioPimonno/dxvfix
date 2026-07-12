package dxvfix.ffmpeg;

import dxvfix.i18n.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Pattern;

/**
 * Downloads a prebuilt Windows ffmpeg binary and installs it locally, for users who don't already
 * have ffmpeg on PATH. Source: gyan.dev's "essentials" build, a well-known stable URL that always
 * resolves to the latest release (the same distribution the winget package Gyan.FFmpeg installs).
 */
public final class FfmpegInstaller {

    public static final String DOWNLOAD_URL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";

    private static final Pattern FFMPEG_EXE = Pattern.compile(".*/bin/ffmpeg\\.exe$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FFPROBE_EXE = Pattern.compile(".*/bin/ffprobe\\.exe$", Pattern.CASE_INSENSITIVE);

    public interface ProgressListener {
        /** total may be -1 if the server didn't report Content-Length. */
        void onProgress(long downloadedBytes, long totalBytes, String phase);
    }

    private FfmpegInstaller() {
    }

    public static Path installDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData != null ? Path.of(localAppData) : Path.of(System.getProperty("user.home"));
        return base.resolve("DxvFrameDoctor").resolve("ffmpeg").resolve("bin");
    }

    /** Downloads and extracts ffmpeg.exe (and ffprobe.exe if present), returning the ffmpeg.exe path. */
    public static Path downloadAndInstall(ProgressListener listener) throws IOException, InterruptedException {
        Path targetDir = installDir();
        Files.createDirectories(targetDir);

        Path tempZip = Files.createTempFile("ffmpeg-download", ".zip");
        try {
            report(listener, 0, -1, Messages.get("ffmpegInstaller.connecting", DOWNLOAD_URL));
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(DOWNLOAD_URL))
                    .timeout(Duration.ofMinutes(10))
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
                    report(listener, downloaded, total, Messages.get("ffmpegInstaller.downloading"));
                }
            }

            report(listener, total, total, Messages.get("ffmpegInstaller.extracting"));
            Path ffmpegExe = extractOne(tempZip, targetDir, FFMPEG_EXE, "ffmpeg.exe");
            if (ffmpegExe == null) {
                throw new IOException(Messages.get("ffmpegInstaller.exeNotFoundInArchive"));
            }
            extractOne(tempZip, targetDir, FFPROBE_EXE, "ffprobe.exe"); // best-effort, not required

            report(listener, total, total, Messages.get("ffmpegInstaller.done"));
            return ffmpegExe;
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    private static Path extractOne(Path zipFile, Path targetDir, Pattern namePattern, String outName) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && namePattern.matcher(name).matches()) {
                    Path out = targetDir.resolve(outName);
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                    return out;
                }
            }
        }
        return null;
    }

    private static void report(ProgressListener listener, long downloaded, long total, String phase) {
        if (listener != null) listener.onProgress(downloaded, total, phase);
    }
}
