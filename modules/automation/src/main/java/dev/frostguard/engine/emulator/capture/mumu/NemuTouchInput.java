package dev.frostguard.engine.emulator.capture.mumu;

import dev.frostguard.engine.emulator.capture.ScreenCaptureException;

/** False permits ADB fallback only when no native gesture command was sent. */
public interface NemuTouchInput {
    boolean tryTouch(String instance, int x, int y, int endX, int endY, int durationMs)
            throws ScreenCaptureException, InterruptedException;
}
