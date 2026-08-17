package dev.frostguard.engine.service;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

import java.io.IOException;
import java.util.Objects;
import dev.frostguard.vision.ocr.OcrException;

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
    private final boolean reuseLastFrame;

    /**
     * Constructs a bridge for the given controller and device.
     *
     * @param controller  emulator controller that owns the ADB session
     * @param deviceId    emulator instance identifier used in ADB commands
     */
    public BotOcrEngine(EmulatorController controller, String deviceId) {
        this(controller, deviceId, false);
    }

    private BotOcrEngine(EmulatorController controller, String deviceId, boolean reuseLastFrame) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.boundDevice = Objects.requireNonNull(deviceId, "deviceId");
        this.reuseLastFrame = reuseLastFrame;
    }

    @Override
    public String extractText(OcrSettingsData config, PointData topLeft, PointData bottomRight)
            throws IOException, OcrException {
        if (reuseLastFrame) {
            return controller.readText(boundDevice, topLeft, bottomRight, config, true);
        }
        return config != null
                ? controller.readText(boundDevice, topLeft, bottomRight, config)
                : controller.readText(boundDevice, topLeft, bottomRight);
    }

    /** Returns the device this bridge is bound to. */
    public String getBoundDevice() {
        return boundDevice;
    }

    /**
     * Returns an extractor that reads the controller's most recently captured frame.
     * Capture reuse is an automation concern and intentionally does not live in OCR settings.
     */
    public BotOcrEngine reusingLastFrame() {
        return new BotOcrEngine(controller, boundDevice, true);
    }
}
