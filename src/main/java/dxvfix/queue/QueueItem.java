package dxvfix.queue;

import dxvfix.scan.ScanEngine;
import dxvfix.scan.ScanResult;

import java.io.File;

/** One file in the batch queue, with its current processing status and (once scanned) results. */
public final class QueueItem {

    public enum Status {
        PENDING, SCANNING, DONE, ERROR
    }

    public final File file;
    public Status status = Status.PENDING;
    public ScanResult scanResult;
    public String errorMessage;
    public ScanEngine.Mode lastMode;
    public boolean repaired;

    public QueueItem(File file) {
        this.file = file;
    }

    public int badCount() {
        return scanResult != null ? scanResult.badCount() : -1;
    }
}
