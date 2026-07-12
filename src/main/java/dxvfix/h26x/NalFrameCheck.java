package dxvfix.h26x;

import dxvfix.mp4.CodecKind;
import dxvfix.scan.FrameCheckResult;

/**
 * Fast structural validator for length-prefixed H.264/H.265 access units, as stored in
 * avc1/avc3/hvc1/hev1 MP4 samples (ISO/IEC 14496-15). It only walks NAL unit framing: does the
 * length-prefix chain exactly cover the sample, and are the NAL header bits legal. It does not
 * touch slice headers, CABAC/CAVLC, or reference frame management, because those genuinely
 * require a real decoder — for AVC/HEVC that gap is meant to be closed by Deep mode (real ffmpeg
 * decode), not by a hand-rolled entropy decoder here. Consequently a clean result from this
 * checker is only ever {@code OK_SHALLOW}, never {@code OK}.
 */
public final class NalFrameCheck {

    private NalFrameCheck() {
    }

    public static FrameCheckResult check(byte[] s, CodecKind codec, int nalLengthSize) {
        if (nalLengthSize < 1 || nalLengthSize > 4) {
            return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                    "unsupported NAL length size " + nalLengthSize, codec.label());
        }
        if (s.length == 0) {
            return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID, "empty sample", codec.label());
        }

        int pos = 0;
        int nalCount = 0;
        while (pos < s.length) {
            if (pos + nalLengthSize > s.length) {
                return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                        "truncated NAL length prefix at byte " + pos, codec.label());
            }
            long len = readLength(s, pos, nalLengthSize);
            pos += nalLengthSize;
            if (len <= 0) {
                return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                        "zero/negative-length NAL at byte " + pos, codec.label());
            }
            if (pos + len > s.length) {
                return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                        "NAL length " + len + " at byte " + pos + " overruns sample (remaining " + (s.length - pos) + ")", codec.label());
            }

            int b0 = s[pos] & 0xFF;
            if (codec == CodecKind.H264) {
                boolean forbiddenBit = (b0 & 0x80) != 0;
                int nalType = b0 & 0x1F;
                if (forbiddenBit) {
                    return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                            "NAL forbidden_zero_bit set at byte " + pos + " (type=" + nalType + ")", codec.label());
                }
            } else { // H265
                if (len < 2) {
                    return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                            "H.265 NAL shorter than its 2-byte header at byte " + pos, codec.label());
                }
                int b1 = s[pos + 1] & 0xFF;
                boolean forbiddenBit = (b0 & 0x80) != 0;
                int temporalIdPlus1 = b1 & 0x07;
                if (forbiddenBit) {
                    return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                            "NAL forbidden_zero_bit set at byte " + pos, codec.label());
                }
                if (temporalIdPlus1 == 0) {
                    return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                            "NAL nuh_temporal_id_plus1 is 0 (forbidden) at byte " + pos, codec.label());
                }
            }

            pos += (int) len;
            nalCount++;
        }

        if (nalCount == 0) {
            return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID, "no NAL units found", codec.label());
        }

        return new FrameCheckResult(FrameCheckResult.Status.OK_SHALLOW,
                nalCount + " NAL unit(s), framing OK (no entropy-level check; use Deep mode for full decode validation)",
                codec.label());
    }

    /**
     * True only if every VCL NAL in this access unit is guaranteed intra-coded by its NAL type
     * alone (H.264 nal_unit_type 5 = IDR slice; H.265 nal_unit_type 19-21 = IRAP picture). Such a
     * frame needs no reference pictures to decode, so its compressed bytes can be duplicated to
     * any other position in the stream and will still decode correctly there (same principle as
     * DXV/ProRes intra frames). A P/B (inter-predicted) frame moved to a new stream position
     * generally will NOT decode correctly even if it isn't itself referenced by anything else,
     * because ITS OWN motion-compensated prediction needs specific reference pictures that differ
     * at the new position — verified empirically via ffmpeg logging "Missing reference picture"
     * when such a frame was duplicated. NAL types 1-4/6-9 (H.264) or 0-18 minus IRAP (H.265) may
     * be intra in practice (a non-IDR I-slice) but that requires parsing slice_type out of the
     * slice header, which this fast structural checker deliberately doesn't do — so they are
     * conservatively treated as "not guaranteed safe" here. Malformed input is also conservative
     * (returns false).
     */
    public static boolean isIntraOnly(byte[] s, CodecKind codec, int nalLengthSize) {
        try {
            int pos = 0;
            boolean sawVcl = false;
            while (pos < s.length) {
                if (pos + nalLengthSize > s.length) return false;
                long len = readLength(s, pos, nalLengthSize);
                pos += nalLengthSize;
                if (len <= 0 || pos + len > s.length) return false;

                int b0 = s[pos] & 0xFF;
                if (codec == CodecKind.H264) {
                    int nalType = b0 & 0x1F;
                    if (nalType >= 1 && nalType <= 5) {
                        sawVcl = true;
                        if (nalType != 5) return false;
                    }
                } else {
                    int nalType = (b0 >> 1) & 0x3F;
                    if (nalType <= 31) {
                        sawVcl = true;
                        if (nalType < 19 || nalType > 21) return false;
                    }
                }
                pos += (int) len;
            }
            return sawVcl;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long readLength(byte[] s, int pos, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) {
            v = (v << 8) | (s[pos + i] & 0xFF);
        }
        return v;
    }
}
