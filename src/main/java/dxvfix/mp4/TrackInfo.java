package dxvfix.mp4;

import java.util.ArrayList;
import java.util.List;

public final class TrackInfo {
    public int trackId = -1;
    public String handlerType = "";
    public String fourcc = "";
    public int width;
    public int height;
    public int timescale = 1;
    public CodecKind codecKind = CodecKind.UNKNOWN;
    /** First edit-list entry's media_time (track timescale units), 0 if no edit list. See parseEdts. */
    public long editListMediaTime = 0;

    /** For avc1/avc3/hvc1/hev1: length (in bytes) of the size prefix before each NAL unit, from avcC/hvcC. */
    public int nalLengthSize = 4;

    // stsz
    public long stszTableAbsOffset = -1; // -1 => constant size used
    public int stszConstantSize = 0;
    int[] rawSampleSizes;

    // stco / co64
    public long stcoTableAbsOffset = -1;
    public int stcoEntrySize = 4;
    public long[] rawChunkOffsets;

    // stsc: (firstChunk, samplesPerChunk, sampleDescIndex), firstChunk is 1-based
    final List<int[]> stscEntries = new ArrayList<>();

    // stts: (sampleCount, sampleDelta)
    final List<int[]> sttsEntries = new ArrayList<>();

    // ctts: (sampleCount, sampleOffset) - sampleOffset may be negative (version 1)
    final List<long[]> cttsEntries = new ArrayList<>();

    public List<SampleInfo> samples = new ArrayList<>();

    public boolean isVideo() {
        return "vide".equals(handlerType);
    }

    /** Expands stsc + chunk offsets + sample sizes into a flat per-sample list. */
    void buildSamples() {
        samples = new ArrayList<>();
        if (rawChunkOffsets == null || rawSampleSizes == null || stscEntries.isEmpty()) {
            return;
        }
        int chunkCount = rawChunkOffsets.length;
        int[] samplesPerChunk = new int[chunkCount];
        for (int i = 0; i < stscEntries.size(); i++) {
            int firstChunk = stscEntries.get(i)[0]; // 1-based
            int spc = stscEntries.get(i)[1];
            int endChunk = (i + 1 < stscEntries.size()) ? stscEntries.get(i + 1)[0] : chunkCount + 1;
            for (int c = firstChunk; c < endChunk && c <= chunkCount; c++) {
                samplesPerChunk[c - 1] = spc;
            }
        }

        // expand stts into per-sample decode time
        long[] decodeTimes = new long[rawSampleSizes.length];
        int si = 0;
        long t = 0;
        for (int[] e : sttsEntries) {
            int count = e[0];
            int delta = e[1];
            for (int k = 0; k < count && si < decodeTimes.length; k++) {
                decodeTimes[si++] = t;
                t += delta;
            }
        }
        while (si < decodeTimes.length) {
            decodeTimes[si++] = t; // no stts coverage (shouldn't normally happen)
        }

        // expand ctts into per-sample composition offset (0 if absent)
        long[] compOffsets = new long[rawSampleSizes.length];
        if (!cttsEntries.isEmpty()) {
            si = 0;
            for (long[] e : cttsEntries) {
                long count = e[0];
                long offset = e[1];
                for (long k = 0; k < count && si < compOffsets.length; k++) {
                    compOffsets[si++] = offset;
                }
            }
        }

        int sampleIdx = 0;
        for (int c = 0; c < chunkCount && sampleIdx < rawSampleSizes.length; c++) {
            long offset = rawChunkOffsets[c];
            int count = samplesPerChunk[c];
            for (int k = 0; k < count && sampleIdx < rawSampleSizes.length; k++) {
                int size = rawSampleSizes[sampleIdx];
                long dts = decodeTimes[sampleIdx];
                long pts = dts + compOffsets[sampleIdx] - editListMediaTime;
                samples.add(new SampleInfo(sampleIdx, offset, size, c, dts, pts));
                offset += size;
                sampleIdx++;
            }
        }
    }
}
