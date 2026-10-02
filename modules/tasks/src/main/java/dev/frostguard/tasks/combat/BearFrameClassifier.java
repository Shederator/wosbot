package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SidebarFrameClassifier;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.detection.CloseCrossDetector;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static dev.frostguard.api.configs.TemplatesEnum.*;

/**
 * The production Bear screen classifier. It checks mutually exclusive screens in precedence order
 * (reconnect and dialogs before panels, panels before the World root) and remembers whether the
 * War list was entered from a verified indicator, which an empty list cannot prove by itself.
 * It is separate from the session so recorded frames can be replayed through it.
 */
final class BearFrameClassifier {

    @FunctionalInterface
    interface TemplateMatcher {
        boolean found(RawImageData frame, TemplatesEnum template, int threshold);
    }

    record Classification(BearNavigationPolicy.Screen screen, long totalNanos, Map<String, Long> templateNanos) {
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
        return new Classification(screen, System.nanoTime() - started, Map.copyOf(templateNanos));
    }

    void rememberWarList(boolean entered) {
        warListKnown = entered;
    }

    boolean warListKnown() {
        return warListKnown;
    }

    static Optional<AreaData> warListClose(RawImageData frame) {
        return CloseCrossDetector.locate(frame, CommonGameAreas.BEAR_WAR_LIST_CLOSE_SEARCH_AREA)
                .stream()
                .findFirst()
                .map(CloseCrossDetector.Detection::bounds);
    }

    private BearNavigationPolicy.Screen classifyUntimed(RawImageData frame) {
        BearNavigationPolicy.Screen screen;
        if (found(frame, GAME_HOME_RECONNECT, 85)) {
            screen = BearNavigationPolicy.Screen.RECONNECT;
        } else if (found(frame, GAME_START_WELCOME_BACK_TITLE, 85)
                || found(frame, GAME_START_DOWNLOAD_NOW, 85)
                || found(frame, GAME_START_MANDATORY_UPDATE_TITLE, 85)) {
            screen = BearNavigationPolicy.Screen.APP_LOADING;
        } else if (found(frame, RALLY_MARCH_QUEUE_FULL, 85)) {
            screen = BearNavigationPolicy.Screen.MARCH_QUEUE_FULL;
        } else if (found(frame, DEPLOY_CONFIRMATION_DIALOG, 90)
                || found(frame, TROOPS_ALREADY_MARCHING, 90)) {
            screen = BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION;
        } else if (found(frame, BEAR_DEPLOY_BUTTON, 90)) {
            screen = BearNavigationPolicy.Screen.FORMATION;
        } else if (found(frame, RALLY_HOLD_BUTTON, 90)) {
            screen = BearNavigationPolicy.Screen.RALLY_TIMER_PANEL;
        } else if (found(frame, BEAR_RALLY_BUTTON, 80)) {
            screen = BearNavigationPolicy.Screen.BEAR_RALLY_PANEL;
        } else {
            boolean joinButtonVisible = found(frame, BEAR_JOIN_PLUS_ICON, 80);
            boolean warCloseVisible = warListClose(frame).isPresent();
            if (BearWarListIdentity.isVisible(
                    warListKnown, warCloseVisible, joinButtonVisible ? 1 : 0)) {
                screen = BearNavigationPolicy.Screen.WAR_LIST;
            } else if (found(frame, ALLIANCE_TERRITORY_BUTTON, 80)) {
                screen = BearNavigationPolicy.Screen.ALLIANCE_MENU;
            } else if (BearSpecialBuildingsScreenClassifier.isGoButtonReady(
                    ImageConverter.toBufferedImage(frame), trapNumber)) {
                screen = BearNavigationPolicy.Screen.SPECIAL_BUILDINGS;
            } else if (found(frame, PETS_SKILL_USE, 85)) {
                screen = BearNavigationPolicy.Screen.PET_QUICK_USE;
            } else if (found(frame, PETS_INFO_SKILLS, 85)) {
                screen = BearNavigationPolicy.Screen.PETS_OVERVIEW;
            } else {
                Optional<SidebarSection> sidebar = SidebarFrameClassifier.selectedSection(
                        ImageConverter.toBufferedImage(frame));
                if (sidebar.orElse(null) == SidebarSection.WILDERNESS) {
                    screen = BearNavigationPolicy.Screen.MARCH_SIDEBAR;
                } else if (sidebar.isPresent()) {
                    screen = BearNavigationPolicy.Screen.SIDEBAR_OTHER;
                } else if (found(frame, GAME_HOME_WORLD, 90)) {
                    screen = found(frame, BEAR_HUNT_IS_RUNNING, 90)
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
}
