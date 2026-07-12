package dxvfix.repair;

public final class RepairSummary {
    public final int framesReplaced;
    public final int framesUnrepairable;
    public final long outputMdatSize;

    public RepairSummary(int framesReplaced, int framesUnrepairable, long outputMdatSize) {
        this.framesReplaced = framesReplaced;
        this.framesUnrepairable = framesUnrepairable;
        this.outputMdatSize = outputMdatSize;
    }
}
