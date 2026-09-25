package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.AreaData;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** One rally-list card parsed from a single fresh frame. */
record BearRallyCandidate(
        AreaData joinButtonArea,
        int rowY,
        boolean bearTarget,
        JoinButton joinButton,
        int currentMembers,
        int maxMembers,
        long currentTroops,
        long maxTroops,
        Duration countdown,
        Instant observedAt) {

    enum JoinButton { GREEN, GREY, UNKNOWN }

    BearRallyCandidate {
        Objects.requireNonNull(joinButton, "joinButton");
        Objects.requireNonNull(countdown, "countdown");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    long remainingCapacity() {
        return Math.max(0, maxTroops - currentTroops);
    }

    boolean accepts(long formationTroops) {
        return joinButtonArea != null
                && bearTarget
                && joinButton == JoinButton.GREEN
                && currentMembers >= 0
                && maxMembers > 0
                && currentMembers < maxMembers
                && currentTroops >= 0
                && maxTroops > 0
                && currentTroops <= maxTroops
                && formationTroops >= 0
                && remainingCapacity() >= formationTroops
                && !countdown.isZero()
                && !countdown.isNegative();
    }

    String stableKey() {
        long completionBucket;
        try {
            completionBucket = Math.floorDiv(observedAt.plus(countdown).getEpochSecond(), 10);
        } catch (DateTimeException | ArithmeticException invalid) {
            completionBucket = -1;
        }
        return "row=" + rowY + ":" + currentMembers + "/" + maxMembers + ":"
                + currentTroops + "/" + maxTroops + ":" + completionBucket;
    }
}
