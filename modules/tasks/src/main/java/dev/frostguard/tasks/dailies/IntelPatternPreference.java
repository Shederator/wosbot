package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.TemplatesEnum;

final class IntelPatternPreference {

    private boolean fireCrystalFirst;

    TemplatesEnum[] order(TemplatesEnum normal, TemplatesEnum... fireCrystal) {
        TemplatesEnum[] ordered = new TemplatesEnum[fireCrystal.length + 1];
        if (fireCrystalFirst) {
            System.arraycopy(fireCrystal, 0, ordered, 0, fireCrystal.length);
            ordered[ordered.length - 1] = normal;
        } else {
            ordered[0] = normal;
            System.arraycopy(fireCrystal, 0, ordered, 1, fireCrystal.length);
        }
        return ordered;
    }

    boolean recordMatch(TemplatesEnum template) {
        if (!fireCrystalFirst && isFireCrystal(template)) {
            fireCrystalFirst = true;
            return true;
        }
        return false;
    }

    boolean isFireCrystalFirst() {
        return fireCrystalFirst;
    }

    void reset() {
        fireCrystalFirst = false;
    }

    private static boolean isFireCrystal(TemplatesEnum template) {
        return switch (template) {
            case INTEL_BEAST_GRAYSCALE_FC,
                    INTEL_BEAST_GRAYSCALE_FC1,
                    INTEL_SURVIVOR_GRAYSCALE_FC,
                    INTEL_JOURNEY_GRAYSCALE_FC -> true;
            default -> false;
        };
    }
}
