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
        Instant observedAt,
        long visualIdentity) {

    /** Leaves enough time to open formation, verify the saved flag, and win the deploy race. */
    static final Duration MIN_SAFE_DEPLOY_COUNTDOWN = Duration.ofSeconds(30);

    enum JoinButton { GREEN, GREY, UNKNOWN }

    BearRallyCandidate {
        Objects.requireNonNull(joinButton, "joinButton");
        Objects.requireNonNull(countdown, "countdown");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    BearRallyCandidate(
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
        this(joinButtonArea, rowY, bearTarget, joinButton, currentMembers, maxMembers,
                currentTroops, maxTroops, countdown, observedAt, 0L);
    }

    long remainingCapacity() {
        return Math.max(0, maxTroops - currentTroops);
    }

    boolean accepts(long formationTroops) {
        return accepts(formationTroops, observedAt);
    }

    boolean accepts(long formationTroops, Instant evaluatedAt) {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Duration elapsed = evaluatedAt.isAfter(observedAt)
                ? Duration.between(observedAt, evaluatedAt)
                : Duration.ZERO;
        Duration effectiveCountdown = countdown.minus(elapsed);
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
                && effectiveCountdown.compareTo(MIN_SAFE_DEPLOY_COUNTDOWN) >= 0;
    }

    String stableKey() {
        long completionBucket;
        try {
            completionBucket = Math.floorDiv(observedAt.plus(countdown).getEpochSecond(), 10);
        } catch (DateTimeException | ArithmeticException invalid) {
            completionBucket = -1;
        }
        // Current members/troops are intentionally excluded: both change while the same rally is
        // filling and must not make a just-rejected rally look new on the next scan.
        return "row=" + rowY + ":" + maxMembers + ":" + maxTroops + ":"
                + completionBucket + ":visual=" + Long.toUnsignedString(visualIdentity, 16);
    }
}
