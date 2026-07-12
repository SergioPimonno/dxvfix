package dxvfix.dxv;

import dxvfix.scan.FrameCheckResult;

/**
 * Validates a single Resolume DXV compressed video sample without decoding it to pixels.
 * <p>
 * This is a line-for-line port of the structural / bounds-checking logic in FFmpeg's
 * {@code libavcodec/dxv.c} (dxv_decode, dxv_decompress_dxt1, dxv_decompress_dxt5, and the
 * CHECKPOINT macro). The pixel-producing parts (writes into the decoded texture buffer) are
 * intentionally omitted: none of the control-flow, opcode selection, or bounds checks in the
 * reference decoder depend on previously decoded pixel *values*, only on the running write
 * position ({@code pos}), so replaying just the bitstream consumption and position bookkeeping
 * is sufficient to reproduce every AVERROR_INVALIDDATA condition the real decoder would hit.
 * <p>
 * YCoCg variants (YCG6 / YG10) and legacy LZF-compressed frames use a different, Huffman-coded
 * opcode scheme that is not ported here; those are only header/size validated ({@link
 * FrameCheckResult.Status#OK_SHALLOW}).
 */
public final class DxvFrameCheck {

    private static final int TEXTURE_BLOCK_H = 4;

    // 4-byte tags as they appear in the file (little-endian uint32 read == these ASCII bytes).
    private static final long TAG_DXT1 = tagOf('D', 'X', 'T', '1');
    private static final long TAG_DXT5 = tagOf('D', 'X', 'T', '5');
    private static final long TAG_YCG6 = tagOf('Y', 'C', 'G', '6');
    private static final long TAG_YG10 = tagOf('Y', 'G', '1', '0');

    /**
     * FFmpeg defines these as MKBETAG('D','X','T','1') etc (see libavcodec/dxv.h), i.e. the
     * first character ends up as the most significant byte. Since the tag is read from the
     * stream with a little-endian 32-bit read, that means the bytes appear in the file in
     * *reverse* character order (e.g. "DXT1" is stored as bytes '1','T','X','D').
     */
    private static long tagOf(char a, char b, char c, char d) {
        return (d) | ((long) c << 8) | ((long) b << 16) | ((long) a << 24);
    }

    private DxvFrameCheck() {
    }

    public static FrameCheckResult check(byte[] sample, int width, int height) {
        if (sample.length < 4) {
            return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                    "frame is only " + sample.length + " bytes, cannot even hold a tag", null);
        }

        ByteReader r = new ByteReader(sample, 0, sample.length);
        long tag = r.getLE32();

        String texFormat;
        boolean isDxt1, isDxt5, isYCoCg;
        boolean raw = false;
        boolean deepValidate = true;
        int size;
        int oldType = 0;

        if (tag == TAG_DXT1 || tag == TAG_DXT5 || tag == TAG_YCG6 || tag == TAG_YG10) {
            texFormat = tagName(tag);
            isDxt1 = tag == TAG_DXT1;
            isDxt5 = tag == TAG_DXT5;
            isYCoCg = tag == TAG_YCG6 || tag == TAG_YG10;
        } else {
            // Legacy header: no separate 12-byte header, the tag itself IS size+type.
            size = (int) (tag & 0x00FFFFFFL);
            oldType = (int) ((tag >> 24) & 0xFF);
            int versionMajor = (oldType & 0x0F) - 1;
            raw = (oldType & 0x80) != 0;
            deepValidate = false; // LZF / raw legacy path not deep-validated
            boolean legacyIsDxt1;

            if ((oldType & 0x40) != 0) {
                texFormat = "DXT5 (legacy header)";
                legacyIsDxt1 = false;
            } else if ((oldType & 0x20) != 0 || versionMajor == 1) {
                texFormat = "DXT1 (legacy header)";
                legacyIsDxt1 = true;
            } else {
                return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                        String.format("unrecognized legacy header (tag=0x%08X, type=0x%02X)", tag, oldType), null);
            }

