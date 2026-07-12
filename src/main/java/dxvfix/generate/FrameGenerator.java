package dxvfix.generate;

import dxvfix.mp4.CodecKind;
import dxvfix.mp4.Mp4Container;
import dxvfix.mp4.SampleInfo;
import dxvfix.mp4.TrackInfo;
import dxvfix.scan.FrameReport;
import dxvfix.scan.ScanResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Synthesizes a replacement frame for a single corrupt sample by motion-interpolating between its
 * two true presentation-order neighbors (via ffmpeg's {@code minterpolate} filter), then
 * re-encoding the result into the track's own codec as a single self-contained frame.
 * <p>
 * Scope, deliberately: only handles an isolated single bad frame with a good frame immediately
 * before and after it in presentation order (not decode order — those can differ once B-frames
 * are involved). Runs of 2+ consecutive bad frames, or a bad frame at the very start/end of the
 * timeline, return null so the caller falls back to plain duplication. Also unsupported: DXV
 * frames tagged DXT5/YCG6/YG10 (ffmpeg's DXV encoder can only produce DXT1 — no alpha, no
 * YCoCg — so DXV3 assets that use DXT5 for alpha, a common case, cannot be re-encoded this way).
 * <p>
 * The generated frame is always encoded as a self-contained intra/IDR frame so splicing it in
 * can't produce a "missing reference picture" hard failure. For H.264 that's clean in practice
 * (verified: only a benign "co located POCs unavailable" TMVP warning on neighboring frames). For
 * H.265, injecting an IDR resets picture-order-count numbering, which can desync the reference
 * picture set of several following frames in the same GOP (verified: ffmpeg logs "Could not find
 * ref with POC" / "Error constructing the frame RPS" for a handful of subsequent frames, though it
 * still recovers and finishes decoding — no crash). Recommend spot-checking HEVC output after a
 * generated repair.
 */
public final class FrameGenerator {

    private FrameGenerator() {
    }

    public interface ProgressListener {
        void onProgress(int done, int total);
    }

    /**
     * Attempts generation for every bad frame in the scan that has exactly one good frame
     * immediately before and after it in *presentation* order (not decode order), setting
     * {@link FrameReport#generatedContent} on success. Frames it can't handle (runs of 2+
     * consecutive bad frames, a bad frame at either end of the timeline, or an unsupported codec)
     * are left alone so the caller's existing duplicate-donor repair covers them instead.
     * Returns how many frames were successfully generated.
     */
    public static int generateForScan(File sourceFile, TrackInfo track, String ffmpegPath, ScanResult scan,
                                       ProgressListener listener) {
        List<FrameReport> byPts = new ArrayList<>(scan.frames);
        byPts.sort(Comparator.comparingDouble(f -> f.timestampSeconds));

        List<FrameReport> badFrames = new ArrayList<>();
        for (FrameReport f : scan.frames) if (f.isBad()) badFrames.add(f);

        int done = 0;
        int generated = 0;
        for (FrameReport bad : badFrames) {
            int pos = byPts.indexOf(bad);
            FrameReport prevF = pos > 0 ? byPts.get(pos - 1) : null;
            FrameReport nextF = pos < byPts.size() - 1 ? byPts.get(pos + 1) : null;
            done++;
            if (listener != null) listener.onProgress(done, badFrames.size());

            if (prevF == null || nextF == null || prevF.isBad() || nextF.isBad()) continue;
            String donorFormat = prevF.result.format;
            if (!isSupported(track.codecKind, donorFormat)) continue;

            SampleInfo prevSample = track.samples.get(prevF.index);
            SampleInfo nextSample = track.samples.get(nextF.index);
            byte[] content = generate(sourceFile, track, ffmpegPath, prevSample, nextSample);
            if (content != null) {
                bad.generatedContent = content;
                generated++;
            }
        }
        return generated;
    }

    public static boolean isSupported(CodecKind codec, String donorFormat) {
        switch (codec) {
            case PRORES:
            case H264:
            case H265:
                return true;
            case DXV:
                return donorFormat != null && donorFormat.startsWith("DXT1");
            default:
                return false;
        }
    }

    /** Returns the freshly-synthesized compressed sample bytes, or null if generation isn't possible/failed. */
    public static byte[] generate(File sourceFile, TrackInfo track, String ffmpegPath,
                                   SampleInfo prevGood, SampleInfo nextGood) {
        if (ffmpegPath == null || prevGood == null || nextGood == null) return null;

        long gapTicks = nextGood.presentationTime - prevGood.presentationTime;
        if (gapTicks <= 0 || gapTicks % 2 != 0) return null; // only the "exactly one frame missing" case
        long frameDurationTicks = gapTicks / 2;
        if (frameDurationTicks <= 0) return null;

        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("dxvfix-gen");
            File prevPng = new File(tmp.toFile(), "prev.png");
            File nextPng = new File(tmp.toFile(), "next.png");
            if (!extractFrame(ffmpegPath, sourceFile, prevGood.presentationTime, track.timescale, prevPng)) return null;
            if (!extractFrame(ffmpegPath, sourceFile, nextGood.presentationTime, track.timescale, nextPng)) return null;

            File seqDir = new File(tmp.toFile(), "seq");
            seqDir.mkdirs();
            Files.copy(prevPng.toPath(), new File(seqDir, "f000.png").toPath());
            Files.copy(prevPng.toPath(), new File(seqDir, "f001.png").toPath());
            Files.copy(nextPng.toPath(), new File(seqDir, "f002.png").toPath());
            Files.copy(nextPng.toPath(), new File(seqDir, "f003.png").toPath());

            String inputFps = track.timescale + "/" + (2 * frameDurationTicks);
            String targetFps = track.timescale + "/" + frameDurationTicks;

            File interpolated = interpolateMiddleFrame(ffmpegPath, tmp.toFile(), seqDir, inputFps, targetFps);
            if (interpolated == null) return null;

            return reencodeSingleFrame(ffmpegPath, tmp.toFile(), interpolated, track);
        } catch (Exception e) {
            return null;
        } finally {
            if (tmp != null) deleteRecursive(tmp.toFile());
        }
    }

    private static boolean extractFrame(String ffmpegPath, File source, long ticks, int timescale, File outPng) {
        double seconds = (double) ticks / (double) timescale;
        List<String> cmd = List.of(ffmpegPath, "-y", "-hide_banner", "-loglevel", "error",
                "-i", source.getAbsolutePath(),
                "-ss", String.format(java.util.Locale.ROOT, "%.6f", seconds),
                "-frames:v", "1", "-update", "1", outPng.getAbsolutePath());
        return run(cmd) && outPng.isFile() && outPng.length() > 0;
    }

    private static File interpolateMiddleFrame(String ffmpegPath, File workDir, File seqDir, String inputFps, String targetFps) {
        for (String miMode : new String[]{"mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1", "blend"}) {
            File outDir = new File(workDir, "out_" + miMode.hashCode());
            outDir.mkdirs();
            List<String> cmd = List.of(ffmpegPath, "-y", "-hide_banner", "-loglevel", "error",
                    "-framerate", inputFps,
                    "-i", new File(seqDir, "f%03d.png").getAbsolutePath(),
                    "-vf", "minterpolate=fps=" + targetFps + ":mi_mode=" + miMode,
                    new File(outDir, "out_%03d.png").getAbsolutePath());
            if (run(cmd)) {
                File target = new File(outDir, "out_004.png");
                if (target.isFile() && target.length() > 0) {
                    return target;
                }
            }
        }
        return null;
    }

    private static byte[] reencodeSingleFrame(String ffmpegPath, File workDir, File pngFrame, TrackInfo track) throws Exception {
        String[] encodeArgs = encodeArgsFor(track);
        if (encodeArgs == null) return null;

        File genMov = new File(workDir, "gen.mov");
        List<String> cmd = new java.util.ArrayList<>(List.of(ffmpegPath, "-y", "-hide_banner", "-loglevel", "error",
                "-i", pngFrame.getAbsolutePath()));
        cmd.addAll(List.of(encodeArgs));
        cmd.add("-frames:v");
        cmd.add("1");
        cmd.add(genMov.getAbsolutePath());
        if (!run(cmd) || !genMov.isFile() || genMov.length() == 0) return null;

        Mp4Container c = Mp4Container.parse(genMov);
        TrackInfo genTrack = c.findPrimaryVideoTrack();
        if (genTrack == null || genTrack.samples.isEmpty()) return null;
        SampleInfo s = genTrack.samples.get(0);
        byte[] buf = new byte[s.size];
        try (var raf = new java.io.RandomAccessFile(genMov, "r")) {
            raf.seek(s.offset);
            raf.readFully(buf);
        }
        return buf;
    }

    private static String[] encodeArgsFor(TrackInfo track) {
        switch (track.codecKind) {
            case H264:
                return new String[]{"-c:v", "libx264", "-pix_fmt", "yuv420p"};
            case H265:
                return new String[]{"-c:v", "libx265", "-pix_fmt", "yuv420p", "-tag:v", "hvc1"};
            case PRORES: {
                String profile;
                switch (track.fourcc) {
                    case "apco": profile = "0"; break;
                    case "apcs": profile = "1"; break;
                    case "apcn": profile = "2"; break;
                    case "apch": profile = "3"; break;
                    case "ap4h": case "ap4x": profile = "4"; break;
                    default: profile = "2"; break;
                }
                boolean alpha = track.fourcc.equals("ap4h") || track.fourcc.equals("ap4x");
                String pixFmt = alpha ? "yuva444p10le" : "yuv422p10le";
                return new String[]{"-c:v", "prores_ks", "-profile:v", profile, "-pix_fmt", pixFmt};
            }
            case DXV:
                return new String[]{"-c:v", "dxv", "-format", "dxt1", "-pix_fmt", "rgba"};
            default:
                return null;
        }
    }

    private static boolean run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (var in = p.getInputStream()) {
                in.readAllBytes();
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
