package dxvfix.gui;

import dxvfix.i18n.Messages;
import dxvfix.license.Fingerprint;
import dxvfix.license.LicenseVerifier;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Blocking startup gate: requires a valid, machine-matched, signed license file to proceed. */
public final class LicenseGateDialog extends JDialog {

    // Placeholder until a dedicated sales/licensing site exists -- swap this for the real URL
    // once it's up. Kept as a single named constant so that's a one-line change later.
    private static final String PURCHASE_URL = "https://github.com/SergioPimonno/dxvfix";

    private boolean approved = false;

    public LicenseGateDialog() {
        super((Frame) null, Messages.get("licenseGate.title"), true);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(new EmptyBorder(16, 16, 16, 16));

        String fingerprint;
        try {
            fingerprint = Fingerprint.compute();
        } catch (Exception e) {
            fingerprint = Messages.get("licenseGate.fingerprintUnknown", e.getMessage());
        }

        JTextArea info = new JTextArea(Messages.get("licenseGate.info"));
        info.setEditable(false);
        info.setOpaque(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        content.add(info, BorderLayout.NORTH);

        JTextField fpField = new JTextField(fingerprint);
        fpField.setEditable(false);
        fpField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        JButton copyBtn = new JButton(Messages.get("licenseGate.copy"));
        String finalFingerprint = fingerprint;
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(finalFingerprint), null);
            copyBtn.setText(Messages.get("licenseGate.copied"));
        });
        JPanel fpPanel = new JPanel(new BorderLayout(6, 6));
        fpPanel.add(fpField, BorderLayout.CENTER);
        fpPanel.add(copyBtn, BorderLayout.EAST);
        content.add(fpPanel, BorderLayout.CENTER);

        JLabel statusLabel = new JLabel(" ");
        statusLabel.setForeground(new Color(180, 40, 40));

        JButton purchaseBtn = new JButton(Messages.get("licenseGate.purchaseLicense"));
        JButton loadBtn = new JButton(Messages.get("licenseGate.loadFile"));
        JButton exitBtn = new JButton(Messages.get("licenseGate.exit"));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(purchaseBtn);
        buttons.add(loadBtn);
        buttons.add(exitBtn);

        JPanel south = new JPanel(new BorderLayout());
        south.add(statusLabel, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        loadBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(Messages.get("licenseGate.filechooser.license"), "lic"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path chosen = fc.getSelectedFile().toPath();
            try {
                Path dest = LicenseVerifier.defaultLicensePath();
                Files.createDirectories(dest.getParent());
                Files.copy(chosen, dest, StandardCopyOption.REPLACE_EXISTING);
                LicenseVerifier.Result result = LicenseVerifier.verify(dest);
                if (result.isValid()) {
                    approved = true;
                    dispose();
                } else {
                    statusLabel.setText(Messages.get("licenseGate.invalid", result.status, result.message));
                }
            } catch (Exception ex) {
                statusLabel.setText(Messages.get("licenseGate.error", ex.getMessage()));
            }
        });
        exitBtn.addActionListener(e -> dispose());
        purchaseBtn.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(new URI(PURCHASE_URL));
            } catch (Exception ex) {
                statusLabel.setText(Messages.get("licenseGate.purchaseFailed", ex.getMessage()));
            }
        });

        setContentPane(content);
        setPreferredSize(new Dimension(560, 260));
        pack();
        setLocationRelativeTo(null);
    }

    /** Returns true if a valid license is present (checked first) or was just loaded and approved. */
    public static boolean ensureLicensed() {
        LicenseVerifier.Result result = LicenseVerifier.verify(LicenseVerifier.defaultLicensePath());
        if (result.isValid()) {
            return true;
        }
        LicenseGateDialog dialog = new LicenseGateDialog();
        dialog.setVisible(true);
        return dialog.approved;
    }
}
