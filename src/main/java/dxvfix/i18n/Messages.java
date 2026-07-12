package dxvfix.i18n;

import dxvfix.settings.AppSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Looks up UI strings from {@code src/main/resources/i18n/messages_<code>.properties}, loaded
 * once at startup for whichever language {@link AppSettings#getLanguageCode()} reports --
 * language changes apply on next launch, not live (see {@code SettingsDialog}).
 * <p>
 * Properties are read explicitly as UTF-8 via {@code Properties.load(Reader)} rather than
 * relying on {@link java.util.ResourceBundle}'s default ISO-8859-1 handling of {@code .properties}
 * files, so translators can write Cyrillic/German/French/Chinese text directly instead of
 * native2ascii-escaping it.
 * <p>
 * Placeholder substitution is a plain {@code {0}}, {@code {1}}, ... string replace rather than
 * {@link java.text.MessageFormat}, deliberately: MessageFormat treats a bare apostrophe as a
 * quoting character (so "l'application" would need to be written "l''application" in every
 * translated string that takes an argument), which is an easy mistake for a translator to make
 * silently. None of this app's placeholders need MessageFormat's locale-aware number/date
 * formatting, so the simpler, safer substitution is enough.
 */
public final class Messages {

    private static final String BASE_PATH = "/i18n/messages";
    private static final String FALLBACK_LANGUAGE = "en";

    private static final Properties FALLBACK = load(FALLBACK_LANGUAGE);
    private static final Properties ACTIVE = resolveActive();

    private Messages() {
    }

    public static String get(String key) {
        String value = ACTIVE.getProperty(key);
        if (value == null) value = FALLBACK.getProperty(key);
        return value != null ? value : key;
    }

    public static String get(String key, Object... args) {
        String template = get(key);
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    }

    private static Properties resolveActive() {
        String code = AppSettings.getLanguageCode();
        return code.equals(FALLBACK_LANGUAGE) ? FALLBACK : load(code);
    }

    private static Properties load(String languageCode) {
        String resourcePath = BASE_PATH + "_" + languageCode + ".properties";
        try (InputStream is = Messages.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                if (languageCode.equals(FALLBACK_LANGUAGE)) {
                    throw new IllegalStateException("Missing bundled fallback resource: " + resourcePath);
                }
                return load(FALLBACK_LANGUAGE);
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            return props;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + resourcePath, e);
        }
    }
}
