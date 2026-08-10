package dev.frostguard.engine.emulator;

import org.opencv.core.Mat;
import java.io.File;
import java.util.Arrays;

// Scrcpy-based frame capture (stub — pipeline not yet connected).
public class ScrcpyStreamCapture extends VideoStreamCapture {

    private final String adb, serial, server, ffmpeg;
    private final int w, h, bitrate, fps;

    public ScrcpyStreamCapture(String adb, String serial, String server, String ffmpeg,
                               int w, int h, int bitrate, int fps) {
        this.adb = adb; this.serial = serial; this.server = server; this.ffmpeg = ffmpeg;
        this.w = w; this.h = h; this.bitrate = bitrate; this.fps = fps;
    }

    public static String findScrcpyServer() {
        File dir = new File("lib/scrcpy");
        if (!dir.isDirectory()) return null;
        return Arrays.stream(dir.listFiles())
                .filter(f -> f.getName().startsWith("scrcpy-server"))
                .map(File::getAbsolutePath).findFirst().orElse(null);
    }

    public static String findFfmpeg() {
        File f = new File("lib/ffmpeg/ffmpeg.exe");
        return f.isFile() ? f.getAbsolutePath() : null;
    }

    @Override public void start()     { running = true; }
    @Override public void stop()      { running = false; }
    @Override public Mat  grabFrame() { return null; }
}
