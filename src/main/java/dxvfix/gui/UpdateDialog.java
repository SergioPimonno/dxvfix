package dxvfix.gui;

import dxvfix.AppVersion;
import dxvfix.i18n.Messages;
import dxvfix.update.UpdateManager;
import dxvfix.update.VersionManifest;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.List;

/**
 * "Update version" screen: fetches the hand-maintained versions.txt from GitHub, lets the user
 * pick one of the versions marked available, downloads its dxvfix.jar, and hands off to {@link
 * UpdateManager#applyAndRestart} -- which needs the app to actually exit before it can finish
 * (Windows won't let the running jar be overwritten while it's open), so a successful update ends
 * with {@code System.exit(0)} rather than returning control to the caller.
 */
final class UpdateDialog {

    private UpdateDialog() {
    }

    static void show(Component parent) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), Messages.get("update.title"),
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel currentVersionLabel = new JLabel(Messages.get("update.currentVersion", AppVersion.VERSION));
        JComboBox<VersionManifest> versionBox = new JComboBox<>();
        versionBox.setEnabled(false);
        JButton updateBtn = new JButton(Messages.get("update.updateButton"));
        updateBtn.setEnabled(false);
        JProgressBar progressBar = new JProgressBar(0, 100);
        JLabel statusLabel = new JLabel(Messages.get("update.fetchingList"));

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        currentVersionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(currentVersionLabel);
        top.add(Box.createVerticalStrut(8));
        JPanel selectRow = new JPanel(new BorderLayout(6, 6));
        selectRow.add(new JLabel(Messages.get("update.selectVersion")), BorderLayout.WEST);
        selectRow.add(versionBox, BorderLayout.CENTER);
        selectRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(selectRow);
        content.add(top, BorderLayout.NORTH);

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.add(progressBar, BorderLayout.NORTH);
        bottom.add(statusLabel, BorderLayout.CENTER);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnRow.add(updateBtn);
        bottom.add(btnRow, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.setPreferredSize(new Dimension(440, 220));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);

        SwingWorker<List<VersionManifest>, Void> fetchWorker = new SwingWorker<>() {
            @Override
            protected List<VersionManifest> doInBackground() throws Exception {
                return VersionManifest.fetchAvailable();
            }

            @Override
            protected void done() {
                try {
                    List<VersionManifest> versions = get();
                    if (versions.isEmpty()) {
                        statusLabel.setText(Messages.get("update.noneAvailable"));
                        return;
                    }
                    for (VersionManifest v : versions) versionBox.addItem(v);
                    versionBox.setEnabled(true);
                    updateBtn.setEnabled(true);
                    statusLabel.setText(" ");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    statusLabel.setText(Messages.get("update.fetchFailed",
                            cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
                }
            }
        };
        fetchWorker.execute();

        updateBtn.addActionListener(e -> {
            VersionManifest selected = (VersionManifest) versionBox.getSelectedItem();
            if (selected == null) return;

            int choice = JOptionPane.showConfirmDialog(dialog,
                    Messages.get("update.confirmMessage", selected.version),
                    Messages.get("update.title"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;

            updateBtn.setEnabled(false);
            versionBox.setEnabled(false);
            progressBar.setIndeterminate(false);
            progressBar.setValue(0);

            SwingWorker<Void, Object[]> worker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() throws Exception {
                    Path downloaded = UpdateManager.download(selected,
                            (downloadedBytes, total, phase) -> publish(new Object[]{downloadedBytes, total, phase}));
                    UpdateManager.applyAndRestart(downloaded);
                    return null;
                }

                @Override
                protected void process(List<Object[]> chunks) {
                    Object[] last = chunks.get(chunks.size() - 1);
                    long downloadedBytes = (Long) last[0];
                    long total = (Long) last[1];
                    String phase = (String) last[2];
                    if (total > 0) {
                        progressBar.setIndeterminate(false);
                        progressBar.setValue((int) Math.min(100, downloadedBytes * 100 / total));
                    } else {
                        progressBar.setIndeterminate(true);
                    }
                    statusLabel.setText(phase);
                }

                @Override
                protected void done() {
                    try {
                        get();
                        JOptionPane.showMessageDialog(dialog, Messages.get("update.restarting"),
                                Messages.get("update.title"), JOptionPane.INFORMATION_MESSAGE);
                        System.exit(0);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        progressBar.setIndeterminate(false);
                        updateBtn.setEnabled(true);
                        versionBox.setEnabled(true);
                        statusLabel.setText(Messages.get("update.failed",
                                cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
                    }
                }
            };
            worker.execute();
        });

        dialog.setVisible(true);
    }
}
