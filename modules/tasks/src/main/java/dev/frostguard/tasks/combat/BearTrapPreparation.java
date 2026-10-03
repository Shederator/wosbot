package dev.frostguard.tasks.combat;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Bounded preparation navigation; arrival proves the configured number, not event activation. */
final class BearTrapPreparation {
    private BearTrapPreparation() { }

    static <T> boolean navigate(BearUiStateMachine<T> ui,
            Predicate<BearFrameStream.Snapshot<T>> configuredTrap,
            BiConsumer<BearUiAction, BearFrameStream.Snapshot<T>> input) {
        for (int edge = 0; edge < 5; edge++) {
            var frame = ui.observe();
            if (BearProductionScreens.WORLD.contains(frame.screen()) && configuredTrap.test(frame)) return true;
            BearUiAction action = switch (frame.screen()) {
                case WORLD, WORLD_ACTIVE_BEAR_ICON_READY, WORLD_AT_CONFIGURED_BEAR -> BearUiAction.OPEN_ALLIANCE;
                case ALLIANCE_MENU -> BearUiAction.OPEN_TERRITORY;
                case ALLIANCE_TERRITORY -> BearUiAction.OPEN_SPECIAL_BUILDINGS;
                case SPECIAL_BUILDINGS -> BearUiAction.GO_TO_CONFIGURED_TRAP;
                default -> null;
            };
            if (action == null) return false;
            var outcome = ui.transition(action, authorization -> input.accept(action, authorization),
                    action == BearUiAction.GO_TO_CONFIGURED_TRAP
                            ? "newer World frame with configured numbered trap centered" : action.expectedPostcondition(),
                    next -> action == BearUiAction.GO_TO_CONFIGURED_TRAP
                            ? BearProductionScreens.WORLD.contains(next.screen()) && configuredTrap.test(next)
                            : action.confirms(next.screen()),
                    next -> action.legalFrom(next.screen()), 0);
            if (outcome != BearVerifiedActionExecutor.Outcome.CONFIRMED) return false;
        }
        return false;
    }
}
