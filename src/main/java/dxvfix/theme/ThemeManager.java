package dxvfix.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import dxvfix.settings.AppSettings;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Applies FlatLaf's light or dark look and feel based on {@link AppSettings.Theme}. SYSTEM is
 * resolved against the Windows "app mode" light/dark registry setting at the moment it's
 * applied -- there's no live OS-theme-change listener, so an OS-level switch is picked up the
 * next time the theme is (re)applied (Settings dialog OK, or app restart), not instantly.
 */
public final class ThemeManager {

    private ThemeManager() {
    }

    /** Call once, before any Swing component is created -- the first line of main(). */
    public static void applyAtStartup() {
        apply(AppSettings.getTheme());
    }

    /** Swaps the look and feel live and repaints every currently open window. */
    public static void applyLive(AppSettings.Theme theme) {
        apply(theme);
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
        }
    }

    private static void apply(AppSettings.Theme theme) {
        boolean dark = switch (theme) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> isWindowsDarkModeActive();
        };
        try {
            UIManager.setLookAndFeel(dark ? new FlatDarkLaf() : new FlatLightLaf());
        } catch (UnsupportedLookAndFeelException e) {
            // Theming is cosmetic -- never worth crashing startup over. Whatever L&F was
            // already active (Swing's default) stays in effect.
        }
    }

    private static boolean isWindowsDarkModeActive() {
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("AppsUseLightTheme")) {
                        return line.trim().endsWith("0x0");
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) {
            // Not on Windows, registry key missing (older Windows without light/dark mode),
            // reg.exe unavailable, etc. -- default to light.
        }
        return false;
    }
}
