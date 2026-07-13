package dxvfix.settings;

import java.util.List;
import java.util.prefs.Preferences;

/**
 * Persists the user's language, theme and UI scale choice (Windows registry-backed via
 * {@link Preferences}, same mechanism {@code ShowWatchPanel} already uses for its last-picked
 * directory). Language takes effect on next launch -- see {@link dxvfix.i18n.Messages}, which
 * reads {@link #getLanguageCode()} once at class-init; theme and scale both apply live via
 * {@code dxvfix.theme.ThemeManager}.
 */
public final class AppSettings {

    public enum Theme { LIGHT, DARK, SYSTEM }

    public record Language(String code, String nativeName) {
    }

    public static final List<Language> SUPPORTED_LANGUAGES = List.of(
            new Language("ru", "Русский"),
            new Language("en", "English"),
            new Language("de", "Deutsch"),
            new Language("fr", "Français"),
            new Language("zh", "中文")
    );

    /** Preset UI scale percentages offered in Settings -- covers typical low-vision accessibility needs. */
    public static final List<Integer> SUPPORTED_UI_SCALES = List.of(100, 125, 150, 175, 200);

    private static final Preferences PREFS = Preferences.userNodeForPackage(AppSettings.class);
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_THEME = "theme";
    private static final String KEY_UI_SCALE = "uiScalePercent";
    private static final String DEFAULT_LANGUAGE = "ru";
    private static final Theme DEFAULT_THEME = Theme.SYSTEM;
    private static final int DEFAULT_UI_SCALE = 100;

    private AppSettings() {
    }

    public static String getLanguageCode() {
        return PREFS.get(KEY_LANGUAGE, DEFAULT_LANGUAGE);
    }

    public static void setLanguageCode(String code) {
        PREFS.put(KEY_LANGUAGE, code);
    }

    public static Theme getTheme() {
        try {
            return Theme.valueOf(PREFS.get(KEY_THEME, DEFAULT_THEME.name()));
        } catch (IllegalArgumentException e) {
            return DEFAULT_THEME;
        }
    }

    public static void setTheme(Theme theme) {
        PREFS.put(KEY_THEME, theme.name());
    }

    public static int getUiScalePercent() {
        int value = PREFS.getInt(KEY_UI_SCALE, DEFAULT_UI_SCALE);
        return value > 0 ? value : DEFAULT_UI_SCALE;
    }

    public static void setUiScalePercent(int percent) {
        PREFS.putInt(KEY_UI_SCALE, percent);
    }
}
