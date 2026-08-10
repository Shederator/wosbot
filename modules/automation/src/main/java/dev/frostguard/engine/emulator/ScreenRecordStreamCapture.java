package dev.frostguard.engine.emulator;

import org.opencv.core.Mat;

// Android screenrecord-based frame capture through FFmpeg (stub — not yet wired).
public class ScreenRecordStreamCapture extends VideoStreamCapture {

    private final String adb, serial, ffmpeg;
    private final int w, h, bitrate, fps;

    public ScreenRecordStreamCapture(String adb, String serial, String ffmpeg,
                                     int w, int h, int bitrate, int fps) {
        this.adb = adb; this.serial = serial; this.ffmpeg = ffmpeg;
        this.w = w; this.h = h; this.bitrate = bitrate; this.fps = fps;
    }

    @Override public void start()     { running = true; }
    @Override public void stop()      { running = false; }
    @Override public Mat  grabFrame() { return null; }
}
