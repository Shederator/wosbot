package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Locale;

/**
 * Parsed candidate card representation from the alliance rally list during Bear Trap event.
 */
public record BearRallyCandidate(
        PointData joinButtonPoint,
        AreaData joinButtonArea,
        AreaData cardArea,
        String hostName,
        int currentMembers,
        int maxMembers,
        long currentTroops,
        long rallyCapacity,
        long remainingCapacity,
        Duration countdown,
        Instant observedAt,
        boolean isJoinable
) {
    public BearRallyCandidate(PointData joinButtonPoint, AreaData cardArea, String hostName,
            int currentMembers, int maxMembers, long currentTroops, long rallyCapacity,
            long remainingCapacity, Duration countdown, Instant observedAt, boolean isJoinable) {
        this(joinButtonPoint, pointArea(joinButtonPoint), cardArea, hostName, currentMembers,
                maxMembers, currentTroops, rallyCapacity, remainingCapacity, countdown,
                observedAt, isJoinable);
    }

    private static AreaData pointArea(PointData point) {
        return point == null ? null : new AreaData(point, point);
    }

    public String getCandidateKey() {
        String cleanHost = hostName == null || hostName.isBlank()
                ? "unknown"
                : hostName.trim().toLowerCase(Locale.ROOT);
        long completionBucket = completionBucket();
        return cleanHost + ":members=" + currentMembers + "/" + maxMembers
                + ":troops=" + currentTroops + "/" + rallyCapacity
                + ":remaining=" + remainingCapacity + ":completion=" + completionBucket;
    }

    private long completionBucket() {
        if (observedAt == null || countdown == null || countdown.isNegative()) {
            return -1;
        }
        try {
            return Math.floorDiv(observedAt.plus(countdown).getEpochSecond(), 15);
        } catch (DateTimeException | ArithmeticException invalidTime) {
            return -1;
        }
    }
}
