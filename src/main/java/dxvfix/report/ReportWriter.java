package dxvfix.report;

import dxvfix.scan.FrameCheckResult;
import dxvfix.scan.FrameReport;
import dxvfix.scan.ScanResult;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ReportWriter {

    private ReportWriter() {
    }

    public static void write(File reportFile, File sourceFile, ScanResult scan, boolean repaired) throws IOException {
        try (PrintWriter w = new PrintWriter(reportFile, StandardCharsets.UTF_8)) {
            w.println("Frame scan report");
            w.println("File: " + sourceFile.getAbsolutePath());
            w.println("Codec: " + scan.videoTrack.codecKind.label() + " (" + scan.videoTrack.fourcc + ")");
            w.println("Track: " + scan.videoTrack.width + "x" + scan.videoTrack.height);
            w.println("Total frames: " + scan.frames.size());
            w.println("Bad frames: " + scan.badCount());
            w.println("Shallow-only validated frames (not deep-checked by the fast structural pass): " + scan.shallowCount());
            w.println("Repaired: " + repaired);
            w.println();
            w.println(String.format(Locale.ROOT, "%-8s %-10s %-8s %-18s %-10s %-10s %s",
                    "frame", "time(s)", "size", "status", "format", "replaced_by", "detail"));
            for (FrameReport f : scan.frames) {
                if (f.result.status == FrameCheckResult.Status.OK) continue;
                String replacedBy = f.generatedContent != null ? "generated"
                        : f.replacementSampleIndex >= 0 ? String.valueOf(f.replacementSampleIndex) : "-";
                w.println(String.format(Locale.ROOT, "%-8d %-10.3f %-8d %-18s %-10s %-10s %s",
                        f.index, f.timestampSeconds, f.size, f.result.status,
                        f.result.format == null ? "?" : f.result.format,
                        replacedBy,
                        (f.result.detail == null ? "" : f.result.detail) +
                                (f.repairCaveat ? "  [WARNING: donor is an inter-predicted frame; expect possible decode glitches near this frame]" : "")));
            }
        }
    }
}
