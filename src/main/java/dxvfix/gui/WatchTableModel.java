package dxvfix.gui;

import dxvfix.watch.WatchedFile;

import javax.swing.table.AbstractTableModel;
import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class WatchTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"Файл", "Битых кадров", "Обнаружено", "Исправлено"};
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final List<WatchedFile> rows = new ArrayList<>();
    private File baseDir;

    void setBaseDir(File baseDir) {
        this.baseDir = baseDir;
    }

    void upsert(WatchedFile wf) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).sourceFile.equals(wf.sourceFile)) {
                rows.set(i, wf);
                fireTableRowsUpdated(i, i);
                return;
            }
        }
        rows.add(wf);
        fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
    }

    void remove(File sourceFile) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).sourceFile.equals(sourceFile)) {
                rows.remove(i);
                fireTableRowsDeleted(i, i);
                return;
            }
        }
    }

    void clear() {
        int n = rows.size();
        rows.clear();
        if (n > 0) fireTableRowsDeleted(0, n - 1);
    }

    int size() {
        return rows.size();
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
    public String getColumnName(int c) {
        return COLUMNS[c];
    }

    @Override
    public Object getValueAt(int r, int c) {
        WatchedFile wf = rows.get(r);
        switch (c) {
            case 0:
                return relativize(wf.sourceFile);
            case 1:
                return wf.errorMessage != null ? "ошибка: " + wf.errorMessage : wf.badCount + " из " + wf.totalFrames;
            case 2:
                return wf.detectedAt == null ? "" : wf.detectedAt.format(TIME_FMT);
            case 3:
                return wf.fixed ? ("да, " + wf.fixedAt.format(TIME_FMT)) : "-";
            default:
                return "";
        }
    }

    private String relativize(File f) {
        if (baseDir == null) return f.getAbsolutePath();
        try {
            return baseDir.toPath().relativize(f.toPath()).toString();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }
}
