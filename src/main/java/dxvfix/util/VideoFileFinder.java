package dxvfix.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Recursively finds .mov/.mp4 files under a directory, shared by the queue's folder-drop and ShowWatcher. */
public final class VideoFileFinder {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mov", "mp4");

    private VideoFileFinder() {
    }

    public static List<File> find(File root) {
        return find(root, Set.of());
    }

    /** Same as {@link #find(File)}, but skips any subdirectory whose name matches one in {@code excludedDirNames} (case-insensitive). */
    public static List<File> find(File root, Set<String> excludedDirNames) {
        List<File> out = new ArrayList<>();
        collect(root, excludedDirNames, out);
        return out;
    }

    private static void collect(File dir, Set<String> excludedDirNames, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File c : children) {
            if (c.isDirectory()) {
                if (excludedDirNames.contains(c.getName().toLowerCase(Locale.ROOT))) continue;
                collect(c, excludedDirNames, out);
            } else if (isVideoFile(c)) {
                out.add(c);
            }
        }
    }

    public static boolean isVideoFile(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && VIDEO_EXTENSIONS.contains(name.substring(dot + 1));
    }
}
