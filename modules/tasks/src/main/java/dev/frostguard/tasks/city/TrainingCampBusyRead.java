package dev.frostguard.tasks.city;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.tasks.city.ConstructionBlockerRegistry.Consumer;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Busy training camp opened by the construction guide. A positive needs a camp
 * title, a full clock on that building, and no upgrade control. A troop word
 * reserves that camp. The word camp alone reserves all three. The September 2026
 * frame read LancerCamp and 07:41:01, so two matches stay unknown.
 */
final class TrainingCampBusyRead {

    static final AreaData CLOCK_AREA = new AreaData(new PointData(300, 640), new PointData(470, 700));

    private static final Set<Consumer> ALL_CAMPS = EnumSet.of(
            Consumer.INFANTRY, Consumer.LANCER, Consumer.MARKSMAN);

    /** Hours may pass 23. Minutes and seconds stay within a clock face. */
    private static final Pattern FULL_CLOCK = Pattern.compile("(\\d{2}):([0-5]\\d):([0-5]\\d)");

    record Decision(Set<Consumer> camps, Duration remaining) {
    }

    private TrainingCampBusyRead() {
    }

    static Decision positive(String buildingName, String clockText, boolean upgradeControlPresent) {
        if (upgradeControlPresent) {
            return null;
        }
        Set<Consumer> camps = campsFromTitle(buildingName);
        Duration remaining = fullClock(clockText);
        if (camps.isEmpty() || remaining == null) {
            return null;
        }
        return new Decision(camps, remaining);
    }

    static Set<Consumer> campsFromTitle(String buildingName) {
        Consumer troop = UpgradeBuildingsRoutine.identifyTrainingConsumer(buildingName);
        if (troop != null) {
            return EnumSet.of(troop);
        }
        String letters = buildingName == null
                ? ""
                : buildingName.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return letters.contains("camp") ? EnumSet.copyOf(ALL_CAMPS) : EnumSet.noneOf(Consumer.class);
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
