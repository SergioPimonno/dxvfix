package dxvfix.mp4;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ISO base media (QuickTime / MP4) box-tree reader. Parses only what is needed to
 * locate video samples inside a single {@code mdat} and to later patch {@code stsz}/{@code
 * stco}/{@code co64} tables in place: it does not build a full generic box tree, only walks
 * the boxes relevant to sample tables (moov/trak/mdia/minf/stbl/*).
 */
public final class Mp4Container {

    public final File sourceFile;
    public final List<TrackInfo> tracks = new ArrayList<>();

    public long mdatBoxAbsOffset = -1;
    public int mdatHeaderSize = 8;
    public long mdatContentAbsOffset = -1;
    public long mdatContentSize = -1;

    private Mp4Container(File f) {
        this.sourceFile = f;
    }

    public static Mp4Container parse(File file) throws IOException {
        Mp4Container c = new Mp4Container(file);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLen = raf.length();
            long pos = 0;
            boolean seenMdat = false;
            while (pos < fileLen) {
                BoxHeader top = readBoxHeader(raf, pos, fileLen);
                if (top == null) break;
                switch (top.type) {
                    case "moov":
                        c.parseMoov(raf, top);
                        break;
                    case "mdat":
                        if (seenMdat) {
                            throw new IOException("File has more than one 'mdat' box; not supported");
                        }
                        seenMdat = true;
                        c.mdatBoxAbsOffset = top.boxAbsOffset;
                        c.mdatHeaderSize = top.headerSize;
                        c.mdatContentAbsOffset = top.contentAbsOffset;
                        c.mdatContentSize = top.contentSize;
                        break;
                    case "moof":
                        throw new IOException("Fragmented MP4 (moof box found) is not supported");
                    default:
                        break; // ftyp, free, skip, wide, uuid, etc: ignore
                }
                pos = top.endAbsOffset();
            }
        }
        if (c.mdatBoxAbsOffset < 0) {
            throw new IOException("No 'mdat' box found");
        }
        for (TrackInfo t : c.tracks) {
            t.buildSamples();
        }
        return c;
    }

    public TrackInfo findPrimaryVideoTrack() {
        TrackInfo fallback = null;
        for (TrackInfo t : tracks) {
            if (!t.isVideo()) continue;
            if (t.fourcc != null && t.fourcc.startsWith("DXD")) {
                return t;
            }
            if (fallback == null) fallback = t;
        }
        return fallback;
    }

    // ---- box tree walking ------------------------------------------------------------------

    private void parseMoov(RandomAccessFile raf, BoxHeader moov) throws IOException {
        long pos = moov.contentAbsOffset;
        long end = moov.endAbsOffset();
        while (pos < end) {
            BoxHeader b = readBoxHeader(raf, pos, end);
            if (b == null) break;
            if ("trak".equals(b.type)) {
                TrackInfo t = new TrackInfo();
                parseTrak(raf, b, t);
                tracks.add(t);
            }
            pos = b.endAbsOffset();
        }
    }

    private void parseTrak(RandomAccessFile raf, BoxHeader trak, TrackInfo t) throws IOException {
        long pos = trak.contentAbsOffset;
        long end = trak.endAbsOffset();
        while (pos < end) {
            BoxHeader b = readBoxHeader(raf, pos, end);
            if (b == null) break;
            switch (b.type) {
                case "tkhd":
                    parseTkhd(raf, b, t);
                    break;
                case "mdia":
                    parseMdia(raf, b, t);
                    break;
                case "edts":
                    parseEdts(raf, b, t);
                    break;
                default:
                    break;
            }
            pos = b.endAbsOffset();
        }
    }

    /**
     * Reads the first edit-list entry's media_time, which is how MOV/MP4 commonly shifts the
     * whole track's presentation timeline (e.g. to compensate for B-frame reorder delay so
     * playback starts at movie time 0). We only support the common single-entry case; composition
     * timestamps are computed as dts + ctts_offset - this value.
     */
    private void parseEdts(RandomAccessFile raf, BoxHeader edts, TrackInfo t) throws IOException {
        long pos = edts.contentAbsOffset;
        long end = edts.endAbsOffset();
        while (pos < end) {
            BoxHeader b = readBoxHeader(raf, pos, end);
            if (b == null) break;
            if ("elst".equals(b.type)) {
                raf.seek(b.contentAbsOffset);
                int version = readUint8(raf);
                raf.skipBytes(3);
                long count = readUint32BE(raf);
                if (count > 0) {
                    long mediaTime;
                    if (version == 1) {
                        raf.skipBytes(8); // segment_duration (u64)
                        mediaTime = readUint64BE(raf); // signed 64-bit, read raw bits
                    } else {
                        raf.skipBytes(4); // segment_duration (u32)
                        mediaTime = (int) readUint32BE(raf); // sign-extend 32-bit
                    }
                    if (mediaTime >= 0) {
                        t.editListMediaTime = mediaTime;
                    }
                }
            }
            pos = b.endAbsOffset();
        }
    }

    private void parseTkhd(RandomAccessFile raf, BoxHeader b, TrackInfo t) throws IOException {
        raf.seek(b.contentAbsOffset);
        int version = readUint8(raf);
        raf.skipBytes(3); // flags
        if (version == 1) {
            raf.skipBytes(8 + 8); // creation, modification
            t.trackId = (int) readUint32BE(raf);
        } else {
            raf.skipBytes(4 + 4);
            t.trackId = (int) readUint32BE(raf);
        }
    }

    private void parseMdia(RandomAccessFile raf, BoxHeader mdia, TrackInfo t) throws IOException {
        long pos = mdia.contentAbsOffset;
        long end = mdia.endAbsOffset();
        while (pos < end) {
            BoxHeader b = readBoxHeader(raf, pos, end);
            if (b == null) break;
            switch (b.type) {
                case "mdhd":
                    parseMdhd(raf, b, t);
                    break;
                case "hdlr":
                    parseHdlr(raf, b, t);
                    break;
                case "minf":
                    parseMinf(raf, b, t);
                    break;
                default:
                    break;
            }
            pos = b.endAbsOffset();
        }
    }

    private void parseMdhd(RandomAccessFile raf, BoxHeader b, TrackInfo t) throws IOException {
        raf.seek(b.contentAbsOffset);
        int version = readUint8(raf);
        raf.skipBytes(3);
        if (version == 1) {
            raf.skipBytes(8 + 8);
            t.timescale = (int) readUint32BE(raf);
        } else {
            raf.skipBytes(4 + 4);
            t.timescale = (int) readUint32BE(raf);
        }
        if (t.timescale <= 0) t.timescale = 1;
    }

    private void parseHdlr(RandomAccessFile raf, BoxHeader b, TrackInfo t) throws IOException {
        raf.seek(b.contentAbsOffset);
        raf.skipBytes(4); // version+flags
        raf.skipBytes(4); // pre_defined
        t.handlerType = readFourCC(raf);
    }

    private void parseMinf(RandomAccessFile raf, BoxHeader minf, TrackInfo t) throws IOException {
        long pos = minf.contentAbsOffset;
        long end = minf.endAbsOffset();
        while (pos < end) {
            BoxHeader b = readBoxHeader(raf, pos, end);
            if (b == null) break;
            if ("stbl".equals(b.type)) {
                parseStbl(raf, b, t);
            }
            pos = b.endAbsOffset();
        }
    }

    private void parseStbl(RandomAccessFile raf, BoxHeader stbl, TrackInfo t) throws IOException {
        long pos = stbl.contentAbsOffset;
        long end = stbl.endAbsOffset();
        while (pos < end) {
            BoxHeader b = readBoxHeader(raf, pos, end);
            if (b == null) break;
            switch (b.type) {
                case "stsd":
                    parseStsd(raf, b, t);
                    break;
                case "stts":
                    parseStts(raf, b, t);
                    break;
                case "ctts":
                    parseCtts(raf, b, t);
                    break;
                case "stsc":
                    parseStsc(raf, b, t);
                    break;
                case "stsz":
                    parseStsz(raf, b, t);
                    break;
                case "stz2":
                    throw new IOException("Compact sample-size table (stz2) is not supported");
                case "stco":
                    parseStco(raf, b, t, 4);
                    break;
                case "co64":
                    parseStco(raf, b, t, 8);
                    break;
                default:
                    break;
            }
            pos = b.endAbsOffset();
        }
    }

    private void parseStsd(RandomAccessFile raf, BoxHeader b, TrackInfo t) throws IOException {
        raf.seek(b.contentAbsOffset);
        raf.skipBytes(4); // version+flags
        long entryCount = readUint32BE(raf);
        if (entryCount < 1) return;
        long entryAbsStart = raf.getFilePointer();
        long entrySize = readUint32BE(raf);
        t.fourcc = readFourCC(raf);
        t.codecKind = CodecKind.fromFourCC(t.fourcc);
        if (t.isVideo() || t.handlerType.isEmpty()) {
            // VisualSampleEntry: width/height sit 32 bytes after the entry start.
            long widthOff = entryAbsStart + 32;
            if (widthOff + 4 <= b.endAbsOffset()) {
                raf.seek(widthOff);
                t.width = readUint16BE(raf);
                t.height = readUint16BE(raf);
            }
            long entryEnd = entryAbsStart + entrySize;
            if (entryEnd <= b.endAbsOffset()) {
                parseVisualSampleEntryExtensions(raf, entryAbsStart + 86, entryEnd, t);
            }
        }
    }

    /** Walks child boxes of a VisualSampleEntry (avcC/hvcC) to recover the NAL length-prefix size. */
    private void parseVisualSampleEntryExtensions(RandomAccessFile raf, long start, long end, TrackInfo t) throws IOException {
        long pos = start;
        while (pos < end) {
            BoxHeader child = readBoxHeader(raf, pos, end);
            if (child == null) break;
            if ("avcC".equals(child.type) && child.contentSize >= 5) {
                raf.seek(child.contentAbsOffset + 4);
                t.nalLengthSize = (readUint8(raf) & 0x03) + 1;
            } else if ("hvcC".equals(child.type) && child.contentSize >= 22) {
                raf.seek(child.contentAbsOffset + 21);
                t.nalLengthSize = (readUint8(raf) & 0x03) + 1;
            }
            pos = child.endAbsOffset();
        }
    }

    private void parseStts(RandomAccessFile raf, BoxHeader b, TrackInfo t) throws IOException {
        raf.seek(b.contentAbsOffset);
        raf.skipBytes(4);
        long count = readUint32BE(raf);
        for (long i = 0; i < count; i++) {
            int sampleCount = (int) readUint32BE(raf);
            int sampleDelta = (int) readUint32BE(raf);
            t.sttsEntries.add(new int[]{sampleCount, sampleDelta});
        }
    }

    private void parseCtts(RandomAccessFile raf, BoxHeader b, TrackInfo t) throws IOException {
        raf.seek(b.contentAbsOffset);
        int version = readUint8(raf);
        raf.skipBytes(3);
        long count = readUint32BE(raf);
        for (long i = 0; i < count; i++) {
            long sampleCount = readUint32BE(raf);
            long raw = readUint32BE(raf);
            long offset = version == 0 ? raw : (int) raw; // version 1: signed 32-bit
            t.cttsEntries.add(new long[]{sampleCount, offset});
        }
    }

    private void parseStsc(RandomAccessFile raf, BoxHeader b, TrackInfo t) throws IOException {
        raf.seek(b.contentAbsOffset);
        raf.skipBytes(4);
        long count = readUint32BE(raf);
        for (long i = 0; i < count; i++) {
            int firstChunk = (int) readUint32BE(raf);
            int samplesPerChunk = (int) readUint32BE(raf);
            int sampleDescIndex = (int) readUint32BE(raf);
            t.stscEntries.add(new int[]{firstChunk, samplesPerChunk, sampleDescIndex});
        }
    }

    private void parseStsz(RandomAccessFile raf, BoxHeader b, TrackInfo t) throws IOException {
        raf.seek(b.contentAbsOffset);
        raf.skipBytes(4);
        int sampleSize = (int) readUint32BE(raf);
        long count = readUint32BE(raf);
        if (sampleSize != 0) {
            t.stszConstantSize = sampleSize;
            t.stszTableAbsOffset = -1;
            t.rawSampleSizes = new int[(int) count];
            for (int i = 0; i < count; i++) t.rawSampleSizes[i] = sampleSize;
        } else {
            t.stszTableAbsOffset = raf.getFilePointer();
            t.rawSampleSizes = new int[(int) count];
            for (int i = 0; i < count; i++) {
                t.rawSampleSizes[i] = (int) readUint32BE(raf);
            }
        }
    }

    private void parseStco(RandomAccessFile raf, BoxHeader b, TrackInfo t, int entrySize) throws IOException {
        raf.seek(b.contentAbsOffset);
        raf.skipBytes(4);
        long count = readUint32BE(raf);
        t.stcoTableAbsOffset = raf.getFilePointer();
        t.stcoEntrySize = entrySize;
        t.rawChunkOffsets = new long[(int) count];
        for (int i = 0; i < count; i++) {
            t.rawChunkOffsets[i] = entrySize == 8 ? readUint64BE(raf) : readUint32BE(raf);
        }
    }

    // ---- low level readers -----------------------------------------------------------------

    private static BoxHeader readBoxHeader(RandomAccessFile raf, long pos, long limit) throws IOException {
        if (pos + 8 > limit) return null;
        raf.seek(pos);
        long size32 = readUint32BE(raf);
        String type = readFourCC(raf);
        int headerSize = 8;
        long contentSize;
        if (size32 == 1) {
            long largeSize = readUint64BE(raf);
            headerSize = 16;
            contentSize = largeSize - headerSize;
        } else if (size32 == 0) {
            contentSize = limit - pos - headerSize;
        } else {
            contentSize = size32 - headerSize;
        }
        if (contentSize < 0 || pos + headerSize + contentSize > limit) {
            return null;
        }
        return new BoxHeader(type, pos, headerSize, contentSize);
    }

    private static int readUint8(RandomAccessFile raf) throws IOException {
        return raf.read() & 0xFF;
    }

    private static int readUint16BE(RandomAccessFile raf) throws IOException {
        int b0 = raf.read() & 0xFF;
        int b1 = raf.read() & 0xFF;
        return (b0 << 8) | b1;
    }

    private static long readUint32BE(RandomAccessFile raf) throws IOException {
        long b0 = raf.read() & 0xFF;
        long b1 = raf.read() & 0xFF;
        long b2 = raf.read() & 0xFF;
        long b3 = raf.read() & 0xFF;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static long readUint64BE(RandomAccessFile raf) throws IOException {
        long hi = readUint32BE(raf);
        long lo = readUint32BE(raf);
        return (hi << 32) | lo;
    }

    private static String readFourCC(RandomAccessFile raf) throws IOException {
        byte[] buf = new byte[4];
        raf.readFully(buf);
        return new String(buf, StandardCharsets.US_ASCII);
    }
}
