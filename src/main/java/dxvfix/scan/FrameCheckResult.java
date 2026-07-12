package dxvfix.scan;

/** Codec-neutral result of validating one compressed video sample. */
public final class FrameCheckResult {

    public enum Status {
        /** Header and full bitstream state machine replayed cleanly. */
        OK,
        /** Header looked sane but the payload isn't deeply validated by the fast structural check
         *  (e.g. DXV YCoCg, legacy LZF, or any codec whose fast check is header-only). */
        OK_SHALLOW,
        /** Header fields are inconsistent with the sample size, or an unknown/garbled tag was found. */
        HEADER_INVALID,
        /** The compressed data itself is structurally invalid (out-of-range back-reference, bad NAL, etc). */
        BITSTREAM_INVALID,
        /** Deep mode: a real decoder (ffmpeg) could not produce this frame at all. */
        DEEP_DECODE_FAILED
    }

    public final Status status;
    public final String detail;
    public final String format;

    public FrameCheckResult(Status status, String detail, String format) {
        this.status = status;
        this.detail = detail;
        this.format = format;
    }

    public boolean isBad() {
        return status == Status.HEADER_INVALID || status == Status.BITSTREAM_INVALID || status == Status.DEEP_DECODE_FAILED;
    }

    @Override
    public String toString() {
        return status + (detail == null ? "" : ": " + detail);
    }
}
