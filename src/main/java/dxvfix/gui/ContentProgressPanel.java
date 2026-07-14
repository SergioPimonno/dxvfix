package dxvfix.gui;

import dxvfix.i18n.Messages;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * Shows how much of the currently-watched show folder's content has been checked -- fed by
 * {@code ShowWatcher.Listener.onScanProgress} via {@code ShowWatchPanel}. Lives in {@code
 * MainFrame}'s bottom bar (not inside the show-watch tab itself) so it stays glanceable regardless
 * of which tab is in front, the same reasoning {@code SystemMonitorPanel} is pinned there for.
 */
final class ContentProgressPanel extends JPanel {

    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel();

    ContentProgressPanel() {
        super(new BorderLayout(8, 2));
        progressBar.setPreferredSize(new Dimension(160, progressBar.getPreferredSize().height));
        progressBar.setStringPainted(true);
        add(progressBar, BorderLayout.WEST);
        add(statusLabel, BorderLayout.CENTER);
        reset();
    }

    /** Safe to call from any thread. */
    void update(int checkedCount, int totalCount) {
        SwingUtilities.invokeLater(() -> {
            if (totalCount <= 0) {
                progressBar.setValue(0);
                progressBar.setString("");
                statusLabel.setText(Messages.get("contentProgress.idle"));
                return;
            }
            int percent = (int) Math.min(100, Math.round(checkedCount * 100.0 / totalCount));
            progressBar.setValue(percent);
            progressBar.setString(percent + "%");
            statusLabel.setText(checkedCount >= totalCount
                    ? Messages.get("contentProgress.allChecked", totalCount)
                    : Messages.get("contentProgress.checking", checkedCount, totalCount));
        });
    }

    void reset() {
        update(0, 0);
    }
}
