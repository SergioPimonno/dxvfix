package dxvfix.ffmpeg;

import dxvfix.mp4.SampleInfo;
import dxvfix.mp4.TrackInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-checks fast structural validation by actually decoding the video track with ffmpeg and
 * comparing the set of presentation timestamps the decoder actually produced against the set we
 * expect from the container's sample table (dts + ctts). Any expected timestamp with no matching
 * output frame means ffmpeg silently dropped or failed to produce that frame — which is exactly
 * the class of corruption a hand-written structural check for AVC/HEVC entropy coding can't catch
 * without reimplementing a full decoder.
 */
public final class DeepDecodeValidator {

    public interface ProgressListener {
        void onProgress(int doneEstimate, int total);
    }

    public static final class DeepFinding {
        public final int sampleIndex;
        public final String detail;

        DeepFinding(int sampleIndex, String detail) {
            this.sampleIndex = sampleIndex;
            this.detail = detail;
        }
    }

    private static final Pattern SHOWINFO_LINE =
            Pattern.compile("n:\\s*(-?\\d+)\\s+pts:\\s*(-?\\d+)\\s+pts_time:(-?[0-9.]+)");

    private DeepDecodeValidator() {
    }

    public static List<DeepFinding> validate(File file, TrackInfo track, String ffmpegPath, ProgressListener listener) throws IOException {
        if (ffmpegPath == null) {
            throw new IOException("ffmpeg was not found; Deep mode requires ffmpeg (configure its path in Settings)");
        }
        int n = track.samples.size();
        if (n == 0) {
            return List.of();
        }

        double epsilon = estimateEpsilon(track);
        double[] expectedPts = new double[n];
        for (int i = 0; i < n; i++) {
            SampleInfo s = track.samples.get(i);
            expectedPts[i] = (double) s.presentationTime / (double) track.timescale;
        }

        TreeSet<Double> observed = new TreeSet<>();
        int errorLineCount = 0;
        String lastErrorLine = null;

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath, "-v", "info", "-nostats",
                "-i", file.getAbsolutePath(),
                "-an", "-sn", "-dn",
                "-map", "0:v:0",
                "-vf", "showinfo",
                "-vsync", "0",
                "-f", "null", "-");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = SHOWINFO_LINE.matcher(line);
                if (m.find()) {
                    try {
                        double ptsTime = Double.parseDouble(m.group(3));
                        observed.add(ptsTime);
                        int nOut = Integer.parseInt(m.group(1));
                        if (listener != null) listener.onProgress(Math.min(n, nOut + 1), n);
                    } catch (NumberFormatException ignored) {
                    }
                } else if (looksLikeError(line)) {
                    errorLineCount++;
                    lastErrorLine = line.trim();
                }
            }
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<DeepFinding> findings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!hasNearby(observed, expectedPts[i], epsilon)) {
                String detail = "ffmpeg produced no decoded frame at pts~" + String.format(java.util.Locale.ROOT, "%.3f", expectedPts[i]) + "s"
                        + (errorLineCount > 0 ? " (" + errorLineCount + " decoder error line(s) seen"
                        + (lastErrorLine != null ? ", last: " + lastErrorLine : "") + ")" : "");
                findings.add(new DeepFinding(i, detail));
            }
        }
        return findings;
    }

    private static boolean hasNearby(TreeSet<Double> observed, double target, double epsilon) {
        Double floor = observed.floor(target + epsilon);
        if (floor != null && Math.abs(floor - target) <= epsilon) return true;
        Double ceil = observed.ceiling(target - epsilon);
        return ceil != null && Math.abs(ceil - target) <= epsilon;
    }

    private static double estimateEpsilon(TrackInfo track) {
        int n = track.samples.size();
        if (n >= 2 && track.timescale > 0) {
            long firstDts = track.samples.get(0).decodeTime;
            long lastDts = track.samples.get(n - 1).decodeTime;
            double avgDelta = (double) (lastDts - firstDts) / (n - 1) / track.timescale;
            if (avgDelta > 0) {
                return Math.max(0.4 * avgDelta, 0.001);
            }
        }
        return 0.02;
    }

    private static boolean looksLikeError(String line) {
        String l = line.toLowerCase(java.util.Locale.ROOT);
        return l.contains("error") || l.contains("invalid data") || l.contains("corrupt")
                || l.contains("missing") && l.contains("frame");
    }
}
