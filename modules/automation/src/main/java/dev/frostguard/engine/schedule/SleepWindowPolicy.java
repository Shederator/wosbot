package dev.frostguard.engine.schedule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.engine.service.ConfigService;

/**
 * Tracks when a profile's queue has nothing to do for long enough to call it asleep.
 *
 * <p>matt, 2026-08-08, revising his own earlier request for a fixed nightly window: sleep should
 * fall out of the real schedule rather than being imposed on it. Once everything has been read
 * off the screen, the queue already knows when the next action is due — if that is far enough
 * away, the bot is asleep until then, whatever the clock says.</p>
 *
 * <p>His follow-up point is the important one: <em>"as long as the bot doesn't do anything, why
 * are we putting it into a sleep?"</em> — correct, and it is why this deliberately does not gate
 * dispatch. An idle queue is already idle. This only names that state so it is visible in the
 * UI, which means it cannot suppress work that was actually due. The earlier version did block
 * the loop, and carried a real risk of holding back a task that came due mid-window.</p>
 */
public final class SleepWindowPolicy {

    /** Wake time per profile, present only while that queue counts as asleep. */
    private static final Map<Long, LocalDateTime> SLEEPING = new ConcurrentHashMap<>();

    private SleepWindowPolicy() {
    }

    /**
     * Idle gap long enough to be called sleep rather than a pause between tasks.
     *
     * <p>matt: <em>"look for anything more than twenty minutes... the minimum should be twenty
     * minutes"</em>, with no upper bound.</p>
     */
    public static int thresholdMinutes() {
        try {
            Map<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
            ConfigurationKeyEnum key = ConfigurationKeyEnum.SLEEP_IDLE_THRESHOLD_MINUTES_INT;
            String raw = cfg == null ? null : cfg.get(key.name());
            int parsed = Integer.parseInt(
                    (raw == null || raw.isBlank()) ? key.getDefaultValue() : raw.trim());
            return Math.max(1, parsed);
        } catch (Exception ex) {
            return 20;
        }
    }

    /**
     * Records that a profile has no work until {@code nextDueAt}, if that is far enough out.
     *
     * @return {@code true} when the gap qualifies as sleep
     */
    public static boolean reportIdleUntil(Long profileId, LocalDateTime nextDueAt) {
        if (profileId == null || nextDueAt == null) {
            clear(profileId);
            return false;
        }

        Duration gap = Duration.between(LocalDateTime.now(), nextDueAt);
        if (gap.toMinutes() < thresholdMinutes()) {
            clear(profileId);
            return false;
        }

        SLEEPING.put(profileId, nextDueAt);
        return true;
    }

    public static void clear(Long profileId) {
        if (profileId != null) {
            SLEEPING.remove(profileId);
        }
    }

    /**
     * Earliest moment any sleeping profile is due to wake.
     *
     * <p>Only reports while <em>every</em> known sleeper is still asleep and none has passed its
     * wake time, so a single busy profile does not get described as sleeping.</p>
     *
     * @return the soonest wake time, or {@code null} when nothing is asleep
     */
    public static LocalDateTime earliestWake() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime soonest = null;
        for (LocalDateTime wake : SLEEPING.values()) {
            if (wake.isAfter(now) && (soonest == null || wake.isBefore(soonest))) {
                soonest = wake;
            }
        }
        return soonest;
    }

    /** Remaining sleep across all profiles, or {@link Duration#ZERO} when awake. */
    public static Duration remaining(LocalDateTime now) {
        LocalDateTime wake = earliestWake();
        if (wake == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(now, wake);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
