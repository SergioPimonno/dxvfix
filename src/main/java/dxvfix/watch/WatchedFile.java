package dxvfix.watch;

import java.io.File;
import java.time.LocalDateTime;

/** One corrupted file tracked by {@link ShowWatcher}, and its repair status if auto-fix is on. */
public final class WatchedFile {
    public final File sourceFile;
    public int badCount;
    public int totalFrames;
    public LocalDateTime detectedAt;
    public boolean fixed;
    public volatile boolean fixing;
    public File fixedFile;
    public LocalDateTime fixedAt;
    public String errorMessage;

    public WatchedFile(File sourceFile) {
        this.sourceFile = sourceFile;
    }
}
