package dxvfix.gui;

import dxvfix.ffmpeg.FfmpegLocator;
import dxvfix.generate.FrameGenerator;
import dxvfix.license.Fingerprint;
import dxvfix.license.LicenseVerifier;
import dxvfix.queue.QueueItem;
import dxvfix.repair.Mp4Repairer;
import dxvfix.repair.RepairSummary;
import dxvfix.report.ReportWriter;
import dxvfix.scan.ScanEngine;
import dxvfix.scan.ScanResult;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class MainFrame extends JFrame {

    private final DefaultListModel<QueueItem> queueModel = new DefaultListModel<>();
    // Always clamp to the viewport's width, regardless of how wide the renderer's content wants
    // to be: without this override, a long filename can make the list wider than its preferred
    // size lets JScrollPane compress it to, which triggers a horizontal scrollbar and scrolls the
    // per-row close button out of view. See QueueListCellRenderer's class doc for the full story.
    private final JList<QueueItem> queueList = new JList<>(queueModel) {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }
    };
    private final JButton addFilesBtn = new JButton("Добавить файлы…");
    private final JButton removeBtn = new JButton("Удалить выбранные файлы");
    private final JButton scanQueueBtn = new JButton("Сканировать очередь");

    private final JButton fixBtn = new JButton("Исправить и сохранить как…");
    private final JRadioButton duplicateStrategyRadio = new JRadioButton("Дублировать соседний кадр", true);
    private final JRadioButton generateStrategyRadio = new JRadioButton("Сгенерировать кадр (интерполяция, экспериментально)");
    private final JCheckBox problemsOnlyBox = new JCheckBox("Показывать только проблемные кадры", true);
    private final JRadioButton fastModeRadio = new JRadioButton("Быстрая проверка", true);
    private final JRadioButton deepModeRadio = new JRadioButton("Углублённая проверка (декодирование через ffmpeg)");
    private final JButton ffmpegBtn = new JButton("ffmpeg…");
    private final JButton ffmpegDownloadBtn = new JButton("Скачать и установить ffmpeg…");
    private final JLabel ffmpegStatusLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel summaryLabel = new JLabel(" ");
    private final FrameTableModel tableModel = new FrameTableModel();
    private final JTable table = new JTable(tableModel);

    private String ffmpegPath;
    private boolean queueRunning = false;
    private double queuePanelProportion = 280.0 / 1180.0;

    public MainFrame() {
        super("DXV Frame Doctor — очередь проверки и починки видеофайлов");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));
        setJMenuBar(buildMenuBar());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildQueuePanel(), buildDetailPanel());
        split.setDividerLocation(280);
        installProportionalDividerHandling(split);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Пакетная проверка", split);
        tabs.addTab("Сопровождение шоу", new ShowWatchPanel());
        add(tabs, BorderLayout.CENTER);

        installDragAndDrop();
        wireActions();
        refreshFfmpegStatus();
        updateSelectionDependentUi();

        setPreferredSize(new Dimension(1180, 680));
        pack();
        setLocationRelativeTo(null);
    }

    /**
     * {@code JSplitPane.resizeWeight} turned out to be unreliable in practice here: verified with
     * an instrumented test that shrinking the window redistributes space correctly, but growing it
     * leaves the divider frozen at its old pixel position — so the left (queue) panel's width
     * never increased when the window was enlarged. Rather than depend on that, this manages the
     * divider position explicitly: remember the queue panel's width as a *proportion* of the split
     * pane's total width, and reapply that same proportion every time the split pane itself is
     * resized (i.e. the window is resized).
     * <p>
     * The proportion is only updated from a genuine user drag (a mouse release on the divider),
     * not from our own programmatic {@code setDividerLocation} calls during a window resize —
     * listening on {@code JSplitPane.DIVIDER_LOCATION_PROPERTY} instead would also fire for those
     * and fight itself.
     */
    private void installProportionalDividerHandling(JSplitPane split) {
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (split.getWidth() > 0) {
                    split.setDividerLocation((int) Math.round(split.getWidth() * queuePanelProportion));
                }
            }
        });
        if (split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI) {
            Component divider = ((javax.swing.plaf.basic.BasicSplitPaneUI) split.getUI()).getDivider();
            divider.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (split.getWidth() > 0) {
                        queuePanelProportion = (double) split.getDividerLocation() / split.getWidth();
                    }
                }
            });
        }
    }

    // ---- menu bar -------------------------------------------------------------------------------

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu menu = new JMenu("Меню");
        JMenuItem checkLicense = new JMenuItem("Проверить лицензию…");
        checkLicense.addActionListener(e -> showLicenseStatus());
        JMenuItem help = new JMenuItem("Справка…");
        help.addActionListener(e -> HelpDialog.show(this));
        menu.add(checkLicense);
        menu.addSeparator();
        menu.add(help);
        bar.add(menu);
        return bar;
    }

    private void showLicenseStatus() {
        LicenseVerifier.Result result = LicenseVerifier.verify(LicenseVerifier.defaultLicensePath());
        String fingerprint;
        try {
            fingerprint = Fingerprint.compute();
        } catch (Exception ex) {
            fingerprint = "не удалось определить (" + ex.getMessage() + ")";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Статус: ").append(translateLicenseStatus(result.status)).append('\n');
        if (result.record != null) {
            sb.append("Метка: ").append(result.record.label.isBlank() ? "-" : result.record.label).append('\n');
            sb.append("Fingerprint в лицензии: ").append(result.record.fingerprint).append('\n');
            sb.append("Выдана: ").append(result.record.issued).append('\n');
            sb.append("Истекает: ").append(result.record.expires == null ? "бессрочно" : result.record.expires).append('\n');
        }
        sb.append('\n').append("Fingerprint этого устройства: ").append(fingerprint);
        if (!result.isValid()) {
            sb.append("\n\n").append(result.message);
        }

        JOptionPane.showMessageDialog(this, sb.toString(), "Проверка лицензии",
                result.isValid() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    private static String translateLicenseStatus(LicenseVerifier.Status status) {
        switch (status) {
            case VALID: return "действительна";
            case FILE_NOT_FOUND: return "файл лицензии не найден";
            case MALFORMED: return "файл лицензии повреждён";
            case BAD_SIGNATURE: return "подпись недействительна";
            case FINGERPRINT_MISMATCH: return "не подходит для этого устройства";
            case EXPIRED: return "истекла";
            case NO_PUBLIC_KEY: return "не удалось проверить (нет встроенного публичного ключа)";
            default: return status.toString();
        }
    }

    // ---- left: queue -------------------------------------------------------------------------

    private JComponent buildQueuePanel() {
        JPanel left = new JPanel(new BorderLayout(6, 6));
        left.setBorder(new TitledBorder("Очередь файлов"));

        queueList.setCellRenderer(new QueueListCellRenderer());
        queueList.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        installQueueCloseButtonHandler();
        left.add(new JScrollPane(queueList), BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new GridLayout(3, 1, 4, 4));
        toolbar.add(addFilesBtn);
        toolbar.add(removeBtn);
        toolbar.add(scanQueueBtn);
        left.add(toolbar, BorderLayout.SOUTH);

        return left;
    }

    // ---- right: per-file results/actions -----------------------------------------------------

    private JComponent buildDetailPanel() {
        JPanel right = new JPanel(new BorderLayout(8, 8));

        JPanel modePanel = new JPanel(new WrapLayout(FlowLayout.LEFT));
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(fastModeRadio);
        modeGroup.add(deepModeRadio);
        modePanel.add(fastModeRadio);
        modePanel.add(deepModeRadio);
        modePanel.add(ffmpegBtn);
        modePanel.add(ffmpegDownloadBtn);
        modePanel.add(ffmpegStatusLabel);

        ButtonGroup strategyGroup = new ButtonGroup();
        strategyGroup.add(duplicateStrategyRadio);
        strategyGroup.add(generateStrategyRadio);
        JPanel strategyPanel = new JPanel(new WrapLayout(FlowLayout.LEFT));
        strategyPanel.add(new JLabel("Способ починки:"));
        strategyPanel.add(duplicateStrategyRadio);
        strategyPanel.add(generateStrategyRadio);

        JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT));
        actions.add(fixBtn);
        actions.add(problemsOnlyBox);

        // BoxLayout instead of GridLayout(3,1): each row keeps its own natural (possibly wrapped,
        // multi-line) height instead of all three rows being stretched to match the tallest one.
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        modePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        strategyPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(modePanel);
        north.add(strategyPanel);
        north.add(actions);
        right.add(north, BorderLayout.NORTH);

        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(6).setPreferredWidth(360);
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(new TitledBorder("Результаты по кадрам выбранного файла"));
        right.add(sp, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.add(progressBar, BorderLayout.NORTH);
        bottom.add(summaryLabel, BorderLayout.SOUTH);
        right.add(bottom, BorderLayout.SOUTH);

        fixBtn.setEnabled(false);

        // Without this, the FlowLayout toolbar rows above (mode/strategy/actions) report a
        // minimum width that's the sum of every button/label in each row (FlowLayout's minimum
        // size calculation doesn't account for the fact that it can wrap those rows onto multiple
        // lines when space is tight) -- easily over 1000px once the ffmpeg status label is long.
        // JSplitPane refuses to drag the divider past a child's reported minimum size, so with
        // that huge a minimum, the divider becomes un-draggable the moment the window is smaller
        // than roughly that width, even though the rows would visually wrap and fit just fine.
        right.setMinimumSize(new Dimension(300, 200));
        return right;
    }

    private void refreshFfmpegStatus() {
        ffmpegPath = FfmpegLocator.find();
        if (ffmpegPath != null) {
            ffmpegStatusLabel.setText("ffmpeg: " + ffmpegPath);
            deepModeRadio.setEnabled(true);
            generateStrategyRadio.setEnabled(true);
        } else {
            ffmpegStatusLabel.setText("ffmpeg не найден — глубокая проверка и генерация кадров недоступны");
            deepModeRadio.setEnabled(false);
            if (deepModeRadio.isSelected()) fastModeRadio.setSelected(true);
            generateStrategyRadio.setEnabled(false);
            if (generateStrategyRadio.isSelected()) duplicateStrategyRadio.setSelected(true);
        }
    }

    // ---- drag & drop / file selection ----------------------------------------------------------

    private void installDragAndDrop() {
        DropTargetAdapter listener = new DropTargetAdapter() {
            @Override
            @SuppressWarnings("unchecked")
            public void drop(DropTargetDropEvent evt) {
                try {
                    evt.acceptDrop(DnDConstants.ACTION_COPY);
                    List<File> files = (List<File>) evt.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    addFilesExpandingFolders(files);
                    evt.dropComplete(true);
                } catch (Exception e) {
                    evt.dropComplete(false);
                    showError("Не удалось принять файлы", e);
                }
            }
        };
        new DropTarget(this, DnDConstants.ACTION_COPY, listener);
        new DropTarget(queueList, DnDConstants.ACTION_COPY, listener);
    }

    private void wireActions() {
        addFilesBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setMultiSelectionEnabled(true);
            fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("QuickTime/MP4 (*.mov, *.mp4) или папка", "mov", "mp4"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                addFilesExpandingFolders(List.of(fc.getSelectedFiles()));
            }
        });
        removeBtn.addActionListener(e -> {
            int[] selected = queueList.getSelectedIndices();
            if (selected.length == 0) return;
            removeItems(selected);
        });
        scanQueueBtn.addActionListener(e -> runQueueScan());
        fixBtn.addActionListener(e -> runFix());
        problemsOnlyBox.addActionListener(e -> tableModel.setProblemsOnly(problemsOnlyBox.isSelected()));
        ffmpegBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ffmpeg.exe", "exe"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String candidate = fc.getSelectedFile().getAbsolutePath();
                if (FfmpegLocator.works(candidate)) {
                    FfmpegLocator.setConfiguredPath(candidate);
                    refreshFfmpegStatus();
                } else {
                    JOptionPane.showMessageDialog(this, "Не удалось запустить этот файл как ffmpeg.",
                            "Ошибка", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        ffmpegDownloadBtn.addActionListener(e -> runFfmpegDownload());
        queueList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateSelectionDependentUi();
        });
    }

    private void runFfmpegDownload() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Будет скачан архив (~100 МБ) с официальной сборкой ffmpeg для Windows:\n" +
                        dxvfix.ffmpeg.FfmpegInstaller.DOWNLOAD_URL + "\n\n" +
                        "Установится в: " + dxvfix.ffmpeg.FfmpegInstaller.installDir() + "\n\nПродолжить?",
                "Скачать ffmpeg", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        ffmpegDownloadBtn.setEnabled(false);
        ffmpegBtn.setEnabled(false);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);

        SwingWorker<java.nio.file.Path, Object[]> worker = new SwingWorker<>() {
            @Override
            protected java.nio.file.Path doInBackground() throws Exception {
                return dxvfix.ffmpeg.FfmpegInstaller.downloadAndInstall((downloaded, total, phase) ->
                        publish(new Object[]{downloaded, total, phase}));
            }

            @Override
            protected void process(List<Object[]> chunks) {
                Object[] last = chunks.get(chunks.size() - 1);
                long downloaded = (Long) last[0];
                long total = (Long) last[1];
                String phase = (String) last[2];
                if (total > 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setValue((int) Math.min(100, downloaded * 100 / total));
                } else {
                    progressBar.setIndeterminate(true);
                }
                summaryLabel.setText(phase);
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                ffmpegDownloadBtn.setEnabled(true);
                ffmpegBtn.setEnabled(true);
                try {
                    java.nio.file.Path installed = get();
                    FfmpegLocator.setConfiguredPath(installed.toAbsolutePath().toString());
                    refreshFfmpegStatus();
                    summaryLabel.setText("ffmpeg установлен: " + installed);
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "ffmpeg установлен и готов к использованию:\n" + installed,
                            "Готово", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    summaryLabel.setText("Не удалось установить ffmpeg.");
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Не удалось скачать/установить ffmpeg: " + cause.getMessage(),
                            "Ошибка", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    /**
     * Accepts a mix of files and folders (as dropped or picked via the file chooser). Folders are
     * walked recursively (in the background, since a dropped folder could be large) to collect
     * every .mov/.mp4 inside, including nested subfolders, before adding everything to the queue.
     */
    private void addFilesExpandingFolders(List<File> dropped) {
        boolean hasFolders = dropped.stream().anyMatch(File::isDirectory);
        if (!hasFolders) {
            addFiles(dropped);
            return;
        }

        summaryLabel.setText("Поиск видеофайлов в папках…");
        SwingWorker<List<File>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<File> doInBackground() {
                List<File> collected = new ArrayList<>();
                for (File f : dropped) {
                    if (f.isDirectory()) {
                        collected.addAll(dxvfix.util.VideoFileFinder.find(f));
                    } else {
                        collected.add(f);
                    }
                }
                return collected;
            }

            @Override
            protected void done() {
                try {
                    List<File> collected = get();
                    addFiles(collected);
                    summaryLabel.setText("Добавлено файлов: " + collected.size());
                } catch (Exception ex) {
                    showError("Не удалось обработать папки", ex);
                    summaryLabel.setText(" ");
                }
            }
        };
        worker.execute();
    }

    private void addFiles(List<File> files) {
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < queueModel.size(); i++) existing.add(queueModel.get(i).file.getAbsolutePath());
        int firstNewIndex = -1;
        for (File f : files) {
            if (f.isDirectory() || existing.contains(f.getAbsolutePath())) continue;
            queueModel.addElement(new QueueItem(f));
            existing.add(f.getAbsolutePath());
            if (firstNewIndex < 0) firstNewIndex = queueModel.size() - 1;
        }
        if (firstNewIndex >= 0 && queueList.getSelectedIndex() < 0) {
            queueList.setSelectedIndex(firstNewIndex);
        }
        queueList.revalidate();
        queueList.repaint();
    }

    /** Removes the given model indices, skipping (and warning about) any that are currently scanning. */
    private void removeItems(int[] indices) {
        int[] sorted = indices.clone();
        java.util.Arrays.sort(sorted);
        int skipped = 0;
        for (int i = sorted.length - 1; i >= 0; i--) {
            QueueItem item = queueModel.get(sorted[i]);
            if (item.status == QueueItem.Status.SCANNING) {
                skipped++;
                continue;
            }
            queueModel.remove(sorted[i]);
        }
        queueList.revalidate();
        queueList.repaint();
        if (skipped > 0) {
            JOptionPane.showMessageDialog(this,
                    skipped + " файл(ов) сейчас сканируется и не был(и) удалён(ы).",
                    "Нельзя удалить", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Clicking the "x" glyph on the right edge of a row removes just that row. The horizontal
     * hit-test deliberately uses the list's *live* {@code getWidth()} rather than the width from
     * {@code getCellBounds()}: the latter can lag behind the actual current size (e.g. right after
     * the vertical scrollbar appears/disappears, or during the split pane's initial layout), which
     * made the close zone appear to track a stale/maximum width instead of the real right edge.
     */
    private void installQueueCloseButtonHandler() {
        queueList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = queueList.locationToIndex(e.getPoint());
                if (index < 0 || index >= queueModel.size()) return;
                Rectangle bounds = queueList.getCellBounds(index, index);
                if (bounds == null || e.getPoint().y < bounds.y || e.getPoint().y >= bounds.y + bounds.height) return;
                int listWidth = queueList.getWidth();
                if (e.getPoint().x >= listWidth - QueueListCellRenderer.CLOSE_ZONE_WIDTH) {
                    removeItems(new int[]{index});
                }
            }
        });
        // Belt-and-braces: force a clean relayout+repaint whenever the list's own size changes
        // (e.g. split-pane drag, scrollbar appearing), so rendering never lags behind reality.
        queueList.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                queueList.revalidate();
                queueList.repaint();
            }
        });
    }

    // ---- selection -> right panel ---------------------------------------------------------------

    private QueueItem selected() {
        int idx = queueList.getSelectedIndex();
        return idx < 0 ? null : queueModel.get(idx);
    }

    private void updateSelectionDependentUi() {
        QueueItem item = selected();
        if (item == null) {
            tableModel.setFrames(java.util.Collections.emptyList());
            summaryLabel.setText(" ");
            fixBtn.setEnabled(false);
            return;
        }
        switch (item.status) {
            case PENDING:
                tableModel.setFrames(java.util.Collections.emptyList());
                summaryLabel.setText(item.file.getName() + " — ожидает сканирования");
                fixBtn.setEnabled(false);
                break;
            case SCANNING:
                summaryLabel.setText(item.file.getName() + " — сканирование…");
                fixBtn.setEnabled(false);
                break;
            case ERROR:
                tableModel.setFrames(java.util.Collections.emptyList());
                summaryLabel.setText(item.file.getName() + " — ошибка: " + item.errorMessage);
                fixBtn.setEnabled(false);
                break;
            case DONE:
                showScanResult(item);
                break;
        }
    }

    private void showScanResult(QueueItem item) {
        ScanResult scan = item.scanResult;
        tableModel.setFrames(scan.frames);
        int bad = scan.badCount();
        int shallow = scan.shallowCount();
        summaryLabel.setText(String.format(
                "%s | Кадров: %d | Битых: %d | Поверхностно проверенных: %d | Кодек: %s | Трек: %dx%d | Режим: %s%s",
                item.file.getName(), scan.frames.size(), bad, shallow,
                scan.videoTrack.codecKind.label(), scan.videoTrack.width, scan.videoTrack.height,
                item.lastMode == ScanEngine.Mode.DEEP ? "углублённый" : "быстрый",
                item.repaired ? " | исправлен" : ""));
        fixBtn.setEnabled(bad > 0);
    }

    // ---- queue scanning ------------------------------------------------------------------------

    private void runQueueScan() {
        if (queueRunning) return;
        List<QueueItem> toProcess = new ArrayList<>();
        for (int i = 0; i < queueModel.size(); i++) {
            QueueItem it = queueModel.get(i);
            if (it.status == QueueItem.Status.PENDING) toProcess.add(it);
        }
        if (toProcess.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Нет файлов, ожидающих сканирования.", "Очередь пуста", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        ScanEngine.Mode mode = deepModeRadio.isSelected() ? ScanEngine.Mode.DEEP : ScanEngine.Mode.FAST;
        String ffmpegForThisRun = ffmpegPath;

        queueRunning = true;
        setQueueControlsEnabled(false);

        SwingWorker<Void, Object[]> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (int i = 0; i < toProcess.size(); i++) {
                    QueueItem item = toProcess.get(i);
                    publish(new Object[]{"start", item, i + 1, toProcess.size()});
                    item.status = QueueItem.Status.SCANNING;
                    publish(new Object[]{"repaint"});
                    try {
                        ScanResult result = ScanEngine.scan(item.file, mode, ffmpegForThisRun, (done, total) ->
                                publish(new Object[]{"progress", total <= 0 ? 0 : Math.min(100, done * 100 / total)}));
                        item.scanResult = result;
                        item.lastMode = mode;
                        item.status = QueueItem.Status.DONE;
                    } catch (Exception ex) {
                        item.status = QueueItem.Status.ERROR;
                        item.errorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    }
                    publish(new Object[]{"repaint"});
                }
                return null;
            }

            @Override
            protected void process(List<Object[]> chunks) {
                for (Object[] c : chunks) {
                    switch ((String) c[0]) {
                        case "start":
                            QueueItem item = (QueueItem) c[1];
                            summaryLabel.setText(String.format("Сканирование файла %d из %d: %s", c[2], c[3], item.file.getName()));
                            progressBar.setValue(0);
                            break;
                        case "progress":
                            progressBar.setValue((Integer) c[1]);
                            break;
                        case "repaint":
                            queueList.repaint();
                            if (selected() != null) updateSelectionDependentUi();
                            break;
                        default:
                            break;
                    }
                }
            }

            @Override
            protected void done() {
                queueRunning = false;
                setQueueControlsEnabled(true);
                queueList.repaint();
                updateSelectionDependentUi();
                summaryLabel.setText("Сканирование очереди завершено.");
            }
        };
        worker.execute();
    }

    private void setQueueControlsEnabled(boolean enabled) {
        addFilesBtn.setEnabled(enabled);
        removeBtn.setEnabled(enabled);
        scanQueueBtn.setEnabled(enabled);
        fastModeRadio.setEnabled(enabled);
        deepModeRadio.setEnabled(enabled && ffmpegPath != null);
    }

    // ---- fix -------------------------------------------------------------------------------------

    private void runFix() {
        QueueItem item = selected();
        if (item == null || item.scanResult == null || item.scanResult.badCount() == 0) return;
        File currentFile = item.file;
        ScanResult currentScan = item.scanResult;

        JFileChooser fc = new JFileChooser();
        String base = currentFile.getName();
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : ".mov";
        fc.setSelectedFile(new File(currentFile.getParentFile(), stem + "_fixed" + ext));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File outFile = fc.getSelectedFile();

        boolean useGeneration = generateStrategyRadio.isSelected();
        String ffmpegForThisFix = ffmpegPath;

        fixBtn.setEnabled(false);
        summaryLabel.setText(useGeneration ? "Генерация кадров…" : "Исправление и запись файла…");
        progressBar.setIndeterminate(!useGeneration);
        progressBar.setValue(0);

        SwingWorker<RepairSummary, Integer> worker = new SwingWorker<>() {
            private int generatedCount = 0;

            @Override
            protected RepairSummary doInBackground() throws Exception {
                if (useGeneration && ffmpegForThisFix != null) {
                    generatedCount = FrameGenerator.generateForScan(currentFile, currentScan.videoTrack, ffmpegForThisFix, currentScan,
                            (done, total) -> setProgress(total <= 0 ? 0 : Math.min(100, done * 100 / total)));
                }
                RepairSummary summary = Mp4Repairer.repair(currentFile, currentScan, outFile);
                File report = new File(outFile.getParentFile(), stripExt(outFile.getName()) + "_report.txt");
                ReportWriter.write(report, currentFile, currentScan, true);
                return summary;
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                fixBtn.setEnabled(true);
                try {
                    RepairSummary s = get();
                    item.repaired = true;
                    showScanResult(item);
                    int duplicated = s.framesReplaced - generatedCount;
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Сохранено: " + outFile.getAbsolutePath() +
                                    "\nЗаменено кадров: " + s.framesReplaced +
                                    (useGeneration ? "\n  из них сгенерировано интерполяцией: " + generatedCount +
                                            (duplicated > 0 ? "\n  дублированием (генерация недоступна/не удалась): " + duplicated : "") : "") +
                                    (s.framesUnrepairable > 0 ? "\nНе удалось найти замену для: " + s.framesUnrepairable + " кадров" : "") +
                                    "\nОтчёт сохранён рядом с файлом (*_report.txt).",
                            "Готово", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    showError("Ошибка исправления файла", ex);
                    summaryLabel.setText("Ошибка исправления.");
                }
            }
        };
        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setValue((Integer) evt.getNewValue());
            }
        });
        worker.execute();
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void showError(String title, Exception ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        JOptionPane.showMessageDialog(this, cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                title, JOptionPane.ERROR_MESSAGE);
    }
}
