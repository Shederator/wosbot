package dev.frostguard.tasks.city;

import java.time.Duration;

final class ResearchTimerPolicy {

    private static final Duration MINIMUM_RECHECK_DELAY = Duration.ofMinutes(1);

    private ResearchTimerPolicy() {
    }

    static Duration recheckDelay(Duration remainingTime) {
        // Alliance Help and speedups can finish research well before the OCR ETA.
        // Rechecking halfway even for short timers prevents the research queue from
        // sitting idle until a stale full-duration appointment, while the one-minute
        // floor avoids a tight retry loop near completion.
        Duration halfTime = remainingTime.dividedBy(2);
        return halfTime.compareTo(MINIMUM_RECHECK_DELAY) < 0
                ? MINIMUM_RECHECK_DELAY
                : halfTime;
    }
}
