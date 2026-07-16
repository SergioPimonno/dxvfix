package dxvfix.util;

import java.util.Locale;

/** Single place to branch on host OS -- everything else should check these flags, not read {@code os.name} itself. */
public final class Platform {

    public static final boolean WINDOWS;
    public static final boolean MAC;

    static {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        WINDOWS = os.contains("win");
        MAC = os.contains("mac") || os.contains("darwin");
    }

    private Platform() {
    }
}
