package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SidebarFrameClassifier;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.vision.convert.ImageConverter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static dev.frostguard.api.configs.TemplatesEnum.*;

/**
 * The production Bear screen classifier. It checks mutually exclusive screens in precedence order
 * (reconnect and dialogs before panels, panels before the World root). Page headings and selected
 * tabs establish list identity; remembered entry and optional join controls never substitute for it.
 * It is separate from the session so recorded frames can be replayed through it.
 */
final class BearFrameClassifier {

    @FunctionalInterface
    interface TemplateMatcher {
        boolean found(RawImageData frame, TemplatesEnum template, int threshold);

        default boolean foundIn(RawImageData frame, TemplatesEnum template, int threshold, AreaData area) {
            return found(frame, template, threshold);
        }
    }

    record Classification(BearNavigationPolicy.Screen screen, long totalNanos, Map<String, Long> templateNanos,
            TrapStatus configuredTrapStatus) {
    }

    private final TemplateMatcher matcher;
    private final int trapNumber;
    private final Map<String, Long> templateNanos = new HashMap<>();
    private boolean warListKnown;

    BearFrameClassifier(TemplateMatcher matcher, int trapNumber) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.trapNumber = trapNumber;
    }

    Classification classify(RawImageData frame) {
        templateNanos.clear();
        long started = System.nanoTime();
        BearNavigationPolicy.Screen screen = classifyUntimed(frame);
        TrapStatus status = screen == BearNavigationPolicy.Screen.SPECIAL_BUILDINGS
                ? trapStatus(frame, trapNumber) : TrapStatus.UNKNOWN;
        return new Classification(screen, System.nanoTime() - started, Map.copyOf(templateNanos), status);
    }

    void rememberWarList(boolean entered) {
        warListKnown = entered;
    }

    boolean warListKnown() {
        return warListKnown;
    }

    Optional<AreaData> warListBack(RawImageData frame) {
        return warListVisible(frame) && found(frame, BEAR_BACK_ARROW, 90)
                ? Optional.of(CommonGameAreas.BEAR_BACK_ARROW_TARGET) : Optional.empty();
    }

    private boolean warListVisible(RawImageData frame) {
        return BearWarListIdentity.isVisible(found(frame, BEAR_WAR_TITLE, 90),
                found(frame, BEAR_RALLY_TAB, 90));
    }

    enum TrapStatus { ACTIVE, COOLDOWN, UNKNOWN }

    TrapStatus trapStatus(RawImageData frame, int number) {
        if ((number != 1 && number != 2) || !found(frame, BEAR_TERRITORY_TITLE, 90)
                || !found(frame, BEAR_SPECIAL_TAB, 90)
                || !found(frame, number == 1 ? BEAR_TRAP_1_TITLE : BEAR_TRAP_2_TITLE, 95)) {
            return TrapStatus.UNKNOWN;
        }
        var area = number == 1 ? CommonGameAreas.BEAR_TRAP_1_STATUS : CommonGameAreas.BEAR_TRAP_2_STATUS;
        boolean active = foundIn(frame, BEAR_TRAP_ACTIVE, 90, area);
        boolean cooldown = foundIn(frame, BEAR_TRAP_COOLDOWN, 90, area);
        if (active == cooldown) return TrapStatus.UNKNOWN;
        return active ? TrapStatus.ACTIVE : TrapStatus.COOLDOWN;
    }

    private BearNavigationPolicy.Screen classifyUntimed(RawImageData frame) {
        if (frame.getWidth() != 720 || frame.getHeight() != 1280) {
            warListKnown = false;
            return BearNavigationPolicy.Screen.UNKNOWN;
        }
        BearNavigationPolicy.Screen screen;
        if (found(frame, GAME_HOME_RECONNECT, 85)) {
            screen = BearNavigationPolicy.Screen.RECONNECT;
        } else if (found(frame, GAME_START_WELCOME_BACK_TITLE, 85)
                || found(frame, GAME_START_DOWNLOAD_NOW, 85)
                || found(frame, GAME_START_MANDATORY_UPDATE_TITLE, 85)) {
            screen = BearNavigationPolicy.Screen.APP_LOADING;
        } else if (found(frame, RALLY_MARCH_QUEUE_FULL, 85)) {
            screen = BearNavigationPolicy.Screen.MARCH_QUEUE_FULL;
        } else if (found(frame, BEAR_PET_CONFIRMATION, 95)
                && found(frame, BEAR_PET_CONFIRM_USE, 90)) {
            screen = BearNavigationPolicy.Screen.PET_CONFIRMATION;
        } else if (found(frame, BEAR_PET_TITLE, 95)) {
            if (found(frame, BEAR_PET_BATTLE_NAME, 95)) {
                screen = found(frame, BEAR_PET_ACTIVE, 95)
                        ? BearNavigationPolicy.Screen.PET_BATTLE_ACTIVE
                        : found(frame, BEAR_PET_QUICK_USE, 95)
                        ? BearNavigationPolicy.Screen.PET_BATTLE_SELECTED
                        : BearNavigationPolicy.Screen.PET_SKILL_PANEL;
            } else {
                screen = BearNavigationPolicy.Screen.PET_SKILL_PANEL;
            }
        } else if (found(frame, DEPLOY_CONFIRMATION_DIALOG, 90)
                || found(frame, TROOPS_ALREADY_MARCHING, 90)) {
            screen = BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION;
        } else if (found(frame, BEAR_DEPLOY_BUTTON, 90)) {
            screen = BearNavigationPolicy.Screen.FORMATION;
        } else if (found(frame, RALLY_HOLD_BUTTON, 90)) {
            screen = BearNavigationPolicy.Screen.RALLY_TIMER_PANEL;
        } else if (found(frame, BEAR_RALLY_BUTTON, 80)
                && found(frame, BEAR_PANEL_TITLE, 95) && configuredBearCentered(frame)) {
            screen = BearNavigationPolicy.Screen.BEAR_RALLY_PANEL;
        } else {
            if (warListVisible(frame)) {
                screen = BearNavigationPolicy.Screen.WAR_LIST;
            } else if (found(frame, BEAR_TERRITORY_TITLE, 90)) {
                screen = found(frame, BEAR_SPECIAL_TAB, 90)
                        ? BearNavigationPolicy.Screen.SPECIAL_BUILDINGS
                        : (found(frame, BEAR_TERRITORY_TAB, 90) || found(frame, BEAR_TERRITORY_OVERVIEW, 95))
                        ? BearNavigationPolicy.Screen.ALLIANCE_TERRITORY : BearNavigationPolicy.Screen.UNKNOWN;
            } else if (found(frame, BEAR_WAR_TITLE, 90)) {
                // A detail page also has a War heading and plus controls, but is not the list.
                screen = BearNavigationPolicy.Screen.UNKNOWN;
            } else if (found(frame, ALLIANCE_TERRITORY_BUTTON, 80)) {
                screen = BearNavigationPolicy.Screen.ALLIANCE_MENU;
            } else {
                Optional<SidebarSection> sidebar = SidebarFrameClassifier.selectedSection(
                        ImageConverter.toBufferedImage(frame));
                if (sidebar.orElse(null) == SidebarSection.WILDERNESS) {
                    screen = BearNavigationPolicy.Screen.MARCH_SIDEBAR;
                } else if (sidebar.isPresent()) {
                    screen = BearNavigationPolicy.Screen.SIDEBAR_OTHER;
                } else if (found(frame, GAME_HOME_WORLD, 90) || found(frame, BEAR_WORLD_CITY, 90)) {
                    screen = configuredBearCentered(frame)
                            ? BearNavigationPolicy.Screen.WORLD_AT_CONFIGURED_BEAR
                            : found(frame, BEAR_HUNT_IS_RUNNING, 90)
                            ? BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY
                            : BearNavigationPolicy.Screen.WORLD;
                } else {
                    screen = BearNavigationPolicy.Screen.UNKNOWN;
                }
            }
        }
        if (screen == BearNavigationPolicy.Screen.WAR_LIST) {
            warListKnown = true;
        } else if (screen != BearNavigationPolicy.Screen.FORMATION
                && screen != BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION
                && screen != BearNavigationPolicy.Screen.MARCH_QUEUE_FULL) {
            warListKnown = false;
        }
        return screen;
    }

    private boolean found(RawImageData frame, TemplatesEnum template, int threshold) {
        long started = System.nanoTime();
        try {
            return matcher.found(frame, template, threshold);
        } finally {
            templateNanos.merge(template.name(), System.nanoTime() - started, Long::sum);
        }
    }

    boolean configuredBearCentered(RawImageData frame) {
        return configuredBearIdentity(frame)
                && (found(frame, BEAR_CENTER_ACTIVE, 95) || found(frame, BEAR_CENTER_ACTIVE_PANEL, 95)
                        || found(frame, BEAR_CENTER_ACTIVE_LIVE, 95));
    }

    boolean configuredBearIdentity(RawImageData frame) {
        return switch (trapNumber) {
            case 1 -> found(frame, BEAR_CENTER_TRAP_1, 95) || found(frame, BEAR_CENTER_TRAP_1_PANEL, 95)
                    || found(frame, BEAR_CENTER_TRAP_1_LIVE, 95);
            case 2 -> found(frame, BEAR_CENTER_TRAP_2, 95);
            default -> false;
        };
    }

    private boolean foundIn(RawImageData frame, TemplatesEnum template, int threshold, AreaData area) {
        long started = System.nanoTime();
        try {
            return matcher.foundIn(frame, template, threshold, area);
        } finally {
            templateNanos.merge(template.name(), System.nanoTime() - started, Long::sum);
        }
    }
}
