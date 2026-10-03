package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.*;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real, privacy-redacted frames: these are visual regressions, not scripted matcher results. */
class BearLiveFrameReplayTest {
    @org.junit.jupiter.api.Test
    void recordedShortcutOpenedPanelMustNotBeMisclassifiedAsWorld() throws Exception {
        var matcher = new BearTemplateMatcher(EmulatorController.getInstance(), "bear-replay");
        var raw = frame("shortcut-opened-panel");
        assertEquals(BearNavigationPolicy.Screen.BEAR_RALLY_PANEL,
                new BearFrameClassifier(matcher, 1).classify(raw).screen());
        assertFalse(new BearFrameClassifier(matcher, 2).configuredBearCentered(raw),
                "Trap 1 evidence must never authorize Trap 2");
        assertTrue(BearUiAction.OPEN_ACTIVE_BEAR.destinations()
                .contains(BearNavigationPolicy.Screen.BEAR_RALLY_PANEL));
    }
    @BeforeAll
    static void initialize() throws Exception {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
        OpenCvPatternLocator.loadNativeLibrary();
    }

    @ParameterizedTest
    @CsvSource({"war-list,WAR_LIST", "rally-detail,UNKNOWN", "territory,ALLIANCE_TERRITORY",
            "trap-status,SPECIAL_BUILDINGS", "settings,UNKNOWN", "march-queue,MARCH_QUEUE_FULL",
            "capacity,DEPLOY_CONFIRMATION", "event-ended,WORLD", "formation,FORMATION",
            "rally-timer,RALLY_TIMER_PANEL", "world-active,WORLD_ACTIVE_BEAR_ICON_READY",
            "pet-panel,PET_SKILL_PANEL", "pet-selected,PET_BATTLE_SELECTED",
            "pet-confirmation,PET_CONFIRMATION", "pet-pending,PET_BATTLE_SELECTED",
            "pet-active,PET_BATTLE_ACTIVE", "pet-growth,PET_SKILL_PANEL"})
    void recordedScreensHavePositiveIdentity(String name, BearNavigationPolicy.Screen expected) throws Exception {
        var classifier = new BearFrameClassifier(new BearTemplateMatcher(
                EmulatorController.getInstance(), "bear-replay"), 2);
        // Previous list entry must not turn another page into an empty rally list.
        classifier.rememberWarList(true);
        var result = classifier.classify(frame(name));
        assertEquals(expected, result.screen(), name);
    }

    @org.junit.jupiter.api.Test
    void recordedRallyCardsAreDetectedWithoutADeviceBackend() throws Exception {
        var helper = new dev.frostguard.engine.helper.TemplateSearchHelper(EmulatorController.getInstance(),
                "offline", new dev.frostguard.api.domain.AccountDescriptor(
                        990999L, "offline", "0", true, 100L, 30L),
                () -> { throw new AssertionError("Replay must never capture a device"); });
        var rows = new BearRallyScanner(helper, frame("war-list")).scanRowsWithoutText();
        assertEquals(2, rows.size(), "two fully visible Bear target cards, not the World shortcut");
        assertTrue(rows.stream().allMatch(BearRallyScanner.RallyRow::joinable));
        assertTrue(rows.get(0).rowY() < rows.get(1).rowY());
    }

    @org.junit.jupiter.api.Test
    void petActionsHaveTargetsInTheirExactRecordedAuthorizingFrames() throws Exception {
        var matcher = new BearTemplateMatcher(EmulatorController.getInstance(), "bear-replay");
        for (var pair : new String[][]{
                {"world-active", "GAME_HOME_PETS"}, {"pet-panel", "BEAR_PET_BATTLE_ICON"},
                {"pet-selected", "BEAR_PET_QUICK_USE"}, {"pet-confirmation", "BEAR_PET_CONFIRM_USE"},
                {"pet-confirmation", "BEAR_PET_CANCEL"}, {"pet-active", "BEAR_PET_CLOSE"}}) {
            assertTrue(matcher.found(frame(pair[0]),
                    dev.frostguard.api.configs.TemplatesEnum.valueOf(pair[1]), 95),
                    pair[0] + ": " + pair[1]);
        }
    }

    @org.junit.jupiter.api.Test
    void centeredTrapAndPanelMustBelongToTheConfiguredTrap() throws Exception {
        var matcher = new BearTemplateMatcher(EmulatorController.getInstance(), "bear-replay");
        var trapOne = new BearFrameClassifier(matcher, 1);
        var trapTwo = new BearFrameClassifier(matcher, 2);
        assertEquals(BearNavigationPolicy.Screen.WORLD_AT_CONFIGURED_BEAR,
                trapOne.classify(frame("bear-centered")).screen());
        assertEquals(BearNavigationPolicy.Screen.BEAR_RALLY_PANEL,
                trapOne.classify(frame("bear-panel")).screen());
        assertFalse(trapTwo.configuredBearCentered(frame("bear-centered")));
        assertNotEquals(BearNavigationPolicy.Screen.BEAR_RALLY_PANEL,
                trapTwo.classify(frame("bear-panel")).screen());
        assertFalse(trapOne.configuredBearCentered(frame("event-ended")));
        assertFalse(trapOne.configuredBearCentered(frame("world-active")));
    }

