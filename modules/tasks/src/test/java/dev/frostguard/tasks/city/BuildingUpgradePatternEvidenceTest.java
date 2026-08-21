package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingUpgradePatternEvidenceTest {

    private static final String FRAME = "/city/fire-crystal-building-upgrade-ready-20260821.png";
    private static final PointData ACTION_TOP_LEFT = new PointData(350, 900);
    private static final PointData ACTION_BOTTOM_RIGHT = new PointData(700, 1255);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void detectsFireCrystalUpgradeActionAtItsObservedLowerPosition() throws IOException {
        ImageSearchResultData hit = locate(ACTION_TOP_LEFT, ACTION_BOTTOM_RIGHT);

        assertTrue(hit.isFound(), "The supplied Fire Crystal dialog should expose the Upgrade action: " + hit);
        assertTrue(hit.getMatchScore() >= 90, "The Upgrade label should meet the runtime threshold: " + hit);
        assertTrue(hit.getPoint().getY() > 1080,
                "The fixture must preserve the Fire Crystal layout below the obsolete fixed tap: " + hit);
    }

    @Test
    void rejectsAdjacentPremiumFinishAction() throws IOException {
        ImageSearchResultData hit = locate(new PointData(20, 900), new PointData(350, 1255));

        assertFalse(hit.isFound(), "The premium Finish action must not be accepted as Upgrade: " + hit);
    }

    private ImageSearchResultData locate(PointData topLeft, PointData bottomRight) throws IOException {
        return OpenCvPatternLocator.locatePattern(
                resource(FRAME),
                TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT,
                topLeft,
                bottomRight,
                90);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = BuildingUpgradePatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
