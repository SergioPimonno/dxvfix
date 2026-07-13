package dxvfix.gui;

import dxvfix.AppVersion;
import dxvfix.i18n.Messages;

import javax.swing.JOptionPane;
import java.awt.Component;

/** "About" screen: app name, version, host OS and author -- reached from the menu bar. */
final class AboutDialog {

    private AboutDialog() {
    }

    static void show(Component parent) {
        String osInfo = System.getProperty("os.name") + " " + System.getProperty("os.version");
        String text = "DXV Frame Doctor\n\n"
                + Messages.get("about.version", AppVersion.VERSION) + "\n"
                + Messages.get("about.os", osInfo) + "\n"
                + Messages.get("about.author", AppVersion.AUTHOR);
        JOptionPane.showMessageDialog(parent, text, Messages.get("mainframe.menu.about"), JOptionPane.INFORMATION_MESSAGE);
    }
}
