package dxvfix.repair;

import dxvfix.mp4.Mp4Container;
import dxvfix.mp4.SampleInfo;
import dxvfix.mp4.TrackInfo;
import dxvfix.scan.FrameReport;
import dxvfix.scan.ScanResult;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites a MOV/MP4 file, replacing corrupt DXV samples with a verbatim copy of a neighboring
 * good sample's bytes. Every other track and sample is byte-for-byte preserved.
 * <p>
 * Strategy: every box outside {@code mdat} keeps its original entry counts (only stsz/stco/co64
 * numeric values are patched in place), so those boxes never change size, which means the byte
 * offset of {@code mdat} itself is identical in input and output. Only mdat's payload (and
 * therefore its size field) changes. That lets the whole rewrite be expressed as: copy prefix
 * bytes (patched), write a new mdat, copy suffix bytes (patched) — see {@link #repair}.
 */
public final class Mp4Repairer {

    private Mp4Repairer() {
    }

    private static final class OutputSample {
        final TrackInfo track;
        final int sampleIndex;
        final long orderingOffset;   // original file offset -> determines mdat interleave order
        long contentSourceOffset;    // where to read replacement/original bytes from (ignored if inMemoryContent != null)
        int contentSourceLength;
        byte[] inMemoryContent;      // set for FrameGenerator output: bytes not present anywhere in the source file
        long newOffset;              // assigned in the ordering pass

        OutputSample(TrackInfo track, int sampleIndex, long orderingOffset, long contentSourceOffset, int contentSourceLength) {
            this.track = track;
            this.sampleIndex = sampleIndex;
            this.orderingOffset = orderingOffset;
            this.contentSourceOffset = contentSourceOffset;
            this.contentSourceLength = contentSourceLength;
        }
    }

    public static RepairSummary repair(java.io.File sourceFile, ScanResult scan, java.io.File outputFile) throws IOException {
        Mp4Container c = scan.container;
        TrackInfo videoTrack = scan.videoTrack;

        Map<Integer, Integer> replacementBySampleIndex = new HashMap<>();
        Map<Integer, byte[]> generatedBySampleIndex = new HashMap<>();
        int unrepairable = 0;
        for (FrameReport f : scan.frames) {
            if (!f.isBad()) continue;
            if (f.generatedContent != null) {
                generatedBySampleIndex.put(f.index, f.generatedContent);
            } else if (f.replacementSampleIndex >= 0) {
                replacementBySampleIndex.put(f.index, f.replacementSampleIndex);
            } else {
                unrepairable++;
            }
        }

        // Gather every sample of every track, in original file order.
        List<OutputSample> all = new ArrayList<>();
        for (TrackInfo t : c.tracks) {
            for (SampleInfo s : t.samples) {
                long srcOff = s.offset;
                int srcLen = s.size;
                byte[] generated = (t == videoTrack) ? generatedBySampleIndex.get(s.index) : null;
                if (generated != null) {
                    srcLen = generated.length;
                } else if (t == videoTrack && replacementBySampleIndex.containsKey(s.index)) {
                    SampleInfo repl = videoTrack.samples.get(replacementBySampleIndex.get(s.index));
                    srcOff = repl.offset;
                    srcLen = repl.size;
                }
                OutputSample os = new OutputSample(t, s.index, s.offset, srcOff, srcLen);
                os.inMemoryContent = generated;
                all.add(os);
            }
        }
        all.sort((a, b) -> Long.compare(a.orderingOffset, b.orderingOffset));

        long runningOffset = c.mdatContentAbsOffset;
        Map<TrackInfo, long[]> newOffsetsByTrack = new HashMap<>();
        Map<TrackInfo, int[]> newSizesByTrack = new HashMap<>();
        for (TrackInfo t : c.tracks) {
            newOffsetsByTrack.put(t, new long[t.samples.size()]);
            newSizesByTrack.put(t, new int[t.samples.size()]);
        }
        for (OutputSample os : all) {
            os.newOffset = runningOffset;
            newOffsetsByTrack.get(os.track)[os.sampleIndex] = os.newOffset;
            newSizesByTrack.get(os.track)[os.sampleIndex] = os.contentSourceLength;
            runningOffset += os.contentSourceLength;
        }
        long newMdatContentSize = runningOffset - c.mdatContentAbsOffset;

        if (c.mdatHeaderSize == 8 && (8 + newMdatContentSize) > 0xFFFFFFFFL) {
            throw new IOException("Repaired mdat would exceed 4GB but the original file used a 32-bit box size; not supported");
        }

        // Build the list of (absolute file offset, new value bytes) patches for stsz/stco/co64.
        List<long[]> longPatches = new ArrayList<>();   // {absOffset, value, widthBytes}
        for (TrackInfo t : c.tracks) {
            long[] newOffsets = newOffsetsByTrack.get(t);
            int[] newSizes = newSizesByTrack.get(t);

            if (t.stszTableAbsOffset >= 0) {
                for (int i = 0; i < newSizes.length; i++) {
                    longPatches.add(new long[]{t.stszTableAbsOffset + 4L * i, newSizes[i], 4});
                }
            } else {
                for (int size : newSizes) {
                    if (size != t.stszConstantSize) {
                        throw new IOException("Track " + t.trackId + " uses a constant sample-size table and a replacement" +
                                " changed a sample's length; not supported");
                    }
                }
            }

            if (t.stcoTableAbsOffset >= 0 && t.rawChunkOffsets != null) {
                int[] chunkFirstSample = firstSampleIndexPerChunk(t);
                for (int chunk = 0; chunk < chunkFirstSample.length; chunk++) {
                    int sIdx = chunkFirstSample[chunk];
                    if (sIdx < 0) continue;
                    long value = newOffsets[sIdx];
                    if (t.stcoEntrySize == 4 && value > 0xFFFFFFFFL) {
                        throw new IOException("Track " + t.trackId + " chunk offset exceeds 32 bits after repair" +
                                " (file uses 'stco' not 'co64'); not supported");
                    }
                    longPatches.add(new long[]{t.stcoTableAbsOffset + (long) chunk * t.stcoEntrySize, value, t.stcoEntrySize});
                }
            }
        }

        long mdatBoxEnd = c.mdatBoxAbsOffset + c.mdatHeaderSize + c.mdatContentSize;
        long fileLen;
        try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
            fileLen = raf.length();
        }

        try (RandomAccessFile in = new RandomAccessFile(sourceFile, "r");
             RandomAccessFile out = new RandomAccessFile(outputFile, "rw")) {
            out.setLength(0);

            byte[] prefix = new byte[(int) c.mdatBoxAbsOffset];
            in.seek(0);
            in.readFully(prefix);
            applyPatches(prefix, 0, longPatches);
            out.write(prefix);

            writeMdatHeader(out, c.mdatHeaderSize, newMdatContentSize);

            byte[] buf = new byte[1 << 20];
            for (OutputSample os : all) {
                if (os.inMemoryContent != null) {
                    out.write(os.inMemoryContent);
                    continue;
                }
                int remaining = os.contentSourceLength;
                long readPos = os.contentSourceOffset;
                in.seek(readPos);
                while (remaining > 0) {
                    int chunk = Math.min(buf.length, remaining);
                    in.readFully(buf, 0, chunk);
                    out.write(buf, 0, chunk);
                    remaining -= chunk;
                }
            }

            long suffixLen = fileLen - mdatBoxEnd;
            if (suffixLen > 0) {
                byte[] suffix = new byte[(int) suffixLen];
                in.seek(mdatBoxEnd);
                in.readFully(suffix);
                applyPatches(suffix, mdatBoxEnd, longPatches);
                out.write(suffix);
            }
        }

        return new RepairSummary(replacementBySampleIndex.size() + generatedBySampleIndex.size(), unrepairable, newMdatContentSize);
    }

    private static int[] firstSampleIndexPerChunk(TrackInfo t) {
        int chunkCount = t.rawChunkOffsets.length;
        int[] first = new int[chunkCount];
        java.util.Arrays.fill(first, -1);
        for (SampleInfo s : t.samples) {
            if (first[s.chunkIndex] < 0) {
                first[s.chunkIndex] = s.index;
            }
        }
        return first;
    }

    private static void applyPatches(byte[] region, long regionAbsStart, List<long[]> patches) {
        long regionEnd = regionAbsStart + region.length;
        for (long[] p : patches) {
            long absOffset = p[0];
            long value = p[1];
            int width = (int) p[2];
            if (absOffset < regionAbsStart || absOffset + width > regionEnd) continue;
            int rel = (int) (absOffset - regionAbsStart);
            for (int i = 0; i < width; i++) {
                region[rel + i] = (byte) (value >>> (8 * (width - 1 - i)));
            }
        }
    }

    private static void writeMdatHeader(RandomAccessFile out, int headerSize, long contentSize) throws IOException {
        if (headerSize == 8) {
            long boxSize = 8 + contentSize;
            out.writeInt((int) boxSize);
            out.writeBytes("mdat");
        } else {
            out.writeInt(1);
            out.writeBytes("mdat");
            out.writeLong(16 + contentSize);
        }
    }
}
