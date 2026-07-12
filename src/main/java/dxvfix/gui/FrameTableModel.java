package dxvfix.gui;

import dxvfix.scan.FrameCheckResult;
import dxvfix.scan.FrameReport;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FrameTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"#", "Time (s)", "Size", "Status", "Format", "Replace with", "Detail"};

    private List<FrameReport> rows = new ArrayList<>();
    /** When true, only frames that are not a clean OK are shown. */
    private boolean problemsOnly = true;
    private List<FrameReport> all = new ArrayList<>();

    void setFrames(List<FrameReport> frames) {
        this.all = frames;
        applyFilter();
    }

    void setProblemsOnly(boolean v) {
        this.problemsOnly = v;
        applyFilter();
    }

    private void applyFilter() {
        if (!problemsOnly) {
            rows = all;
        } else {
            rows = new ArrayList<>();
            for (FrameReport f : all) {
                if (f.result.status != FrameCheckResult.Status.OK) rows.add(f);
            }
        }
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        FrameReport f = rows.get(rowIndex);
        switch (columnIndex) {
            case 0: return f.index;
            case 1: return String.format(Locale.ROOT, "%.3f", f.timestampSeconds);
            case 2: return f.size;
            case 3: return f.result.status;
            case 4: return f.result.format == null ? "?" : f.result.format;
            case 5: return f.generatedContent != null
                    ? "generated (interpolated)"
                    : f.replacementSampleIndex >= 0
                        ? f.replacementSampleIndex + (f.repairCaveat ? " ⚠ inter-frame donor" : "")
                        : (f.isBad() ? "none found!" : "-");
            case 6: return f.result.detail == null ? "" : f.result.detail;
            default: return "";
        }
    }
}
