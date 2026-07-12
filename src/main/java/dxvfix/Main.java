package dxvfix;

import dxvfix.gui.LicenseGateDialog;
import dxvfix.gui.MainFrame;

import javax.swing.*;

public final class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            if (!LicenseGateDialog.ensureLicensed()) {
                System.exit(0);
                return;
            }
            new MainFrame().setVisible(true);
        });
    }
}
