package dxvfix.mp4;

/** One sample (frame) of a track: its byte range in the file and which chunk it belongs to. */
public final class SampleInfo {
    public final int index;
    public final long offset;
    public final int size;
    public final int chunkIndex;
    public final long decodeTime;        // DTS, in track timescale units (from stts)
    public final long presentationTime;  // PTS, decodeTime + ctts offset (== decodeTime if no ctts/B-frames)

    public SampleInfo(int index, long offset, int size, int chunkIndex, long decodeTime, long presentationTime) {
        this.index = index;
        this.offset = offset;
        this.size = size;
        this.chunkIndex = chunkIndex;
        this.decodeTime = decodeTime;
        this.presentationTime = presentationTime;
    }
}
