package dev.frostguard.engine.emulator;

import org.opencv.core.Mat;

// Abstract frame provider that streams live video from an Android emulator.
// Concrete implementations bind to scrcpy or screenrecord transports.
public abstract class VideoStreamCapture {

    protected volatile boolean running;
    protected volatile String  lastError;
    protected volatile int     frameCount;

    public abstract void start();
    public abstract void stop();
    public abstract Mat  grabFrame();

    public Mat waitForFrame(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Mat f = grabFrame();
            if (f != null) return f;
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    public boolean isRunning()          { return running; }
    public String  getLastError()       { return lastError; }
    public int     getDecodedFrameCount() { return frameCount; }
}
