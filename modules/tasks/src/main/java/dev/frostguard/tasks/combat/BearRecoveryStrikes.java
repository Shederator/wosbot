package dev.frostguard.tasks.combat;

import java.util.EnumMap;
import java.util.Map;

/**
 * Bounded recovery escalation for a Bear session. Strikes are counted per goal so a successful
 * recovery for one goal cannot hide another that keeps failing. An unclassifiable screen gets one
 * bounded Back before the game is restarted.
 */
final class BearRecoveryStrikes {

    enum UnknownStep {
        PRESS_BACK_ONCE,
        RESTART_GAME
    }

    private final int limit;
    private final Map<BearSessionCoordinator.State, Integer> strikes =
            new EnumMap<>(BearSessionCoordinator.State.class);
    private int consecutiveUnknown;

    BearRecoveryStrikes(int limit) {
        this.limit = limit;
    }

    /** Records a failed recovery for {@code goal}; true when its budget is exhausted. */
    boolean failed(BearSessionCoordinator.State goal) {
        if (strikes.merge(goal, 1, Integer::sum) >= limit) {
            strikes.remove(goal);
            return true;
        }
        return false;
    }

    void recovered(BearSessionCoordinator.State goal) {
        strikes.remove(goal);
    }

    int strikes(BearSessionCoordinator.State goal) {
        return strikes.getOrDefault(goal, 0);
    }

    UnknownStep unknownScreen() {
        if (++consecutiveUnknown >= 2) {
            consecutiveUnknown = 0;
            return UnknownStep.RESTART_GAME;
        }
        return UnknownStep.PRESS_BACK_ONCE;
    }

    void classifiedScreen() {
        consecutiveUnknown = 0;
    }
}
