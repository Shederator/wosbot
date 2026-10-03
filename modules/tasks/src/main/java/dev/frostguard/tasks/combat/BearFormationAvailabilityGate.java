package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.MarchSlotState;
import java.time.Instant;
import java.util.List;

/** Prevents repeated plus/formation transactions while every configured formation is occupied. */
final class BearFormationAvailabilityGate {

    private Instant blockedUntil;

    boolean blocked(Instant now) {
        return blockedUntil != null && now.isBefore(blockedUntil);
    }

    void clearIfDue(Instant now) {
        if (blockedUntil != null && !now.isBefore(blockedUntil)) blockedUntil = null;
    }

    Instant block(
            Instant now,
            Instant eventEnd,
            Instant ownReturnDeadline,
            List<MarchSlotState> slots) {
        Instant earliest = (slots == null ? List.<MarchSlotState>of() : slots).stream()
                .filter(MarchSlotState::hasExactReleaseCountdown)
                .map(slot -> now.plus(slot.countdown()))
                .min(Instant::compareTo)
                .orElse(now.plusSeconds(5));
        if (ownReturnDeadline != null && ownReturnDeadline.isBefore(earliest)) {
            earliest = ownReturnDeadline;
        }
        blockedUntil = eventEnd.isBefore(earliest) ? eventEnd : earliest;
        return blockedUntil;
    }
}
