package dxvfix.notchlc;

import dxvfix.scan.FrameCheckResult;

/**
 * Fast structural validator for NotchLC, based on the frame layout used by FFmpeg's
 * {@code libavcodec/notchlc.c} decoder: every sample opens with a 16-byte header --
 * {@code 'NLC1'} magic tag (4 bytes), {@code uncompressed_size} (u32 LE), {@code compressed_size}
 * (u32 LE), and a {@code format} selector (u32 LE: 0 = LZF, 1 = LZ4, 2 = uncompressed).
 * <p>
 * Deliberately shallow beyond that header, for the same reason {@code h26x.NalFrameCheck} stops
 * at NAL framing for H.264/H.265 rather than replaying entropy coding: for {@code format} 0 or 1
 * the payload past the header is itself LZF- or LZ4-compressed, and validating the per-block
 * offset table that follows would mean re-implementing one of those compressors in Java just to
 * reach it -- disproportionate effort for a corruption check when Deep mode (which runs the real
 * ffmpeg decoder) already covers that ground generically for any codec ffmpeg can decode. What
 * header validation alone already catches is the corruption pattern this app deals with most --
 * a truncated or partially-overwritten sample -- since that reliably clobbers the first bytes of
 * the frame.
 * <p>
 * For {@code format == 2} (uncompressed), the payload is plain bytes with a directly checkable
 * declared length, so that case gets one extra size-consistency check for free.
 */
public final class NotchLcFrameCheck {

    private static final int HEADER_SIZE = 16;
    // FFmpeg itself rejects packets at or below this size before even reading the header.
    private static final int MIN_SAMPLE_SIZE = 40;

    /**
     * FFmpeg defines this as MKBETAG('N','L','C','1') and reads it with a little-endian 32-bit
     * read -- same convention as dxv.DxvFrameCheck's tags, so the bytes appear in the file in
     * *reverse* character order ("NLC1" is stored as bytes '1','C','L','N').
     */
    private static final long MAGIC_NLC1 = tagOf('N', 'L', 'C', '1');

    private static long tagOf(char a, char b, char c, char d) {
        return (d) | ((long) c << 8) | ((long) b << 16) | ((long) a << 24);
    }

    private NotchLcFrameCheck() {
    }

    public static FrameCheckResult check(byte[] sample, int width, int height) {
        if (sample.length <= MIN_SAMPLE_SIZE) {
            return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                    "frame is only " + sample.length + " bytes, too small for a NotchLC header", "NotchLC");
        }

        ByteReader r = new ByteReader(sample, 0, sample.length);
        long tag = r.getLE32();
        if (tag != MAGIC_NLC1) {
            return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                    String.format("bad NotchLC magic tag (0x%08X)", tag), "NotchLC");
        }

        long uncompressedSize = r.getLE32();
        long compressedSize = r.getLE32();
        long format = r.getLE32();

        if (format > 2) {
            return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                    "unknown NotchLC compression format " + format, "NotchLC");
        }
        if (uncompressedSize == 0) {
            return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                    "NotchLC uncompressed_size is zero", "NotchLC");
        }

        long payloadBytesAvailable = sample.length - HEADER_SIZE;
        String formatLabel = format == 0 ? "NotchLC (LZF)" : format == 1 ? "NotchLC (LZ4)" : "NotchLC (raw)";

        if (compressedSize > payloadBytesAvailable) {
            return new FrameCheckResult(FrameCheckResult.Status.BITSTREAM_INVALID,
                    "NotchLC declares " + compressedSize + " compressed bytes but only "
                            + payloadBytesAvailable + " remain in the sample", formatLabel);
        }

        if (format == 2 && uncompressedSize > payloadBytesAvailable) {
            return new FrameCheckResult(FrameCheckResult.Status.BITSTREAM_INVALID,
                    "NotchLC declares " + uncompressedSize + " uncompressed bytes but only "
                            + payloadBytesAvailable + " remain in the sample", formatLabel);
        }

        return new FrameCheckResult(FrameCheckResult.Status.OK_SHALLOW, null, formatLabel);
    }
}
