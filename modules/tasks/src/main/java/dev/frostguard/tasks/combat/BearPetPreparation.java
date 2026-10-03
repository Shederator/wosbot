package dev.frostguard.tasks.combat;

import java.util.function.BiConsumer;
import java.time.Duration;

/** Resumes the recorded battle-skill flow from its observed screen, never by replaying old taps. */
final class BearPetPreparation {
    private BearPetPreparation() {}

    static <T> boolean activate(
            BearUiStateMachine<T> ui,
            boolean usePreviouslyArmed,
            BiConsumer<BearUiAction, BearFrameStream.Snapshot<T>> input) {
        ui.observe();
        // Open, select, request all available battle skills, confirm, close. No blind retry.
        for (int edge = 0; edge < 5; edge++) {
            if (usePreviouslyArmed && (ui.current().screen() == BearNavigationPolicy.Screen.PET_BATTLE_SELECTED
                    || ui.current().screen() == BearNavigationPolicy.Screen.PET_CONFIRMATION)) {
                if (ui.await(Duration.ofSeconds(4),
                        frame -> frame.screen() == BearNavigationPolicy.Screen.PET_BATTLE_ACTIVE,
                        "ambiguous-pet-use-await-active-without-repeating-confirmation").isEmpty()) return false;
            }
            BearUiAction action = switch (ui.current().screen()) {
                case WORLD, WORLD_ACTIVE_BEAR_ICON_READY, WORLD_AT_CONFIGURED_BEAR -> BearUiAction.OPEN_PETS;
                case PET_SKILL_PANEL -> BearUiAction.SELECT_BATTLE_SKILL;
                case PET_BATTLE_SELECTED -> BearUiAction.OPEN_PET_QUICK_USE;
                case PET_CONFIRMATION -> BearUiAction.CONFIRM_PET_USE;
                case PET_BATTLE_ACTIVE -> BearUiAction.CLOSE_PET_SKILLS;
                default -> null;
            };
            if (action == null || ui.transition(action, frame -> input.accept(action, frame))
                    != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                return false;
            }
            if (action == BearUiAction.CLOSE_PET_SKILLS) return true;
        }
        return false;
    }
}
