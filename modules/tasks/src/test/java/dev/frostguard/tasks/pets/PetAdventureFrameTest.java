package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Saved Pet Adventure frames from 2026-10-01. After Start, In Adventure is
 * success. Map pins with a countdown pill are already running.
 */
class PetAdventureFrameTest {

    private static final PointData SCREEN_ORIGIN = new PointData(0, 0);
    private static final PointData SCREEN_LIMIT = new PointData(720, 1280);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library.
        }
    }

    @Test
    void inAdventureOverlayIsPresentOnTheThreeStartOutcomeFrames() throws IOException {
        for (String frame : List.of(
                "in-adventure-lv3.png",
                "in-adventure-lv2.png",
                "in-adventure-lv3-evening.png")) {
            ImageSearchResultData overlay = locate(frame, TemplatesEnum.PETS_CHEST_IN_ADVENTURE);
            assertTrue(PetAdventureDecisions.inAdventureOverlay(overlay),
                    () -> frame + " score=" + overlay.getMatchPercentage());
        }
    }

    @Test
    void staminaObtainMoreIsNotInAdventure() throws IOException {
        ImageSearchResultData overlay = locate("stamina-obtain-more.png",
                TemplatesEnum.PETS_CHEST_IN_ADVENTURE);
        assertFalse(PetAdventureDecisions.inAdventureOverlay(overlay),
                () -> "score=" + overlay.getMatchPercentage());
    }

    @Test
    void idleMapHasNoRunningTimers() throws IOException {
        byte[] encoded = encodedFrom("/live-regressions-20260818/pet-adventure.png");
        List<ImageSearchResultData> timers = timers(encoded);
        assertEquals(List.of(), timers, () -> "timers=" + describe(timers));
    }

    @Test
    void occupiedMapSkipsPinsWithTimersAndLeavesTheIdleOrangeChest() throws IOException {
        byte[] encoded = encoded("map-occupied-and-idle.png");
        List<ImageSearchResultData> timers = timers(encoded);
        assertEquals(2, timers.size(), () -> "timers=" + describe(timers));

        List<ImageSearchResultData> idle = new ArrayList<>();
        List<ImageSearchResultData> occupied = new ArrayList<>();
        for (TemplatesEnum colour : List.of(
                TemplatesEnum.PETS_CHEST_RED,
                TemplatesEnum.PETS_CHEST_PURPLE,
                TemplatesEnum.PETS_CHEST_BLUE)) {
            for (ImageSearchResultData chest : chests(encoded, colour)) {
                if (PetAdventureDecisions.occupiedByTimer(chest.getPoint(), timers)) {
                    occupied.add(chest);
                } else {
                    idle.add(chest);
                }
            }
        }

        assertFalse(idle.isEmpty(), () -> "idle=" + describe(idle) + " occupied=" + describe(occupied));
        assertTrue(idle.stream().anyMatch(hit -> hit.getHitX() > 500),
                () -> "expected idle orange on the right: " + describe(idle));
        for (ImageSearchResultData chest : idle) {
            assertFalse(PetAdventureDecisions.occupiedByTimer(chest.getPoint(), timers),
                    () -> "idle pin still paired with a timer: " + describe(List.of(chest))
                            + " timers=" + describe(timers));
        }
    }

    private ImageSearchResultData locate(String frame, TemplatesEnum template) throws IOException {
        return OpenCvPatternLocator.locatePattern(
                encoded(frame), template, SCREEN_ORIGIN, SCREEN_LIMIT,
                PetAdventureDecisions.TEMPLATE_THRESHOLD);
    }

    private List<ImageSearchResultData> timers(byte[] encoded) {
        return hits(OpenCvPatternLocator.locateAllPatterns(
                encoded, TemplatesEnum.PETS_CHEST_ADVENTURE_TIMER,
                SCREEN_ORIGIN, SCREEN_LIMIT, PetAdventureDecisions.TIMER_THRESHOLD, 5));
    }

    private List<ImageSearchResultData> chests(byte[] encoded, TemplatesEnum colour) {
        return hits(OpenCvPatternLocator.locateAllPatterns(
                encoded, colour, SCREEN_ORIGIN, SCREEN_LIMIT,
                PetAdventureDecisions.TEMPLATE_THRESHOLD, 3));
    }

    private List<ImageSearchResultData> hits(List<ImageSearchResultData> found) {
        if (found == null) {
            return List.of();
        }
        return found.stream().filter(ImageSearchResultData::isFound).toList();
    }

    private String describe(List<ImageSearchResultData> hits) {
        StringBuilder text = new StringBuilder();
        for (ImageSearchResultData hit : hits) {
            text.append('@').append(hit.getHitX()).append(',').append(hit.getHitY())
                    .append('=').append(String.format("%.1f", hit.getMatchPercentage()))
                    .append(' ');
        }
        return text.toString();
    }

    private byte[] encoded(String frame) throws IOException {
        return encodedFrom("/pets/" + frame);
    }

    private byte[] encodedFrom(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing frame: " + path).readAllBytes();
        }
    }
}
