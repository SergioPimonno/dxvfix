package dxvfix.gui;

import dxvfix.ffmpeg.FfmpegLocator;
import dxvfix.i18n.Messages;
import dxvfix.scan.ScanEngine;
import dxvfix.watch.ShowWatcher;
import dxvfix.watch.WatchedFile;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.prefs.Preferences;

/**
 * "Show monitoring" tab: pick a content directory once, then leave it running for the duration of
 * a show. New/changed files are picked up automatically (see {@link ShowWatcher} for why this
 * polls instead of using a filesystem watch API), corrupt ones show up in the table below, and
 * with auto-fix on, repaired copies land in {@link ShowWatcher#FIXED_SUBFOLDER_NAME} inside the
 * watched directory itself (which is never re-scanned). Monitoring only runs between explicit
 * Start/Stop clicks so it costs nothing once the show's content is locked in.
 */
final class ShowWatchPanel extends JPanel {

    private static final Preferences PREFS = Preferences.userNodeForPackage(ShowWatchPanel.class);
    private static final String PREF_LAST_DIR = "lastContentDir";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextField dirField = new JTextField();
    private final JButton browseBtn = new JButton(Messages.get("showwatch.browse"));
    private final JRadioButton fastModeRadio = new JRadioButton(Messages.get("showwatch.mode.fast"), true);
    private final JRadioButton deepModeRadio = new JRadioButton(Messages.get("showwatch.mode.deep"));
    private final JRadioButton duplicateStrategyRadio = new JRadioButton(Messages.get("showwatch.strategy.duplicate"), true);
    private final JRadioButton generateStrategyRadio = new JRadioButton(Messages.get("showwatch.strategy.generate"));
    private final JCheckBox autoFixBox = new JCheckBox(Messages.get("showwatch.autoFix"), false);
    private final JButton startStopBtn = new JButton(Messages.get("showwatch.start"));
    private final JLabel statusLabel = new JLabel(Messages.get("showwatch.status.stopped"));
    private final JLabel ffmpegStatusLabel = new JLabel(" ");
    private final WatchTableModel tableModel = new WatchTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextArea log = new JTextArea();

    private final ShowWatcher watcher;
    private String ffmpegPath;

    ShowWatchPanel() {
        super(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        add(buildForm(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);

        String lastDir = PREFS.get(PREF_LAST_DIR, "");
        if (!lastDir.isBlank()) dirField.setText(lastDir);

        refreshFfmpegStatus();
        wireActions();

        watcher = new ShowWatcher(new ShowWatcher.Listener() {
            @Override
            public void onFileUpdated(WatchedFile file) {
                SwingUtilities.invokeLater(() -> {
                    tableModel.upsert(file);
                    statusLabel.setText(Messages.get("showwatch.status.active", tableModel.size()));
                });
            }

            @Override
            public void onFileCleared(File sourceFile) {
                SwingUtilities.invokeLater(() -> {
                    tableModel.remove(sourceFile);
                    statusLabel.setText(Messages.get("showwatch.status.active", tableModel.size()));
                });
            }

            @Override
            public void onLog(String message) {
                SwingUtilities.invokeLater(() -> appendLog(message));
            }
        });
    }

    private JComponent buildForm() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JPanel dirRow = new JPanel(new BorderLayout(6, 6));
        dirRow.add(new JLabel(Messages.get("showwatch.dirLabel")), BorderLayout.WEST);
        dirRow.add(dirField, BorderLayout.CENTER);
        dirRow.add(browseBtn, BorderLayout.EAST);
        dirRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(dirRow);

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(fastModeRadio);
        modeGroup.add(deepModeRadio);
        JPanel modeRow = new JPanel(new WrapLayout(FlowLayout.LEFT));
        modeRow.add(new JLabel(Messages.get("showwatch.modeLabel")));
        modeRow.add(fastModeRadio);
        modeRow.add(deepModeRadio);
        modeRow.add(ffmpegStatusLabel);
        modeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(modeRow);

        ButtonGroup strategyGroup = new ButtonGroup();
        strategyGroup.add(duplicateStrategyRadio);
        strategyGroup.add(generateStrategyRadio);
        JPanel strategyRow = new JPanel(new WrapLayout(FlowLayout.LEFT));
        strategyRow.add(new JLabel(Messages.get("showwatch.strategyLabel")));
        strategyRow.add(duplicateStrategyRadio);
        strategyRow.add(generateStrategyRadio);
        strategyRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(strategyRow);

        JLabel hint = new JLabel(Messages.get("showwatch.hint"));
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, hint.getFont().getSize2D() - 1f));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(hint);

