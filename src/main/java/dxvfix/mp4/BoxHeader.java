package dxvfix.mp4;

final class BoxHeader {
    final String type;
    final long boxAbsOffset;   // absolute offset of the box (size field start)
    final int headerSize;      // 8 or 16
    final long contentAbsOffset;
    final long contentSize;

    BoxHeader(String type, long boxAbsOffset, int headerSize, long contentSize) {
        this.type = type;
        this.boxAbsOffset = boxAbsOffset;
        this.headerSize = headerSize;
        this.contentAbsOffset = boxAbsOffset + headerSize;
        this.contentSize = contentSize;
    }

    long endAbsOffset() {
        return contentAbsOffset + contentSize;
    }
}
