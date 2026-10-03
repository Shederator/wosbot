package dev.frostguard.tasks.combat;

import static dev.frostguard.tasks.combat.BearNavigationPolicy.Screen.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic transition tests; real pixels are covered separately by BearLiveFrameReplayTest. */
class BearPetPreparationTest {
    @Test
    void everyPetInputRequiresItsOwnNewerPostcondition() {
        Replay replay = new Replay(WORLD, PET_SKILL_PANEL, PET_BATTLE_SELECTED,
                PET_CONFIRMATION, PET_BATTLE_SELECTED, PET_BATTLE_ACTIVE, WORLD);
        assertTrue(replay.activate());
        assertEquals(List.of(BearUiAction.OPEN_PETS, BearUiAction.SELECT_BATTLE_SKILL,
                BearUiAction.OPEN_PET_QUICK_USE, BearUiAction.CONFIRM_PET_USE,
                BearUiAction.CLOSE_PET_SKILLS), replay.inputs);
        assertEquals(List.of(1L, 2L, 3L, 4L, 6L), replay.provenance);
    }

    @Test
    void interruptedConfirmationResumesWithoutRepeatingQuickUse() {
        Replay replay = new Replay(PET_CONFIRMATION, PET_BATTLE_ACTIVE, WORLD);
        assertTrue(replay.activate());
        assertEquals(List.of(BearUiAction.CONFIRM_PET_USE, BearUiAction.CLOSE_PET_SKILLS), replay.inputs);
    }

    @Test
    void reconstructionAfterAmbiguousUseWaitsForActiveWithoutAnotherConfirmation() {
        Replay replay = new Replay(PET_BATTLE_SELECTED, PET_BATTLE_SELECTED, PET_BATTLE_ACTIVE, WORLD);
        assertTrue(replay.activate(true));
        assertEquals(List.of(BearUiAction.CLOSE_PET_SKILLS), replay.inputs);
    }

    @Test
    void alreadyActiveSkillIsNotActivatedAgain() {
        Replay replay = new Replay(PET_BATTLE_ACTIVE, WORLD);
        assertTrue(replay.activate());
        assertEquals(List.of(BearUiAction.CLOSE_PET_SKILLS), replay.inputs);
    }

    @Test
    void unchangedPanelDoesNotCauseRepeatedSelectionOrUse() {
        Replay replay = new Replay(PET_SKILL_PANEL);
        assertFalse(replay.activate());
        assertEquals(List.of(BearUiAction.SELECT_BATTLE_SKILL), replay.inputs);
    }

    @Test
    void returningToTheSelectedPanelDoesNotProveActivation() {
        Replay replay = new Replay(PET_CONFIRMATION, PET_BATTLE_SELECTED);
        assertFalse(replay.activate());
        assertEquals(List.of(BearUiAction.CONFIRM_PET_USE), replay.inputs);
    }

    @Test
    void unrelatedDialogIsNeverAcceptedAsPetConfirmation() {
        Replay replay = new Replay(DEPLOY_CONFIRMATION);
        assertFalse(replay.activate());
        assertTrue(replay.inputs.isEmpty());
    }

    @Test
    void failedCloseDoesNotReportPreparationComplete() {
        Replay replay = new Replay(PET_BATTLE_ACTIVE);
        assertFalse(replay.activate());
        assertEquals(List.of(BearUiAction.CLOSE_PET_SKILLS), replay.inputs);
    }

    private static final class Replay {
        final List<BearUiAction> inputs = new ArrayList<>();
        final List<Long> provenance = new ArrayList<>();
        final BearUiStateMachine<BearNavigationPolicy.Screen> ui;

        Replay(BearNavigationPolicy.Screen... screens) {
            var queue = new ArrayDeque<>(List.of(screens));
            var stream = new BearFrameStream<>(() -> queue.size() > 1 ? queue.removeFirst() : queue.peek(),
                    screen -> screen, () -> false);
            ui = new BearUiStateMachine<>(stream, Duration.ofMillis(150), ignored -> {});
        }

        boolean activate() {
            return activate(false);
        }

        boolean activate(boolean usePreviouslyArmed) {
            return BearPetPreparation.activate(ui, usePreviouslyArmed, (action, frame) -> {
                inputs.add(action);
                provenance.add(frame.sequence());
            });
        }
    }
}
