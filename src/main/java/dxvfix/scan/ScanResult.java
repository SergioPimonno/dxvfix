package dxvfix.scan;

import dxvfix.mp4.Mp4Container;
import dxvfix.mp4.TrackInfo;

import java.util.ArrayList;
import java.util.List;

public final class ScanResult {
    public final Mp4Container container;
    public final TrackInfo videoTrack;
    public final List<FrameReport> frames = new ArrayList<>();

    public ScanResult(Mp4Container container, TrackInfo videoTrack) {
        this.container = container;
        this.videoTrack = videoTrack;
    }

    public int badCount() {
        int n = 0;
        for (FrameReport f : frames) if (f.isBad()) n++;
        return n;
    }

    public int shallowCount() {
        int n = 0;
        for (FrameReport f : frames) {
            if (f.result.status == FrameCheckResult.Status.OK_SHALLOW) n++;
        }
        return n;
    }
}