            if (height / 2 / TEXTURE_BLOCK_H < 1) {
                return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                        "coded height too small (" + height + ")", texFormat);
            }

            FrameCheckResult sizeResult = checkSizeAndMaybeShallow(r, size, texFormat, raw);
            if (sizeResult.status != FrameCheckResult.Status.OK_SHALLOW || !raw) {
                return sizeResult;
            }
            int codedWidth = align(width, 16);
            int codedHeight = align(height, TEXTURE_BLOCK_H);
            int texSize = legacyIsDxt1 ? (codedWidth / 4) * (codedHeight / 4) * 8
                                        : (codedWidth / 4) * (codedHeight / 4) * 16;
            if (r.bytesLeft() < texSize) {
                return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                        "legacy raw texture truncated: need " + texSize + " bytes, have " + r.bytesLeft(), texFormat);
            }
            return sizeResult;
        }

        if (height / 2 / TEXTURE_BLOCK_H < 1) {
            return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                    "coded height too small (" + height + ")", texFormat);
        }

        // Modern 12-byte header: tag(4) already consumed, then 8 more bytes.
        r.getByte(); // version_major (unused for validation)
        r.getByte(); // version_minor (unused for validation)
        int compression = r.getByte(); // non-zero => encoder stored raw texture data
        r.skip(1);   // unknown
        size = (int) r.getLE32();
        raw = compression != 0;

        FrameCheckResult sizeResult = checkSizeAndMaybeShallow(r, size, texFormat, false);
        if (sizeResult != null) {
            return sizeResult;
        }

        int codedWidth = align(width, 16);
        int codedHeight = align(height, TEXTURE_BLOCK_H);
        int texSize;
        if (isDxt1) {
            texSize = (codedWidth / 4) * (codedHeight / 4) * 8;
        } else if (isDxt5) {
            texSize = (codedWidth / 4) * (codedHeight / 4) * 16;
        } else {
            texSize = 0; // YCoCg: not deep validated
        }

        if (raw) {
            if (texSize > 0 && r.bytesLeft() < texSize) {
                return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                        "raw texture truncated: need " + texSize + " bytes, have " + r.bytesLeft(), texFormat);
            }
            return new FrameCheckResult(FrameCheckResult.Status.OK, null, texFormat + " (raw)");
        }

        if (isYCoCg) {
            return new FrameCheckResult(FrameCheckResult.Status.OK_SHALLOW,
                    "YCoCg opcode stream not deep-validated", texFormat);
        }

        try {
            if (isDxt1) {
                simulateDxt1(r, texSize);
            } else {
                simulateDxt5(r, texSize);
            }
        } catch (DxvCorruptException e) {
            return new FrameCheckResult(FrameCheckResult.Status.BITSTREAM_INVALID, e.getMessage(), texFormat);
        }

        return new FrameCheckResult(FrameCheckResult.Status.OK, null, texFormat);
    }

    private static FrameCheckResult checkSizeAndMaybeShallow(ByteReader r, int size, String texFormat, boolean legacyRaw) {
        int left = r.bytesLeft();
        if (size != left) {
            return new FrameCheckResult(FrameCheckResult.Status.HEADER_INVALID,
                    "declared payload size " + size + " != remaining sample bytes " + left, texFormat);
        }
        if (!legacyRaw && texFormat.contains("legacy")) {
            // legacy LZF path: only size-checked
            return new FrameCheckResult(FrameCheckResult.Status.OK_SHALLOW,
                    "legacy LZF payload not deep-validated", texFormat);
        }
        if (legacyRaw) {
            return new FrameCheckResult(FrameCheckResult.Status.OK_SHALLOW,
                    "legacy raw payload, size-checked only", texFormat);
        }
        return null; // signal caller to continue with modern-header deep validation
    }

    private static String tagName(long tag) {
        if (tag == TAG_DXT1) return "DXT1";
        if (tag == TAG_DXT5) return "DXT5";
        if (tag == TAG_YCG6) return "YCG6";
        if (tag == TAG_YG10) return "YG10";
        return "?";
    }

    private static int align(int v, int a) {
        return (v + a - 1) / a * a;
    }

    // ---- CHECKPOINT state machine ----------------------------------------------------------

    private static final class CpState {
        long value = 0;
        int state = 0;
    }

    private static final class Checkpoint {
        int op;
        int idx;
    }

    /**
     * Port of the CHECKPOINT(x) macro. Reads a 2-bit opcode from a 32-bit LE word that is
     * refilled every 16 opcodes, and for op 2/3 resolves a back-reference index, throwing if
     * that index would reach further back than the data decoded so far (pos).
     */
    private static Checkpoint checkpoint(ByteReader r, CpState st, int x, int pos) {
        if (st.state == 0) {
            if (r.bytesLeft() < 4) {
                throw new DxvCorruptException("truncated opcode stream (need 4 bytes to refill checkpoint word)");
            }
            st.value = r.getLE32();
            st.state = 16;
        }
        int op = (int) (st.value & 0x3);
        st.value >>= 2;
        st.state--;

        Checkpoint cp = new Checkpoint();
        cp.op = op;
        switch (op) {
            case 1:
                cp.idx = x;
                break;
            case 2:
                cp.idx = (r.getByte() + 2) * x;
                if (cp.idx > pos) {
                    throw new DxvCorruptException("back-reference idx " + cp.idx + " > pos " + pos + " (op=2)");
                }
                break;
            case 3:
                cp.idx = (r.getLE16() + 0x102) * x;
                if (cp.idx > pos) {
                    throw new DxvCorruptException("back-reference idx " + cp.idx + " > pos " + pos + " (op=3)");
                }
                break;
            default:
                cp.idx = 0;
                break;
        }
        return cp;
    }

    /** Port of dxv_decompress_dxt1: replays position/opcode bookkeeping only, no pixel data. */
    private static void simulateDxt1(ByteReader r, int texSize) {
        CpState st = new CpState();
        int pos = 2;
        r.getLE32(); // first two elements copied verbatim
        r.getLE32();

        while (pos + 2 <= texSize / 4) {
            Checkpoint outer = checkpoint(r, st, 2, pos);
            if (outer.op != 0) {
                pos += 2;
            } else {
                Checkpoint e1 = checkpoint(r, st, 2, pos);
                if (e1.op == 0) {
                    r.getLE32();
                }
                pos++;

                Checkpoint e2 = checkpoint(r, st, 2, pos);
                if (e2.op == 0) {
                    r.getLE32();
                }
                pos++;
            }
        }
    }

    /** Port of dxv_decompress_dxt5: replays position/run/opcode bookkeeping only. */
    private static void simulateDxt5(ByteReader r, int texSize) {
        CpState st = new CpState();
        int pos = 4;
        int run = 0;

        r.getLE32();
        r.getLE32();
        r.getLE32();
        r.getLE32();

        while (pos + 2 <= texSize / 4) {
            if (run > 0) {
                run--;
                pos += 2;
            } else {
                if (r.bytesLeft() < 1) {
                    throw new DxvCorruptException("truncated stream mid-frame");
                }
                if (st.state == 0) {
                    st.value = r.getLE32();
                    st.state = 16;
                }
                int op = (int) (st.value & 0x3);
                st.value >>= 2;
                st.state--;

                if (op == 0) {
                    // Long copy
                    int check = r.getByte() + 1;
                    if (check == 256) {
                        int probe;
                        do {
                            probe = r.getLE16();
                            check += probe;
                        } while (probe == 0xFFFF);
                    }
                    while (check != 0 && pos + 4 <= texSize / 4) {
                        pos += 4;
                        check--;
                    }
                    continue; // restart outer loop, skip the trailing CHECKPOINT(4) block
                } else if (op == 1) {
                    run = r.getByte();
                    if (run == 255) {
                        int probe;
                        do {
                            probe = r.getLE16();
                            run += probe;
                        } while (probe == 0xFFFF);
                    }
                    pos += 2;
                } else if (op == 2) {
                    int idx = 8 + 4 * r.getLE16();
                    if (idx > pos || (pos - idx) + 2 > texSize / 4) {
                        throw new DxvCorruptException("back-reference idx " + idx + " out of range at pos " + pos + " (op=2)");
                    }
                    pos += 2;
                } else {
                    r.getLE32();
                    r.getLE32();
                    pos += 2;
                }
            }

            Checkpoint outer = checkpoint(r, st, 4, pos);
            if (pos + 2 > texSize / 4) {
                throw new DxvCorruptException("position overflow after checkpoint (pos=" + pos + ")");
            }
            if (outer.op != 0) {
                if (outer.idx > pos || (pos - outer.idx) + 2 > texSize / 4) {
                    throw new DxvCorruptException("back-reference idx " + outer.idx + " out of range at pos " + pos);
                }
                pos += 2;
            } else {
                Checkpoint e1 = checkpoint(r, st, 4, pos);
                if (e1.op != 0 && (e1.idx > pos || (pos - e1.idx) + 2 > texSize / 4)) {
                    throw new DxvCorruptException("back-reference idx " + e1.idx + " out of range at pos " + pos);
                }
                if (e1.op == 0) {
                    r.getLE32();
                }
                pos++;

                Checkpoint e2 = checkpoint(r, st, 4, pos);
                if (e2.op == 0) {
                    r.getLE32();
                }
                pos++;
            }
        }
    }
}
