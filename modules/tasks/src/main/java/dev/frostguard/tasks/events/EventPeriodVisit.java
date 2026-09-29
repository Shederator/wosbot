package dev.frostguard.tasks.events;

import java.time.LocalDateTime;

/**
 * Period-event visit decision. A missing tab gets one later menu walk, then the
 * task's rest time. Button absence is not completion: that takes a positive match.
 * The duration is only a fallback for a countdown that cannot be read.
 */
public final class EventPeriodVisit {

    public static final int DEFAULT_DURATION_DAYS = 3;
    public static final int MENU_RETRY_MINUTES = 5;

    public enum TabMiss {
        RETRY,
        REST
    }

    public enum OpenedScreen {
        ACTIVE,
        COMPLETED,
        COUNTDOWN_TO_START,
        UNRECOGNIZED
    }

    private EventPeriodVisit() {
    }

    public static TabMiss tabMiss(boolean extraVisitAlreadyUsed) {
        return extraVisitAlreadyUsed ? TabMiss.REST : TabMiss.RETRY;
    }

    /** The extra visit is remembered until it is used or the tab is found. */
    public static boolean rememberExtraVisit(boolean extraVisitAlreadyUsed) {
        return !extraVisitAlreadyUsed;
    }

    public static LocalDateTime retryAt(LocalDateTime now) {
        return now.plusMinutes(MENU_RETRY_MINUTES);
    }

    public static LocalDateTime durationFallback(LocalDateTime seenAt) {
        return seenAt.plusDays(DEFAULT_DURATION_DAYS);
    }

    public static OpenedScreen openedScreen(boolean huntControlPresent, boolean completionRecognized,
            boolean startCountdownRecognized) {
        if (huntControlPresent) {
            return OpenedScreen.ACTIVE;
        }
        if (completionRecognized) {
            return OpenedScreen.COMPLETED;
        }
        if (startCountdownRecognized) {
            return OpenedScreen.COUNTDOWN_TO_START;
        }
        return OpenedScreen.UNRECOGNIZED;
    }

    public static LocalDateTime nextVisit(OpenedScreen screen, LocalDateTime now, LocalDateTime dailyReset,
            LocalDateTime countdown) {
        return switch (screen) {
            case COMPLETED -> dailyReset;
            case COUNTDOWN_TO_START -> countdown != null ? countdown : retryAt(now);
            case UNRECOGNIZED, ACTIVE -> retryAt(now);
        };
    }

    public static boolean endCountdownUnconfirmed(boolean countdownRead, boolean completionConfirmed) {
        return countdownRead && !completionConfirmed;
    }
}
