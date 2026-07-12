package dxvfix;

import dxvfix.gui.LicenseGateDialog;
import dxvfix.gui.MainFrame;
import dxvfix.theme.ThemeManager;

import javax.swing.*;

public final class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ThemeManager.applyAtStartup();
            if (!LicenseGateDialog.ensureLicensed()) {
                System.exit(0);
                return;
            }
            new MainFrame().setVisible(true);
        });
    }
}
