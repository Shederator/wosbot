package dev.frostguard.tasks.city;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.tasks.city.ConstructionBlockerRegistry.Consumer;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Busy training camp opened by the construction guide. A positive needs the camp
 * name, a full clock on that building, and no upgrade control. The September 2026
 * frame read all three exactly, so two matches stay unknown.
 */
final class TrainingCampBusyRead {

    static final AreaData CLOCK_AREA = new AreaData(new PointData(300, 640), new PointData(470, 700));

    /** Hours may pass 23. Minutes and seconds stay within a clock face. */
    private static final Pattern FULL_CLOCK = Pattern.compile("(\\d{2}):([0-5]\\d):([0-5]\\d)");

    record Decision(Consumer camp, Duration remaining) {
    }

    private TrainingCampBusyRead() {
    }

    static Decision positive(String buildingName, String clockText, boolean upgradeControlPresent) {
        if (upgradeControlPresent) {
            return null;
        }
        Consumer camp = UpgradeBuildingsRoutine.identifyTrainingConsumer(buildingName);
        Duration remaining = fullClock(clockText);
        if (camp == null || remaining == null) {
            return null;
        }
        return new Decision(camp, remaining);
    }

    static Duration fullClock(String text) {
        if (text == null) {
            return null;
        }
        Matcher match = FULL_CLOCK.matcher(text.trim());
        if (!match.matches()) {
            return null;
        }
        return Duration.ofHours(Long.parseLong(match.group(1)))
                .plusMinutes(Long.parseLong(match.group(2)))
                .plusSeconds(Long.parseLong(match.group(3)));
    }
}
