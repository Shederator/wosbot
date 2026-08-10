package dev.frostguard.engine.service;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

import java.io.IOException;
import java.util.Objects;
import net.sourceforge.tess4j.TesseractException;

/**
 * Bridges the Frostguard OCR pipeline to a specific emulator instance
 * managed by an {@link EmulatorController}.  Each bridge is bound to a
 * single emulator identifier at construction time, so task code can call
 * OCR methods without threading the device id through every invocation.
 *
 * <p>Internally delegates all region-capture and text-extraction work
 * to the controller's {@code readText} family of methods.
 */
public final class BotOcrEngine implements ResilientOcrExecutor.TextExtractor {

    private final EmulatorController controller;
    private final String boundDevice;

    /**
     * Constructs a bridge for the given controller and device.
     *
     * @param controller  emulator controller that owns the ADB session
     * @param deviceId    emulator instance identifier used in ADB commands
     */
    public BotOcrEngine(EmulatorController controller, String deviceId) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.boundDevice = Objects.requireNonNull(deviceId, "deviceId");
    }

    @Override
    public String extractText(TesseractSettingsData tessConfig, PointData topLeft, PointData bottomRight)
            throws IOException, TesseractException {
        return hasCustomConfig(tessConfig)
                ? controller.readText(boundDevice, topLeft, bottomRight, tessConfig)
                : controller.readText(boundDevice, topLeft, bottomRight);
    }

    /** Returns the device this bridge is bound to. */
    public String getBoundDevice() {
        return boundDevice;
    }

    private static boolean hasCustomConfig(TesseractSettingsData cfg) {
        return cfg != null;
    }
}
