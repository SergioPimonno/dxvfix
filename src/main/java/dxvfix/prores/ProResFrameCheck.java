package dxvfix.prores;

import dxvfix.scan.FrameCheckResult;

/**
 * Structural validator for Apple ProRes frames (422/HQ/LT/Proxy/4444/4444 XQ), ported from the
 * bounds-checking parts of FFmpeg's {@code libavcodec/proresdec.c} (decode_frame /
 * decode_frame_header / decode_picture_header). It stops short of entropy/DCT decode: it
 * validates the frame header, picture header, and slice size table are internally consistent
 * (sizes in range, and the slice grid exactly tiles the macroblock grid), which is exactly the
 * class of corruption (truncated/garbled headers, torn slice tables) that would make a real
 * decoder reject the frame.
 */
public final class ProResFrameCheck {

    private ProResFrameCheck() {
    }

    public static FrameCheckResult check(byte[] s, int trackWidth, int trackHeight) {
        try {
            if (s.length < 28) {
                return bad("frame is only " + s.length + " bytes, minimum ProRes frame is 28");
            }
            long frameSize = u32be(s, 0);
            if (frameSize != s.length) {
                return bad("frame_size field " + frameSize + " != sample size " + s.length);
            }
            if (!(s[4] == 'i' && s[5] == 'c' && s[6] == 'p' && s[7] == 'f')) {
                return bad("missing 'icpf' frame identifier");
            }

            int pos = 8;
            int remaining = s.length - 8;

            int hdrSize = u16be(s, pos);
            if (hdrSize > remaining || hdrSize < 20) {
                return bad("frame header size " + hdrSize + " out of range (remaining " + remaining + ")");
            }
            int version = u16be(s, pos + 2);
            if (version > 1) {
                return bad("unsupported frame header version " + version);
            }
            int width = u16be(s, pos + 8);
            int height = u16be(s, pos + 10);
            if (width <= 0 || height <= 0) {
                return bad("invalid dimensions in frame header: " + width + "x" + height);
            }
            // Note: a real decoder tolerates width/height differing from the track's declared
            // dimensions (mid-stream resolution change support), so this is not treated as an error.
            int frameType = (u8(s, pos + 12) >> 2) & 3;
            int alphaInfo = u8(s, pos + 17) & 0xF;
            if (alphaInfo > 2) {
                return bad("invalid alpha_info " + alphaInfo);
            }

            pos += hdrSize;
            remaining -= hdrSize;

            boolean interlaced = frameType != 0;
            int mbWidth = (width + 15) >> 4;
            int mbHeight = interlaced ? (height + 31) >> 5 : (height + 15) >> 4;

            boolean firstField = true;
            int fieldsToDecode = interlaced ? 2 : 1;
            for (int field = 0; field < fieldsToDecode; field++) {
                if (remaining <= 0) {
                    if (field == 0) {
                        return bad("no picture data after frame header");
                    }
                    break; // second field legitimately absent
                }
                PicResult pr = checkPicture(s, pos, remaining, mbWidth, mbHeight);
                if (pr.error != null) {
                    return bad(pr.error);
                }
                pos += pr.picSize;
                remaining -= pr.picSize;
            }

            return new FrameCheckResult(FrameCheckResult.Status.OK, null,
                    interlaced ? "ProRes (interlaced)" : "ProRes");
        } catch (ArrayIndexOutOfBoundsException e) {
            return bad("truncated/malformed header (index out of range while parsing)");
        }
    }

    private static final class PicResult {
        int picSize;
        String error;
    }

    private static PicResult checkPicture(byte[] s, int pos, int remaining, int mbWidth, int mbHeight) {
        PicResult r = new PicResult();

        int hdrSize = u8(s, pos) >> 3;
        if (hdrSize < 8 || hdrSize > remaining) {
            r.error = "picture header size " + hdrSize + " out of range (remaining " + remaining + ")";
            return r;
        }
        long picDataSize = u32be3(s, pos + 1); // AV_RB32(buf+1) but we only trust it fits in an int for these file sizes
        if (picDataSize > remaining) {
            r.error = "picture data size " + picDataSize + " exceeds remaining " + remaining;
            return r;
        }

        int log2SliceMbWidth = u8(s, pos + 7) >> 4;
        int log2SliceMbHeight = u8(s, pos + 7) & 0xF;
        if (log2SliceMbWidth > 3 || log2SliceMbHeight != 0) {
            r.error = "unsupported slice resolution 2^" + log2SliceMbWidth + "x2^" + log2SliceMbHeight;
            return r;
        }
        int sliceMbWidthInit = 1 << log2SliceMbWidth;

        int slicesPerRow = (mbWidth >> log2SliceMbWidth) + Integer.bitCount(mbWidth & (sliceMbWidthInit - 1));
        int sliceCount = mbHeight * slicesPerRow;
        if (sliceCount <= 0) {
            r.error = "computed slice count is " + sliceCount;
            return r;
        }
        if (hdrSize + sliceCount * 2 > remaining) {
            r.error = "slice size table (count=" + sliceCount + ") does not fit in remaining " + remaining;
            return r;
        }

        int indexPos = pos + hdrSize;
        int dataPos = indexPos + sliceCount * 2;
        int sampleEnd = pos + remaining;

        int sliceMbCount = sliceMbWidthInit;
        int mbX = 0, mbY = 0;
        for (int i = 0; i < sliceCount; i++) {
            int sliceSize = u16be(s, indexPos + i * 2);
            int sliceStart = dataPos;
            dataPos += sliceSize;

            while (mbWidth - mbX < sliceMbCount) {
                sliceMbCount >>= 1;
                if (sliceMbCount == 0) {
                    r.error = "slice grid could not tile macroblock width at mb_x=" + mbX;
                    return r;
                }
            }

            int thisSliceDataSize = dataPos - sliceStart;
            if (thisSliceDataSize < 6) {
                r.error = "slice " + i + " data size " + thisSliceDataSize + " below minimum (6)";
                return r;
            }

            mbX += sliceMbCount;
            if (mbX == mbWidth) {
                sliceMbCount = sliceMbWidthInit;
                mbX = 0;
                mbY++;
            }
            if (dataPos > sampleEnd) {
                r.error = "slice " + i + " data runs past end of picture data";
                return r;
            }
        }

        if (mbX != 0 || mbY != mbHeight) {
            r.error = "slice grid does not cover the full frame (mb_y=" + mbY + " expected=" + mbHeight + ")";
            return r;
        }

        r.picSize = (int) picDataSize;
        return r;
    }

    private static FrameCheckResult bad(String detail) {
        return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID, detail, "ProRes");
    }

    private static int u8(byte[] a, int i) {
        return a[i] & 0xFF;
    }

    private static int u16be(byte[] a, int i) {
        return ((a[i] & 0xFF) << 8) | (a[i + 1] & 0xFF);
    }

    private static long u32be(byte[] a, int i) {
        return ((long) (a[i] & 0xFF) << 24) | ((a[i + 1] & 0xFF) << 16) | ((a[i + 2] & 0xFF) << 8) | (a[i + 3] & 0xFF);
    }

    /** AV_RB32(buf+1) as used by decode_picture_header: same as u32be, kept as a distinct name for clarity at call site. */
    private static long u32be3(byte[] a, int i) {
        return u32be(a, i);
    }
}
