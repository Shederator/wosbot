package dev.frostguard.tasks.city;

import java.time.Duration;

final class ResearchTimerPolicy {

    private static final Duration MINIMUM_RECHECK_DELAY = Duration.ofMinutes(1);

    // Guards against a garbled OCR read (e.g. "99:99:99") parking the task for months.
    // Deliberately far above any real research length so it never touches a valid timer.
    private static final Duration SANITY_CEILING = Duration.ofDays(7);

    // Small cushion so the recheck lands just after completion rather than racing it
    // and finding the queue still one second from done.
    private static final Duration COMPLETION_MARGIN = Duration.ofSeconds(30);

    private ResearchTimerPolicy() {
    }

    /**
     * Schedules the next look at the Research Center for when the current research
     * actually completes, as read off the screen.
     *
     * <p>History, because this reversed twice: the original code rechecked at half the
     * remaining time, which left a 19h58m research unlooked-at for ~10h (observed
     * 2026-08-06). That was capped at 30 minutes on 2026-08-08, which fixed the idle
     * window but replaced it with blind polling — a 1d17h research got woken 80+ times
     * to be told "still running", and the real completion time was never actually used.
     *
     * <p>Now it schedules to the real time. The case the halving/cap defended against —
     * Alliance Help or a manual speedup finishing research early, so the OCR ETA is
     * stale — is covered instead by the startup full rescan
     * ({@code STARTUP_FULL_RESCAN_BOOL}): any real Start re-opens the screen and
     * re-derives this from scratch. That is a better safety net than polling, because
     * it costs nothing while research is genuinely running.</p>
     */
    static Duration recheckDelay(Duration remainingTime) {
        if (remainingTime == null || remainingTime.isNegative()) {
            return MINIMUM_RECHECK_DELAY;
        }
        Duration target = remainingTime.plus(COMPLETION_MARGIN);
        if (target.compareTo(MINIMUM_RECHECK_DELAY) < 0) {
            return MINIMUM_RECHECK_DELAY;
        }
        if (target.compareTo(SANITY_CEILING) > 0) {
            return SANITY_CEILING;
        }
        return target;
    }
}
