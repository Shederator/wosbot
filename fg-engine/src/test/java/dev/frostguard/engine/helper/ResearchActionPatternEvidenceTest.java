package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class ResearchActionPatternEvidenceTest {

    private static final PointData ACTION_TOP_LEFT = new PointData(500, 1135);
    private static final PointData ACTION_BOTTOM_RIGHT = new PointData(710, 1250);
    private static final PointData REPLENISH_TOP_LEFT = new PointData(180, 1070);
    private static final PointData REPLENISH_BOTTOM_RIGHT = new PointData(535, 1195);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // The app and other frame tests may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void helpPatternIdentifiesThePreHelpState() throws IOException {
        byte[] helpFrame = resource("/research/help-short-20260716.png");
        byte[] completedFrame = resource("/research/help-completed-20260716.png");
        byte[] speedupFrame = resource("/research/speedup-running-20260716.png");

        double helpOnHelp = score(helpFrame, "/templates/research/helpButton.png");
        double helpOnCompleted = score(completedFrame, "/templates/research/helpButton.png");
        double helpOnSpeedup = score(speedupFrame, "/templates/research/helpButton.png");

        assertTrue(helpOnHelp >= 95, "Help pattern should match the Help frame strongly: " + helpOnHelp);
        assertTrue(helpOnCompleted < 90,
                "Help pattern must not match the completed research tree: " + helpOnCompleted);
        assertTrue(helpOnSpeedup < 90,
                "Help pattern must not match the running Speedup button: " + helpOnSpeedup);
    }

    @Test
    void speedupPatternSeparatesRunningFromInstantlyCompletedResearch() throws IOException {
        byte[] helpFrame = resource("/research/help-short-20260716.png");
        byte[] completedFrame = resource("/research/help-completed-20260716.png");
        byte[] speedupFrame = resource("/research/speedup-running-20260716.png");

        double speedupOnSpeedup = score(speedupFrame, "/templates/research/speedupButton.png");
        double speedupOnCompleted = score(completedFrame, "/templates/research/speedupButton.png");
        double speedupOnHelp = score(helpFrame, "/templates/research/speedupButton.png");

        assertTrue(speedupOnSpeedup >= 95,
                "Speedup pattern should match running research strongly: " + speedupOnSpeedup);
        assertTrue(speedupOnCompleted < 90,
                "Speedup pattern must not match the completed research tree: " + speedupOnCompleted);
        assertTrue(speedupOnHelp < 90,
                "Speedup pattern must not match the Help button: " + speedupOnHelp);
    }

    @Test
    void replenishPatternIdentifiesTheSharedResourceShortfallFlow() throws IOException {
        byte[] shortfallFrame = resource("/research/resource-shortfall-20260717.png");
        byte[] replenishFrame = resource("/research/resource-replenish-20260717.png");
        byte[] confirmFrame = resource("/research/resource-confirm-20260717.png");

        double replenishOnReplenish = score(replenishFrame, "/templates/home/camp/replenishall.png",
                REPLENISH_TOP_LEFT, REPLENISH_BOTTOM_RIGHT);
        double replenishOnShortfall = score(shortfallFrame, "/templates/home/camp/replenishall.png",
                REPLENISH_TOP_LEFT, REPLENISH_BOTTOM_RIGHT);
        double replenishOnConfirm = score(confirmFrame, "/templates/home/camp/replenishall.png",
                REPLENISH_TOP_LEFT, REPLENISH_BOTTOM_RIGHT);

        assertTrue(replenishOnReplenish >= 90,
                "Replenish All pattern should match the Obtain more frame: " + replenishOnReplenish);
        assertTrue(replenishOnShortfall < 90,
                "Replenish All pattern must not match the research detail: " + replenishOnShortfall);
        assertTrue(replenishOnConfirm < 90,
                "Replenish All pattern must not match the confirmation dialog: " + replenishOnConfirm);
    }

    private static double score(byte[] frame, String template) {
        return score(frame, template, ACTION_TOP_LEFT, ACTION_BOTTOM_RIGHT);
    }

    private static double score(byte[] frame, String template, PointData topLeft, PointData bottomRight) {
        ImageSearchResultData result = OpenCvPatternLocator.locatePattern(
                frame, template, topLeft, bottomRight, 0);
        return result.getMatchScore();
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = ResearchActionPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