        JPanel actionRow = new WrapLayoutPanel();
        actionRow.add(autoFixBox);
        actionRow.add(startStopBtn);
        actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(actionRow);

        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(statusLabel);

        return form;
    }

    /** Trivial named subclass so buildForm() reads cleanly; behaves exactly like a WrapLayout JPanel. */
    private static final class WrapLayoutPanel extends JPanel {
        WrapLayoutPanel() {
            super(new WrapLayout(FlowLayout.LEFT));
        }
    }

    private JComponent buildCenter() {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.65);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(new TitledBorder(Messages.get("showwatch.table.title")));

        javax.swing.table.TableRowSorter<WatchTableModel> sorter = new javax.swing.table.TableRowSorter<>(tableModel);
        sorter.setSortable(WatchTableModel.ACTION_COLUMN, false); // column holds a WatchedFile, not sortable text
        table.setRowSorter(sorter);

        WatchFixButtonCell fixButtonCell = new WatchFixButtonCell(this::onManualFixRequested);
        table.getColumnModel().getColumn(WatchTableModel.ACTION_COLUMN).setCellRenderer(fixButtonCell);
        table.getColumnModel().getColumn(WatchTableModel.ACTION_COLUMN).setCellEditor(fixButtonCell);

        split.setTopComponent(tableScroll);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(new TitledBorder(Messages.get("showwatch.log.title")));
        split.setBottomComponent(logScroll);

        split.setDividerLocation(300);
        return split;
    }

    private void refreshFfmpegStatus() {
        ffmpegPath = FfmpegLocator.find();
        if (ffmpegPath != null) {
            ffmpegStatusLabel.setText(Messages.get("showwatch.ffmpeg.found"));
            deepModeRadio.setEnabled(true);
            generateStrategyRadio.setEnabled(true);
        } else {
            ffmpegStatusLabel.setText(Messages.get("showwatch.ffmpeg.notFound"));
            deepModeRadio.setEnabled(false);
            if (deepModeRadio.isSelected()) fastModeRadio.setSelected(true);
            generateStrategyRadio.setEnabled(false);
            if (generateStrategyRadio.isSelected()) duplicateStrategyRadio.setSelected(true);
        }
    }

    private void wireActions() {
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (!dirField.getText().isBlank()) {
                fc.setCurrentDirectory(new File(dirField.getText()));
            }
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                dirField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        startStopBtn.addActionListener(e -> {
            if (watcher.isRunning()) {
                watcher.stop();
                onStopped();
            } else {
                startWatching();
            }
        });
    }

    private void startWatching() {
        String path = dirField.getText().trim();
        if (path.isBlank()) {
            JOptionPane.showMessageDialog(this, Messages.get("showwatch.noDirMessage"),
                    Messages.get("showwatch.noDirTitle"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        File dir = new File(path);
        if (!dir.isDirectory()) {
            JOptionPane.showMessageDialog(this, Messages.get("showwatch.dirNotFound", path),
                    Messages.get("mainframe.error.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }

        refreshFfmpegStatus();
        ScanEngine.Mode mode = deepModeRadio.isSelected() ? ScanEngine.Mode.DEEP : ScanEngine.Mode.FAST;
        boolean useGenerate = generateStrategyRadio.isSelected();
        boolean autoFix = autoFixBox.isSelected();

        PREFS.put(PREF_LAST_DIR, dir.getAbsolutePath());
        tableModel.setBaseDir(dir);
        tableModel.clear();
        log.setText("");

        watcher.start(dir, mode, useGenerate, autoFix, ffmpegPath);
        startStopBtn.setText(Messages.get("showwatch.stop"));
        statusLabel.setText(Messages.get("showwatch.status.active", 0));
        setControlsEnabledWhileEditable(false);
    }

    private void onManualFixRequested(WatchedFile wf) {
        watcher.fixNow(wf);
    }

    private void onStopped() {
        startStopBtn.setText(Messages.get("showwatch.start"));
        statusLabel.setText(Messages.get("showwatch.status.stoppedWithHistory"));
        setControlsEnabledWhileEditable(true);
    }

    private void setControlsEnabledWhileEditable(boolean enabled) {
        dirField.setEnabled(enabled);
        browseBtn.setEnabled(enabled);
        fastModeRadio.setEnabled(enabled && (ffmpegPath != null || true));
        deepModeRadio.setEnabled(enabled && ffmpegPath != null);
        duplicateStrategyRadio.setEnabled(enabled);
        generateStrategyRadio.setEnabled(enabled && ffmpegPath != null);
        autoFixBox.setEnabled(enabled);
    }

    private void appendLog(String message) {
        log.append("[" + java.time.LocalTime.now().format(TIME_FMT) + "] " + message + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }
}
