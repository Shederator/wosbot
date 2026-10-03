package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BearRecoveryStrikesTest {

    @Test
    void aRecoveryForOneGoalDoesNotResetAnotherGoalsStrikes() {
        BearRecoveryStrikes strikes = new BearRecoveryStrikes(3);

        assertFalse(strikes.failed(BearSessionCoordinator.State.OWN_RALLY_REQUIRED));
        assertFalse(strikes.failed(BearSessionCoordinator.State.OWN_RALLY_REQUIRED));
        strikes.recovered(BearSessionCoordinator.State.FILL_JOIN_SLOTS);

        assertTrue(strikes.failed(BearSessionCoordinator.State.OWN_RALLY_REQUIRED),
                "the third own-rally failure exhausts its budget despite the join recovery");
    }

    @Test
    void recoveringAGoalClearsOnlyItsOwnStrikes() {
        BearRecoveryStrikes strikes = new BearRecoveryStrikes(3);
        strikes.failed(BearSessionCoordinator.State.OWN_RALLY_REQUIRED);
        strikes.failed(BearSessionCoordinator.State.OWN_RALLY_REQUIRED);

        strikes.recovered(BearSessionCoordinator.State.OWN_RALLY_REQUIRED);

        assertFalse(strikes.failed(BearSessionCoordinator.State.OWN_RALLY_REQUIRED));
        assertEquals(1, strikes.strikes(BearSessionCoordinator.State.OWN_RALLY_REQUIRED));
    }

    @Test
    void anUnknownScreenGetsOneBoundedBackBeforeAGameRestart() {
        BearRecoveryStrikes strikes = new BearRecoveryStrikes(3);

        assertEquals(BearRecoveryStrikes.UnknownStep.PRESS_BACK_ONCE, strikes.unknownScreen());
        assertEquals(BearRecoveryStrikes.UnknownStep.RESTART_GAME, strikes.unknownScreen());
        assertEquals(BearRecoveryStrikes.UnknownStep.PRESS_BACK_ONCE, strikes.unknownScreen(),
                "the restart starts a new escalation");

        strikes.unknownScreen();
        strikes.classifiedScreen();
        assertEquals(BearRecoveryStrikes.UnknownStep.PRESS_BACK_ONCE, strikes.unknownScreen(),
                "a classified screen resets the escalation");
    }
}
