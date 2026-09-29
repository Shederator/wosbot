package dev.frostguard.tasks.events;

import java.time.LocalDateTime;

/**
 * One extra menu visit after a tab miss. Cleared when the tab is found or when
 * the extra visit has been spent.
 */
public final class EventMenuRetryState {

    private boolean extraVisitUsed;

    public record Choice(LocalDateTime at, boolean resting) {
    }

    public void found() {
        extraVisitUsed = false;
    }

    public Choice choose(LocalDateTime now, LocalDateTime rest) {
        boolean resting = extraVisitUsed;
        extraVisitUsed = EventPeriodVisit.rememberExtraVisit(extraVisitUsed);
        return new Choice(resting ? rest : EventPeriodVisit.retryAt(now), resting);
    }
}
