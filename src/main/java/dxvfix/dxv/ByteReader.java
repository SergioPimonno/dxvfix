package dxvfix.dxv;

/**
 * Clamped little-endian byte reader mirroring FFmpeg's bytestream2 "safe" accessors:
 * reads past the end of the buffer return 0 instead of throwing, matching the reference
 * decoder's behavior so bounds-check logic ported from it stays faithful.
 */
final class ByteReader {
    private final byte[] data;
    private final int start;
    private final int limit;
    private int pos;

    ByteReader(byte[] data, int offset, int length) {
        this.data = data;
        this.start = offset;
        this.limit = offset + length;
        this.pos = offset;
    }

    int bytesLeft() {
        return Math.max(0, limit - pos);
    }

    int tell() {
        return pos - start;
    }

    int getByte() {
        if (pos < limit) {
            return data[pos++] & 0xFF;
        }
        return 0;
    }

    int getLE16() {
        int b0 = getByte();
        int b1 = getByte();
        return b0 | (b1 << 8);
    }

    long getLE32() {
        long b0 = getByte();
        long b1 = getByte();
        long b2 = getByte();
        long b3 = getByte();
        return (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)) & 0xFFFFFFFFL;
    }

    void skip(int n) {
        pos = Math.min(limit, pos + n);
    }
}
