package dxvfix.scan;

public final class FrameReport {
    public final int index;
    public final long offset;
    public final int size;
    public final double timestampSeconds;
    public final FrameCheckResult result;
    /** Sample index whose bytes will be used to replace this frame if it is bad; -1 if not bad or no replacement found. */
    public int replacementSampleIndex = -1;
    /** H.264/H.265 only: true if guaranteed intra-coded (safe to duplicate anywhere). See NalFrameCheck.isIntraOnly. */
    public boolean intraOnly = false;
    /** Set when the chosen replacement is an inter-predicted frame duplicated into a new position (H.264/H.265 risk). */
    public boolean repairCaveat = false;
    /** When set (by FrameGenerator), these freshly-synthesized bytes are spliced in instead of a donor sample's bytes. */
    public byte[] generatedContent;

    public FrameReport(int index, long offset, int size, double timestampSeconds, FrameCheckResult result) {
        this.index = index;
        this.offset = offset;
        this.size = size;
        this.timestampSeconds = timestampSeconds;
        this.result = result;
    }

    public boolean isBad() {
        return result.isBad();
    }

    public FrameReport withResult(FrameCheckResult newResult) {
        FrameReport f = new FrameReport(index, offset, size, timestampSeconds, newResult);
        f.replacementSampleIndex = replacementSampleIndex;
        f.intraOnly = intraOnly;
        f.repairCaveat = repairCaveat;
        return f;
    }
}
