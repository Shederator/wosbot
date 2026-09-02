package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * The other half of the quit-dialog evidence: screens where the dialog is NOT present.
 *
 * <p>{@code QuitGameDialogCancelButtonEvidenceTest} proves the template finds the real dialog. On
 * its own that is only half a claim -- a template matching everything would pass it too -- and a
 * false positive here is not harmless: the guard would tap Cancel's coordinate on a screen where
 * something else lives. These are real captures from the same account with no dialog up, so a hit
 * on any of them is a genuine false positive.
 *
 * <p>They are deliberately awkward frames rather than blank ones. The Labyrinth deploy screen
 * carries a large light panel with rounded buttons across the bottom, and Hero Recruitment carries
 * two big banner panels -- the closest things to a modal this UI produces.
 *
 * <p>Account details are blacked out of the top HUD strip, matching upstream's own fixture
 * redaction (#263); the dialog occupies the middle of the screen, so nothing relevant is covered.
 * A third candidate capture was discarded rather than redacted, because it carried other players'
 * chat names.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class QuitGameDialogNegativeEvidenceTest {

    private static final String TEMPLATE = "/templates/system/quitGameDialog.png";
    private static final String POSITIVE = "/system/quitGameDialog-liveAccount-20260819.png";
    private static final String[] NEGATIVES = {
        "/system/negative-labyrinthDeploy-20260821.png",
        "/system/negative-heroRecruitment-20260821.png",
    };

    /** Matches the production threshold used by QuitDialogGuard.dismissIfPresent. */
    private static final int THRESHOLD = 90;

    @BeforeAll
    static void loadOpenCv() {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError | IOException ignored) {
            // Already loaded by the app or another test in this JVM.
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = QuitGameDialogNegativeEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(in, "missing fixture " + path).readAllBytes();
        }
    }

    private static ImageSearchResultData search(String frameResource) throws IOException {
        return OpenCvPatternLocator.locatePattern(
                resource(frameResource),
                TEMPLATE,
                new PointData(0, 0),
                new PointData(720, 1280),
                THRESHOLD);
    }

    @Test
    void theTemplateStillFindsTheRealDialog() throws IOException {
        // Sanity anchor: if this fails, the negatives below prove nothing.
        assertTrue(search(POSITIVE).isFound(),
                "the positive fixture must still match, or these negatives are meaningless");
    }

    @Test
    void theTemplateDoesNotFireOnScreensWithoutTheDialog() throws IOException {
        for (String negative : NEGATIVES) {
            ImageSearchResultData hit = search(negative);
            assertFalse(hit.isFound(),
                    "QUIT_GAME_DIALOG falsely matched " + negative + " (" + hit + ") -- the guard "
                            + "would tap Cancel's coordinate on a screen with no dialog on it");
        }
    }

    @Test
    void theNegativesScoreWellBelowTheThreshold() throws IOException {
        // A near-miss is worth failing on even when it does not cross the line: it is the early
        // warning that the margin is thinner than a pass/fail result makes it look.
        for (String negative : NEGATIVES) {
            ImageSearchResultData hit = search(negative);
            assertTrue(hit.getMatchScore() < 80.0,
                    negative + " scored " + hit.getMatchScore() + " against a threshold of "
                            + THRESHOLD + " -- close enough that ordinary rendering variance "
                            + "could cross it");
        }
    }
}
