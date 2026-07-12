package dxvfix.gui;

import dxvfix.queue.QueueItem;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Renders one queue row as [name+status][fixed-width close "x" glyph]. Two things this fixes
 * relative to a naive HTML-in-one-JLabel renderer that sizes itself off the raw filename:
 * <p>
 * 1) {@link #getPreferredSize()} is deliberately capped and does NOT grow with the filename's
 *    length, and {@code MainFrame} additionally overrides {@code JList.getScrollableTracksViewportWidth()}
 *    to always return true. Left unfixed, a long filename makes the renderer's (and therefore the
 *    row's, and therefore the whole JList's) preferred width balloon past the viewport width; once
 *    that happens {@code JList.getScrollableTracksViewportWidth()}'s *default* implementation
 *    returns false (it only tracks the viewport when the viewport is wider than the list wants),
 *    the list stops being clamped to the viewport, a horizontal scrollbar appears, and the close
 *    button — pinned to the far right of that now-oversized row — ends up scrolled out of view
 *    entirely. With both fixes the list is always exactly viewport width, so the close button is
 *    always at the visible right edge.
 * <p>
 * 2) The filename/status text is truncated with an ellipsis (via FontMetrics, computed from the
 *    live {@code list.getWidth()} at render time) to whatever width is actually available, rather
 *    than relying on HTML reflow — this is what keeps rendering to a single, non-nested JLabel
 *    (text as HTML for the bold name / small status lines) inside a single-level BorderLayout,
 *    which paints reliably from JList's cell renderer pane without depending on a second layout
 *    pass cascading into nested child containers.
 * <p>
 * 3) The close label is opaque with an explicit background fill, so even if something unexpected
 *    overlaps it, it still paints as a solid block on top rather than letting text bleed through.
 */
final class QueueListCellRenderer extends JPanel implements ListCellRenderer<QueueItem> {

    static final int CLOSE_ZONE_WIDTH = 26;
    private static final int CAPPED_PREFERRED_WIDTH = 200;
    private static final int RIGHT_PADDING = 6;

    private final JLabel textLabel = new JLabel();
    private final JLabel closeLabel = new JLabel("✕", SwingConstants.CENTER);

    QueueListCellRenderer() {
        super(new BorderLayout());
        setOpaque(true);

        textLabel.setBorder(new EmptyBorder(4, 8, 4, 4));
        textLabel.setOpaque(false);

        closeLabel.setOpaque(true);
        closeLabel.setPreferredSize(new Dimension(CLOSE_ZONE_WIDTH, 10));
        closeLabel.setFont(closeLabel.getFont().deriveFont(Font.BOLD, 12f));

        add(textLabel, BorderLayout.CENTER);
        add(closeLabel, BorderLayout.EAST);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension natural = super.getPreferredSize();
        return new Dimension(Math.min(natural.width, CAPPED_PREFERRED_WIDTH), natural.height);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends QueueItem> list, QueueItem item, int index,
                                                    boolean isSelected, boolean cellHasFocus) {
        int rowWidth = list.getWidth() > 0 ? list.getWidth() : CAPPED_PREFERRED_WIDTH;
        int availableForText = rowWidth - CLOSE_ZONE_WIDTH - RIGHT_PADDING - 12;

        FontMetrics boldFm = textLabel.getFontMetrics(textLabel.getFont().deriveFont(Font.BOLD));
        FontMetrics smallFm = textLabel.getFontMetrics(textLabel.getFont().deriveFont(Font.PLAIN, textLabel.getFont().getSize2D() - 1f));
        String name = truncate(item.file.getName(), availableForText, boldFm);
        String status = truncate(statusText(item), availableForText, smallFm);
        textLabel.setText("<html><b>" + escape(name) + "</b><br><small>" + escape(status) + "</small></html>");
        setToolTipText(item.file.getAbsolutePath());

        Color bg = isSelected ? list.getSelectionBackground() : list.getBackground();
        Color fg = isSelected ? list.getSelectionForeground() : statusColor(item, list.getForeground());
        setBackground(bg);
        textLabel.setForeground(fg);

        boolean removable = item.status != QueueItem.Status.SCANNING;
        closeLabel.setBackground(bg);
        closeLabel.setForeground(removable ? (isSelected ? list.getSelectionForeground() : new Color(150, 150, 150)) : bg);
        closeLabel.setToolTipText(removable ? "Убрать из очереди" : null);
        return this;
    }

    private static String truncate(String text, int availableWidth, FontMetrics fm) {
        if (text == null) return "";
        if (availableWidth <= 0) return "";
        if (fm.stringWidth(text) <= availableWidth) return text;
        String ellipsis = "…";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fm.stringWidth(text.substring(0, mid)) + ellipsisWidth <= availableWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo <= 0 ? ellipsis : text.substring(0, lo) + ellipsis;
    }

    private static String statusText(QueueItem item) {
        switch (item.status) {
            case PENDING:
                return "в очереди";
            case SCANNING:
                return "сканирование...";
            case DONE:
                int bad = item.badCount();
                String mode = item.lastMode == dxvfix.scan.ScanEngine.Mode.DEEP ? "углублённо" : "быстро";
                return (bad == 0 ? "OK" : bad + " битых") + " (" + mode + ")" + (item.repaired ? ", исправлен" : "");
            case ERROR:
                return "ошибка: " + (item.errorMessage == null ? "?" : item.errorMessage);
            default:
                return "";
        }
    }

    private static Color statusColor(QueueItem item, Color defaultColor) {
        switch (item.status) {
            case ERROR:
                return new Color(180, 40, 40);
            case DONE:
                return item.badCount() > 0 ? new Color(170, 110, 0) : new Color(30, 130, 30);
            default:
                return defaultColor;
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
