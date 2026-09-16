package dev.frostguard.tasks.events;

import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import static org.junit.jupiter.api.Assertions.*;

class FishingFrameTest {
    @BeforeAll
    static void loadNative() throws IOException {
        OpenCvPatternLocator.loadNativeLibrary();
    }

    @Test
    void identifiesTheHomeEntryAndOnlyTheSafeCastControls() throws IOException {
        assertHit("home", TemplatesEnum.FISHING_HOME_ICON, 480, 120, 620, 240);
        assertHit("event", TemplatesEnum.FISHING_TITLE, 90, 0, 500, 80);
        assertHit("event", TemplatesEnum.FISHING_ICE_BUTTON, 370, 1140, 670, 1240);
        assertHit("cast-dialog", TemplatesEnum.FISHING_NORMAL_CAST, 50, 760, 360, 875);
        assertHit("cast-dialog", TemplatesEnum.FISHING_NO_SPECIAL_ITEM, 100, 580, 650, 730);
        assertFalse(match("event", TemplatesEnum.FISHING_NORMAL_CAST, 50, 760, 360, 875).isFound());
    }

    @Test
    void identifiesTheHomeEntryBelowTheOldSearchBoundary() throws IOException {
        assertFalse(match("home-lower-entry", TemplatesEnum.FISHING_HOME_ICON, 475, 120, 630, 250).isFound());
        var area = CommonGameAreas.FISHING_HOME_ENTRY;
        try (var stream = FishingFrameTest.class.getResourceAsStream("/fishing/home-lower-entry.png")) {
            var result = OpenCvPatternLocator.locatePattern(Objects.requireNonNull(stream).readAllBytes(),
                    TemplatesEnum.FISHING_HOME_ICON, area.topLeft(), area.bottomRight(),
                    FishingMinigameRoutine.HOME_ENTRY_MATCH_THRESHOLD);
            assertTrue(result.isFound(), result.toString());
            assertTrue(result.getPoint().getY() > 200, result.toString());
        }
        try (var stream = FishingFrameTest.class.getResourceAsStream("/fishing/event.png")) {
            assertFalse(OpenCvPatternLocator.locatePattern(Objects.requireNonNull(stream).readAllBytes(),
                    TemplatesEnum.FISHING_HOME_ICON, area.topLeft(), area.bottomRight(),
                    FishingMinigameRoutine.HOME_ENTRY_MATCH_THRESHOLD).isFound());
        }
    }

    @Test
    void doesNotTreatTheRememberedClubTabAsTheFishingOverview() throws IOException {
        assertHit("club-tab", TemplatesEnum.FISHING_TITLE, 85, 0, 500, 80);
        var area = CommonGameAreas.FISHING_ICE_CAST_BUTTON;
        assertFalse(match("club-tab", TemplatesEnum.FISHING_ICE_BUTTON,
                area.topLeft().getX(), area.topLeft().getY(), area.bottomRight().getX(), area.bottomRight().getY()).isFound());
        assertHit("event", TemplatesEnum.FISHING_ICE_BUTTON,
                area.topLeft().getX(), area.topLeft().getY(), area.bottomRight().getX(), area.bottomRight().getY());
    }

    @Test
    void detectsTheHookAtItsActualPositionAndTheGameplayHud() throws IOException {
        var hook = match("active", TemplatesEnum.FISHING_HOOK, 170, 70, 640, 1280);
        assertTrue(hook.isFound(), hook.toString());
        assertTrue(hook.getPoint().getY() < 250, hook.toString());
        assertHit("active", TemplatesEnum.FISHING_PAUSE, 645, 0, 720, 85);
        assertFalse(match("event", TemplatesEnum.FISHING_PAUSE, 645, 0, 720, 85).isFound());
    }

    @Test
    void reopeningAfterReloadRevealsRecoveryControlsNotANewCast() throws IOException {
        assertHit("suspended-after-reload", TemplatesEnum.FISHING_TITLE, 85, 0, 500, 80);
        assertHit("suspended-after-reload", TemplatesEnum.FISHING_GO_FISH, 270, 1040, 465, 1130);
        assertFalse(match("suspended-after-reload", TemplatesEnum.FISHING_NORMAL_CAST, 50, 760, 360, 875).isFound());
        assertFalse(match("suspended-after-reload", TemplatesEnum.FISHING_ICE_BUTTON, 370, 1140, 675, 1240).isFound());
    }

    @Test
    void distinguishesPausedAndAlreadyPaidSuspendedStagesFromANewCast() throws IOException {
        assertHit("paused", TemplatesEnum.FISHING_CONTINUE, 390, 570, 535, 710);
        assertHit("paused", TemplatesEnum.FISHING_PAUSE_EXIT, 185, 570, 330, 710);
        assertHit("suspended", TemplatesEnum.FISHING_TITLE, 85, 0, 500, 80);
        assertHit("suspended", TemplatesEnum.FISHING_GO_FISH, 270, 1040, 465, 1130);
        assertFalse(match("event", TemplatesEnum.FISHING_GO_FISH, 270, 1040, 465, 1130).isFound());
        assertFalse(match("paused", TemplatesEnum.FISHING_NORMAL_CAST, 50, 760, 360, 875).isFound());
        assertFalse(match("suspended", TemplatesEnum.FISHING_NORMAL_CAST, 50, 760, 360, 875).isFound());
    }

    private static void assertHit(String frame, TemplatesEnum template, int x1, int y1, int x2, int y2)
            throws IOException {
        var result = match(frame, template, x1, y1, x2, y2);
        assertTrue(result.isFound(), template + ": " + result);
    }

    private static dev.frostguard.api.domain.ImageSearchResultData match(String frame,
            TemplatesEnum template, int x1, int y1, int x2, int y2) throws IOException {
        try (var stream = FishingFrameTest.class.getResourceAsStream("/fishing/" + frame + ".png")) {
            return OpenCvPatternLocator.locatePattern(Objects.requireNonNull(stream).readAllBytes(),
                    template, new PointData(x1, y1), new PointData(x2, y2), 90);
        }
    }
}
