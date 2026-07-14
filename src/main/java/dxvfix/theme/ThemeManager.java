package dxvfix.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import dxvfix.settings.AppSettings;

import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.Font;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Applies FlatLaf's light or dark look and feel based on {@link AppSettings.Theme}, plus a UI
 * scale percentage (default UI font size, scaled) for accessibility -- FlatLaf derives its
 * internal "user scale factor" (which drives icon size, padding, borders, everything) from the
 * default font's point size relative to the platform baseline, so scaling the font up is enough
 * to scale the whole interface, no separate icon-scaling logic needed. SYSTEM theme is resolved
 * against the OS's own light/dark setting (Windows' "app mode" registry key, or macOS's
 * AppsUseLightTheme-equivalent {@code AppleInterfaceStyle} default) at the moment it's applied --
 * there's no live OS-theme-change listener, so an OS-level switch is picked up the next time the
 * theme is (re)applied (Settings dialog OK, or app restart), not instantly.
 * <p>
 * Both theme and scale apply live (unlike language, which needs a restart -- see {@link
 * dxvfix.i18n.Messages}): swapping the look and feel resets {@code UIManager}'s "defaultFont" back
 * to the L&F's own unscaled default, so every apply here re-derives the scaled font from whatever
 * the L&F just installed, rather than compounding scale on top of a previous scale.
 */
public final class ThemeManager {

    private ThemeManager() {
    }

    /** Call once, before any Swing component is created -- the first line of main(). */
    public static void applyAtStartup() {
        apply(AppSettings.getTheme(), AppSettings.getUiScalePercent());
    }

    /** Swaps the look and feel and UI scale live, and repaints every currently open window. */
    public static void applyLive(AppSettings.Theme theme, int scalePercent) {
        apply(theme, scalePercent);
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
            w.revalidate();
            w.repaint();
        }
    }

    private static void apply(AppSettings.Theme theme, int scalePercent) {
        boolean dark = switch (theme) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> isSystemDarkModeActive();
        };
        LookAndFeel laf = dark ? new FlatDarkLaf() : new FlatLightLaf();
        try {
            UIManager.setLookAndFeel(laf);
        } catch (UnsupportedLookAndFeelException e) {
            // Theming is cosmetic -- never worth crashing startup over. Whatever L&F was
            // already active (Swing's default) stays in effect.
            return;
        }

        // Read the base size from the L&F instance's own fresh defaults, NOT from
        // UIManager.getFont("defaultFont") -- UIManager.put() overrides (from a *previous*
        // apply() call) live in a layer that survives setLookAndFeel(), so reading back through
        // UIManager here would pick up an already-scaled size and compound it on every call
        // (verified: 150% followed by 200% produced 300%, not 200%, before this fix).
        Font baseFont = laf.getDefaults().getFont("defaultFont");
        if (baseFont != null) {
            float scaledSize = baseFont.getSize2D() * scalePercent / 100f;
            UIManager.put("defaultFont", baseFont.deriveFont(scaledSize));
        }
    }

    private static boolean isSystemDarkModeActive() {
        if (dxvfix.util.Platform.MAC) return isMacDarkModeActive();
        return isWindowsDarkModeActive();
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

    /**
     * macOS only writes the {@code AppleInterfaceStyle} default when Dark mode is active -- it's
     * simply absent in Light mode, hence checking for a non-empty "Dark" line rather than parsing
     * a value the key won't even have.
     */
    private static boolean isMacDarkModeActive() {
        try {
            Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null && line.trim().equalsIgnoreCase("Dark")) {
                    return true;
                }
            }
            p.waitFor();
        } catch (Exception ignored) {
            // Key absent (Light mode) or `defaults` unavailable -- default to light.
        }
        return false;
    }
}
