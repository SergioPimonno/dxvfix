package dxvfix.gui;

import dxvfix.i18n.Messages;
import dxvfix.watch.WatchedFile;

import javax.swing.*;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Renders/edits the watch table's "Действие" column as a per-row button: "Исправить" when the
 * file is broken and not already fixed or mid-fix, "Исправляется…"/"Исправлено" otherwise (shown
 * disabled). One instance serves as both renderer and editor, since they share all their state
 * derivation logic.
 */
final class WatchFixButtonCell extends AbstractCellEditor implements TableCellRenderer, TableCellEditor {

    private final JButton renderButton = new JButton();
    private final JButton editButton = new JButton();
    private final Consumer<WatchedFile> onFixClicked;
    private WatchedFile editingRow;

    WatchFixButtonCell(Consumer<WatchedFile> onFixClicked) {
        this.onFixClicked = onFixClicked;
        editButton.addActionListener(e -> {
            WatchedFile row = editingRow;
            fireEditingStopped();
            if (row != null && isActionable(row)) {
                onFixClicked.accept(row);
            }
        });
    }

    private static boolean isActionable(WatchedFile wf) {
        return !wf.fixed && !wf.fixing && wf.errorMessage == null;
    }

    private static String labelFor(WatchedFile wf) {
        if (wf.fixing) return Messages.get("watch.action.fixing");
        if (wf.fixed) return Messages.get("watch.action.fixed");
        if (wf.errorMessage != null) return Messages.get("watch.action.checkError");
        return Messages.get("watch.action.fix");
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                     boolean hasFocus, int row, int column) {
        WatchedFile wf = (WatchedFile) value;
        renderButton.setText(labelFor(wf));
        renderButton.setEnabled(isActionable(wf));
        return renderButton;
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
        editingRow = (WatchedFile) value;
        editButton.setText(labelFor(editingRow));
        editButton.setEnabled(isActionable(editingRow));
        return editButton;
    }

    @Override
    public Object getCellEditorValue() {
        return editingRow;
    }
}
