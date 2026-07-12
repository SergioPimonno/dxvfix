package dxvfix.license.tools;

import dxvfix.license.Fingerprint;
import dxvfix.license.LicenseIssuer;
import dxvfix.license.LicenseRecord;
import dxvfix.license.LicenseVerifier;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.util.prefs.Preferences;

/**
 * Standalone license-issuing tool, deliberately separate from the main app jar: run this on
 * whichever machine holds {@code license_private.key} (never distribute that file) to produce a
 * {@code .lic} file for a given device fingerprint, or to install one directly on this machine
 * for testing.
 */
public final class LicenseAdminGui extends JFrame {

    private static final Preferences PREFS = Preferences.userNodeForPackage(LicenseAdminGui.class);
    private static final String PREF_KEY_PATH = "lastPrivateKeyPath";

    private final JTextField keyPathField = new JTextField();
    private final JTextField fingerprintField = new JTextField();
    private final JTextField labelField = new JTextField();
    private final JTextField expiresField = new JTextField();
    private final JTextArea log = new JTextArea(10, 60);

    public LicenseAdminGui() {
        super("DXV Frame Doctor — выдача лицензий");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(12, 12, 12, 12));

        add(buildForm(), BorderLayout.NORTH);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(log), BorderLayout.CENTER);

        String lastKey = PREFS.get(PREF_KEY_PATH, "");
        if (!lastKey.isBlank() && new File(lastKey).isFile()) {
            keyPathField.setText(lastKey);
        }

        pack();
        setLocationRelativeTo(null);
    }

    private JComponent buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addRow(form, gc, row++, "Приватный ключ (license_private.key):", keyPathField, "Обзор…", this::browseForKey);

        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        form.add(new JLabel("Fingerprint устройства:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        form.add(fingerprintField, gc);
        JButton thisDeviceBtn = new JButton("Это устройство");
        thisDeviceBtn.addActionListener(e -> {
            try {
                fingerprintField.setText(Fingerprint.compute());
            } catch (Exception ex) {
                showLog("Не удалось определить fingerprint этого устройства: " + ex.getMessage());
            }
        });
        gc.gridx = 2; gc.weightx = 0;
        form.add(thisDeviceBtn, gc);
        row++;

        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        form.add(new JLabel("Метка (необязательно):"), gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 1;
        form.add(labelField, gc);
        gc.gridwidth = 1;
        row++;

        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        form.add(new JLabel("Истекает (yyyy-MM-dd, необязательно):"), gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 1;
        form.add(expiresField, gc);
        gc.gridwidth = 1;
        row++;

        JButton saveBtn = new JButton("Сохранить как .lic файл…");
        saveBtn.addActionListener(e -> issue(false));
        JButton installBtn = new JButton("Установить на это устройство");
        installBtn.addActionListener(e -> issue(true));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(saveBtn);
        buttons.add(installBtn);
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 3;
        form.add(buttons, gc);

        return form;
    }

    private interface Runnable0 {
        void run();
    }

    private void addRow(JPanel form, GridBagConstraints gc, int row, String label, JTextField field,
                         String buttonText, Runnable0 onButton) {
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        form.add(new JLabel(label), gc);
        gc.gridx = 1; gc.weightx = 1;
        form.add(field, gc);
        JButton btn = new JButton(buttonText);
        btn.addActionListener(e -> onButton.run());
        gc.gridx = 2; gc.weightx = 0;
        form.add(btn, gc);
    }

    private void browseForKey() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("license_private.key", "key"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            keyPathField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void issue(boolean installLocally) {
        try {
            if (keyPathField.getText().isBlank()) {
                showLog("Укажите файл приватного ключа.");
                return;
            }
            if (fingerprintField.getText().isBlank()) {
                showLog("Укажите fingerprint устройства (или нажмите «Это устройство»).");
                return;
            }
            PrivateKey key = LicenseIssuer.loadPrivateKey(Path.of(keyPathField.getText().trim()));
            PREFS.put(PREF_KEY_PATH, keyPathField.getText().trim());

            LocalDate expires = null;
            String expiresText = expiresField.getText().trim();
            if (!expiresText.isBlank()) {
                expires = LocalDate.parse(expiresText);
            }

            Path outPath;
            if (installLocally) {
                outPath = LicenseVerifier.defaultLicensePath();
                java.nio.file.Files.createDirectories(outPath.getParent());
            } else {
                JFileChooser fc = new JFileChooser();
                String suggested = fingerprintField.getText().trim().replace(":", "") + ".lic";
                fc.setSelectedFile(new File(suggested));
                if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
                outPath = fc.getSelectedFile().toPath();
            }

            LicenseRecord record = LicenseIssuer.issue(key, fingerprintField.getText().trim(),
                    labelField.getText().trim(), LocalDate.now(), expires, outPath);

            showLog("Выдана лицензия: " + outPath.toAbsolutePath());
            showLog("  fingerprint = " + record.fingerprint);
            showLog("  label       = " + record.label);
            showLog("  issued      = " + record.issued);
            showLog("  expires     = " + (record.expires == null ? "(бессрочно)" : record.expires));

            LicenseVerifier.Result check = LicenseVerifier.verify(outPath);
            showLog("Самопроверка подписи: " + check.status +
                    (check.status == LicenseVerifier.Status.FINGERPRINT_MISMATCH
                            ? " (это нормально, если выдавали не для этого компьютера)" : ""));

            if (installLocally) {
                if (check.status == LicenseVerifier.Status.VALID) {
                    showLog("Установлено и подтверждено: это устройство теперь может запускать основное приложение.");
                } else if (check.status == LicenseVerifier.Status.FINGERPRINT_MISMATCH) {
                    showLog("ВНИМАНИЕ: fingerprint не совпадает с этим устройством — введённое значение отличается " +
                            "от реального. Приложение на этом компьютере не запустится.");
                }
            }
        } catch (Exception ex) {
            showLog("Ошибка: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void showLog(String line) {
        log.append(line + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new LicenseAdminGui().setVisible(true);
        });
    }
}
