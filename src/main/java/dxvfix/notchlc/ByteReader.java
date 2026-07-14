package dxvfix.notchlc;

/**
 * Clamped little-endian byte reader, same idea as {@code dxvfix.dxv.ByteReader} -- reads past
 * the end of the buffer return 0 instead of throwing. Not shared across packages: each codec's
 * validator package is self-contained in this codebase (see the dxv/prores/h26x packages).
 */
final class ByteReader {
    private final byte[] data;
    private final int limit;
    private int pos;

    ByteReader(byte[] data, int offset, int length) {
        this.data = data;
        this.limit = offset + length;
        this.pos = offset;
    }

    int getByte() {
        if (pos < limit) {
            return data[pos++] & 0xFF;
        }
        return 0;
    }

    long getLE32() {
        long b0 = getByte();
        long b1 = getByte();
        long b2 = getByte();
        long b3 = getByte();
        return (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)) & 0xFFFFFFFFL;
    }
}
