package dxvfix.settings;

import java.util.List;
import java.util.prefs.Preferences;

/**
 * Persists the user's language and theme choice (Windows registry-backed via
 * {@link Preferences}, same mechanism {@code ShowWatchPanel} already uses for its last-picked
 * directory). Language takes effect on next launch -- see {@link dxvfix.i18n.Messages}, which
 * reads {@link #getLanguageCode()} once at class-init; theme applies live via
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

    private static final Preferences PREFS = Preferences.userNodeForPackage(AppSettings.class);
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_THEME = "theme";
    private static final String DEFAULT_LANGUAGE = "ru";
    private static final Theme DEFAULT_THEME = Theme.SYSTEM;

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
}
