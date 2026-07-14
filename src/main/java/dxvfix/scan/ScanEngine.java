package dxvfix.scan;

import dxvfix.dxv.DxvFrameCheck;
import dxvfix.ffmpeg.DeepDecodeValidator;
import dxvfix.h26x.NalFrameCheck;
import dxvfix.mp4.Mp4Container;
import dxvfix.mp4.SampleInfo;
import dxvfix.mp4.TrackInfo;
import dxvfix.notchlc.NotchLcFrameCheck;
import dxvfix.prores.ProResFrameCheck;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

public final class ScanEngine {

    public enum Mode {
        /** Per-sample structural checks only: fast, no external process required. */
        FAST,
        /** FAST checks plus a real ffmpeg decode pass that cross-validates every frame. */
        DEEP
    }

    public interface ProgressListener {
        void onProgress(int done, int total);
    }

    private ScanEngine() {
    }

    public static ScanResult scan(File file, Mode mode, String ffmpegPath, ProgressListener listener) throws IOException {
        Mp4Container container = Mp4Container.parse(file);
        TrackInfo track = container.findPrimaryVideoTrack();
        if (track == null) {
            throw new IOException("No video track found in file");
        }
        if (track.width <= 0 || track.height <= 0) {
            throw new IOException("Could not determine video dimensions (width/height) from stsd");
        }

        ScanResult result = new ScanResult(container, track);
        int total = track.samples.size();
        int fastBudget = mode == Mode.DEEP ? Math.max(1, (int) (total * 0.6)) : total;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            for (SampleInfo s : track.samples) {
                byte[] buf = new byte[s.size];
                raf.seek(s.offset);
                raf.readFully(buf);
                FrameCheckResult vr = checkFast(track, buf);
                double ts = (double) s.presentationTime / (double) track.timescale;
                FrameReport fr = new FrameReport(s.index, s.offset, s.size, ts, vr);
                if (track.codecKind == dxvfix.mp4.CodecKind.H264 || track.codecKind == dxvfix.mp4.CodecKind.H265) {
                    fr.intraOnly = NalFrameCheck.isIntraOnly(buf, track.codecKind, track.nalLengthSize);
                }
                result.frames.add(fr);
                if (listener != null) {
                    listener.onProgress(total == 0 ? 0 : (s.index + 1) * fastBudget / total, total);
                }
            }
        }

        if (mode == Mode.DEEP) {
            List<DeepDecodeValidator.DeepFinding> findings = DeepDecodeValidator.validate(file, track, ffmpegPath,
                    (done, dtotal) -> {
                        if (listener != null) {
                            int overall = fastBudget + (dtotal == 0 ? 0 : done * (total - fastBudget) / dtotal);
                            listener.onProgress(Math.min(total, overall), total);
                        }
                    });
            for (DeepDecodeValidator.DeepFinding f : findings) {
                FrameReport existing = result.frames.get(f.sampleIndex);
                if (!existing.isBad()) {
                    FrameCheckResult deepResult = new FrameCheckResult(
                            FrameCheckResult.Status.DEEP_DECODE_FAILED, f.detail, existing.result.format);
                    result.frames.set(f.sampleIndex, existing.withResult(deepResult));
                }
            }
        }

        boolean interPredictedCodec = track.codecKind == dxvfix.mp4.CodecKind.H264 || track.codecKind == dxvfix.mp4.CodecKind.H265;
        assignReplacements(result, interPredictedCodec);
        if (listener != null) listener.onProgress(total, total);
        return result;
    }

    private static FrameCheckResult checkFast(TrackInfo track, byte[] buf) {
        switch (track.codecKind) {
            case DXV:
                return DxvFrameCheck.check(buf, track.width, track.height);
            case PRORES:
                return ProResFrameCheck.check(buf, track.width, track.height);
            case H264:
            case H265:
                return NalFrameCheck.check(buf, track.codecKind, track.nalLengthSize);
            case NOTCHLC:
                return NotchLcFrameCheck.check(buf, track.width, track.height);
            default:
                return new FrameCheckResult(FrameCheckResult.Status.OK_SHALLOW,
                        "no fast structural validator for this codec; use Deep mode", track.codecKind.label());
        }
    }

    /**
     * For every bad frame, pick a good frame to duplicate. For H.264/H.265 this strongly prefers
     * a guaranteed-intra donor (see NalFrameCheck.isIntraOnly) when one exists anywhere in the
     * file: an inter-predicted (P/B) frame's compressed bytes generally will NOT decode correctly
     * if moved to a different stream position, since its motion compensation needs specific
     * reference pictures that differ there (verified empirically via ffmpeg's "Missing reference
     * picture" decode errors). An intra donor needs no references, so it decodes correctly
     * anywhere, exactly like DXV/ProRes frames.
     */
    private static void assignReplacements(ScanResult result, boolean preferIntraDonor) {
        int n = result.frames.size();
        for (int i = 0; i < n; i++) {
            FrameReport f = result.frames.get(i);
            if (!f.isBad()) continue;

            int best = -1;
            if (preferIntraDonor) {
                best = nearestGood(result, i, n, true);
            }
            boolean usedFallback = false;
            if (best < 0) {
                best = nearestGood(result, i, n, false);
                usedFallback = preferIntraDonor;
            }
            f.replacementSampleIndex = best;
            f.repairCaveat = usedFallback && best >= 0 && !result.frames.get(best).intraOnly;
        }
    }

    private static int nearestGood(ScanResult result, int i, int n, boolean intraOnlyDonor) {
        for (int d = 1; d < n; d++) {
            int prev = i - d;
            int next = i + d;
            if (prev >= 0 && isEligibleDonor(result, prev, intraOnlyDonor)) return prev;
            if (next < n && isEligibleDonor(result, next, intraOnlyDonor)) return next;
            if (prev < 0 && next >= n) break;
        }
        return -1;
    }

    private static boolean isEligibleDonor(ScanResult result, int idx, boolean intraOnlyDonor) {
        FrameReport f = result.frames.get(idx);
        if (f.isBad()) return false;
        return !intraOnlyDonor || f.intraOnly;
    }
}