    @org.junit.jupiter.api.Test
    void realTrapTwoIdentityDoesNotTurnCooldownIntoActiveBear() throws Exception {
        var matcher = new BearTemplateMatcher(EmulatorController.getInstance(), "bear-replay");
        var image = frame("trap2-cooldown-centered");
        var one = new BearFrameClassifier(matcher, 1);
        var two = new BearFrameClassifier(matcher, 2);
        assertTrue(two.configuredBearIdentity(image));
        assertFalse(one.configuredBearIdentity(image));
        assertFalse(two.configuredBearCentered(image));
        assertNotEquals(BearNavigationPolicy.Screen.BEAR_RALLY_PANEL, two.classify(image).screen());
    }

    @org.junit.jupiter.api.Test
    void recordedTrapTwoPreparationHasDistinctTargetsAndInactiveStatus() throws Exception {
        var matcher = new BearTemplateMatcher(EmulatorController.getInstance(), "bear-replay");
        var classifier = new BearFrameClassifier(matcher, 2);
        var overview = frame("territory-overview");
        assertEquals(BearNavigationPolicy.Screen.ALLIANCE_TERRITORY, classifier.classify(overview).screen());
        assertTrue(matcher.found(overview, dev.frostguard.api.configs.TemplatesEnum.BEAR_SPECIAL_TAB_UNSELECTED, 95));
        var traps = frame("trap2-list-cooldown");
        assertEquals(BearNavigationPolicy.Screen.SPECIAL_BUILDINGS, classifier.classify(traps).screen());
        assertEquals(BearFrameClassifier.TrapStatus.COOLDOWN, classifier.trapStatus(traps, 2));
        for (int number : new int[]{1,2}) {
            assertTrue(matcher.foundIn(traps, dev.frostguard.api.configs.TemplatesEnum.BEAR_TRAP_GO, 95,
                    dev.frostguard.engine.nav.CommonGameAreas.bearTrapGoArea(number)));
        }
        assertTrue(matcher.found(frame("trap2-cooldown-centered"),
                dev.frostguard.api.configs.TemplatesEnum.BEAR_WORLD_ALLIANCE, 95));
    }

    @org.junit.jupiter.api.Test
    void backRequiresListIdentityAndTheArrowInTheSameFrame() throws Exception {
        var classifier = new BearFrameClassifier(new BearTemplateMatcher(
                EmulatorController.getInstance(), "bear-replay"), 2);
        assertTrue(classifier.warListBack(frame("war-list")).isPresent());
        for (String other : new String[]{"rally-detail", "settings", "territory", "trap-status", "event-ended"}) {
            assertTrue(classifier.warListBack(frame(other)).isEmpty(), other);
        }
    }

    @org.junit.jupiter.api.Test
    void goButtonsDoNotMakeTheConfiguredCooldownTrapActive() throws Exception {
        var classifier = new BearFrameClassifier(new BearTemplateMatcher(
                EmulatorController.getInstance(), "bear-replay"), 2);
        assertEquals(BearFrameClassifier.TrapStatus.ACTIVE, classifier.trapStatus(frame("trap-status"), 1));
        assertEquals(BearFrameClassifier.TrapStatus.COOLDOWN, classifier.trapStatus(frame("trap-status"), 2));
        assertEquals(BearFrameClassifier.TrapStatus.UNKNOWN, classifier.trapStatus(frame("settings"), 2));
        assertEquals(BearFrameClassifier.TrapStatus.COOLDOWN,
                classifier.classify(frame("trap-status")).configuredTrapStatus());
    }

    @org.junit.jupiter.api.Test
    void genericRallyIndicatorDoesNotKeepBearActiveAfterEventEnd() throws Exception {
        var image = frame("event-ended");
        var matcher = new BearTemplateMatcher(EmulatorController.getInstance(), "bear-replay");
        assertTrue(matcher.found(image, dev.frostguard.api.configs.TemplatesEnum.RALLY_INDICATOR, 80));
        assertEquals(BearNavigationPolicy.Screen.WORLD, new BearFrameClassifier(matcher, 1).classify(image).screen());
    }

    @org.junit.jupiter.api.Test
    void benchmarkRecordedFramesWhenRequested() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("bear.replay.benchmark"));
        var classifier = new BearFrameClassifier(new BearTemplateMatcher(
                EmulatorController.getInstance(), "bear-replay"), 2);
        for (String name : new String[]{"war-list", "rally-detail", "territory", "trap-status", "settings", "event-ended", "world-active"}) {
            var image = frame(name);
            classifier.classify(image); // warm native/template paths, not the observation itself
            long[] times = new long[5];
            for (int i = 0; i < times.length; i++) times[i] = classifier.classify(image).totalNanos() / 1_000_000;
            java.util.Arrays.sort(times);
            System.out.println("BEAR_REPLAY_TIMING " + name + " medianMs=" + times[2] + " maxMs=" + times[4]);
        }
    }

    static RawImageData frame(String name) throws Exception {
        BufferedImage image = ImageIO.read(BearLiveFrameReplayTest.class.getResource(
                "/bear/live-20261003/" + name + ".png"));
        return raw(image);
    }

    static RawImageData raw(BufferedImage image) {
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        for (int y = 0, i = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[i++] = (byte) (rgb >> 16); rgba[i++] = (byte) (rgb >> 8);
                rgba[i++] = (byte) rgb; rgba[i++] = (byte) 255;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
