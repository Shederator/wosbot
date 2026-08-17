package dev.frostguard.engine.ranking.capture;

import dev.frostguard.engine.emulator.EmulatorType;

import java.util.Locale;

/** Describes whether the first ranking traffic-capture backend can run. */
public record AllianceRankingCaptureSupport(boolean supported, String message) {

    public static AllianceRankingCaptureSupport evaluate(String operatingSystem, EmulatorType emulatorType) {
        String os = operatingSystem == null ? "" : operatingSystem.toLowerCase(Locale.ROOT);
        if (!os.contains("windows")) {
            return unavailable("Alliance ranking traffic capture is currently only available on Windows with MuMu Player.");
        }
        if (emulatorType != EmulatorType.MUMU) {
            return unavailable("Alliance ranking traffic capture is currently only available for MuMu Player on Windows.");
        }
        return new AllianceRankingCaptureSupport(true,
                "Game ranking collection is available for MuMu Player on Windows.");
    }

    private static AllianceRankingCaptureSupport unavailable(String message) {
        return new AllianceRankingCaptureSupport(false, message);
    }
}
