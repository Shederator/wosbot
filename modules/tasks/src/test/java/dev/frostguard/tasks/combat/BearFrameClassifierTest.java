package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.RawImageData;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BearFrameClassifierTest {

    private static final RawImageData BLANK = RawImageData.capture(new byte[720 * 1280 * 4], 720, 1280, 32);

    @Test
    void unsupportedGeometryCannotAuthorizeInputEvenWithMatchingSignals() {
        var classifier = new BearFrameClassifier((frame, template, threshold) -> true, 1);
        assertEquals(BearNavigationPolicy.Screen.UNKNOWN,
                classifier.classify(RawImageData.capture(new byte[1080 * 2400 * 4], 1080, 2400, 32)).screen());
    }

    @Test
    void duringTheEventTheWorldRootIsClassifiedWithTheBearIcon() {
        assertEquals(BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                classify(EnumSet.of(TemplatesEnum.GAME_HOME_WORLD, TemplatesEnum.BEAR_HUNT_IS_RUNNING)));
        assertEquals(BearNavigationPolicy.Screen.WORLD, classify(EnumSet.of(TemplatesEnum.GAME_HOME_WORLD)));
    }

    @Test
    void dialogsAndReconnectTakePrecedenceOverTheScreenBeneathThem() {
        assertEquals(BearNavigationPolicy.Screen.RECONNECT, classify(EnumSet.of(
                TemplatesEnum.GAME_HOME_RECONNECT, TemplatesEnum.GAME_HOME_WORLD, TemplatesEnum.BEAR_DEPLOY_BUTTON)));
        assertEquals(BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION, classify(EnumSet.of(
                TemplatesEnum.DEPLOY_CONFIRMATION_DIALOG, TemplatesEnum.BEAR_DEPLOY_BUTTON)));
        assertEquals(BearNavigationPolicy.Screen.MARCH_QUEUE_FULL, classify(EnumSet.of(
                TemplatesEnum.RALLY_MARCH_QUEUE_FULL, TemplatesEnum.DEPLOY_CONFIRMATION_DIALOG)));
    }

    @Test
    void anEmptyWarListNeedsTheVerifiedEntryToBeRecognised() {
        BearFrameClassifier classifier = new BearFrameClassifier((frame, template, threshold) -> false, 1);
        assertEquals(BearNavigationPolicy.Screen.UNKNOWN, classifier.classify(BLANK).screen());

        BearFrameClassifier withPlus = new BearFrameClassifier(
                (frame, template, threshold) -> template == TemplatesEnum.BEAR_WAR_TITLE
                        || template == TemplatesEnum.BEAR_RALLY_TAB, 1);
        assertEquals(BearNavigationPolicy.Screen.WAR_LIST, withPlus.classify(BLANK).screen());
        assertTrue(withPlus.warListKnown(), "a recognised War list is remembered for empty pages");

        withPlus.classify(BLANK);
        BearFrameClassifier leaving = new BearFrameClassifier(
                (frame, template, threshold) -> template == TemplatesEnum.GAME_HOME_WORLD, 1);
        leaving.rememberWarList(true);
        leaving.classify(BLANK);
        assertFalse(leaving.warListKnown(), "reaching World forgets the War-list entry");
    }

    @Test
    void reportsTheTimeSpentPerTemplate() {
        BearFrameClassifier.Classification classification = new BearFrameClassifier(
                (frame, template, threshold) -> false, 1).classify(BLANK);

        assertTrue(classification.templateNanos().containsKey(TemplatesEnum.GAME_HOME_RECONNECT.name()));
        assertTrue(classification.templateNanos().containsKey(TemplatesEnum.GAME_HOME_WORLD.name()));
        assertTrue(classification.totalNanos() > 0);
    }

    private static BearNavigationPolicy.Screen classify(Set<TemplatesEnum> visible) {
        return new BearFrameClassifier((frame, template, threshold) -> visible.contains(template), 1)
                .classify(BLANK).screen();
    }
}
