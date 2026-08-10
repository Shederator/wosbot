package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class DailyMissionPatternEvidenceTest {

    private static final PointData FULL_FRAME_TOP_LEFT = new PointData(0, 0);
    private static final PointData HEADER_BOTTOM_RIGHT = new PointData(720, 110);
    private static final PointData BOTTOM_BOTTOM_RIGHT = new PointData(720, 250);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // The app and other tests may already have loaded the native library in this JVM.
        }
    }

    @Test
    void dailyTabButtonMatchesBothTwoAndThreeTabLayouts() throws IOException {
        byte[] threeTabFrame = resource("/dailymission/three-tab-chapter-bottom-20260716.png");
        byte[] twoTabFrame = resource("/dailymission/two-tab-growth-bottom-20260716.png");

        ImageSearchResultData threeTabHit = locate(threeTabFrame,
                "/templates/dailymission/dailyMissionDailyTabButton.png", BOTTOM_BOTTOM_RIGHT);
        ImageSearchResultData twoTabHit = locate(twoTabFrame,
                "/templates/dailymission/dailyMissionDailyTabButton.png", BOTTOM_BOTTOM_RIGHT);

        assertTrue(threeTabHit.getMatchScore() >= 95,
                "Daily tab should match the three-tab layout: " + threeTabHit);
        assertTrue(twoTabHit.getMatchScore() >= 95,
                "Daily tab should match the two-tab layout: " + twoTabHit);
        assertTrue(threeTabHit.getPoint().getX() > twoTabHit.getPoint().getX() + 80,
                "Pattern must follow the shifted Daily tab instead of assuming a fixed coordinate");
    }

    @Test
    void chapterTitleCannotConfirmTheDailyScreen() throws IOException {
        byte[] chapterHeader = resource("/dailymission/three-tab-chapter-header-20260716.png");
        byte[] growthHeader = resource("/dailymission/two-tab-growth-header-20260716.png");

        double chapterScore = score(chapterHeader, "/templates/dailymission/dailyMissionScreenTitle.png",
                HEADER_BOTTOM_RIGHT);
        double growthScore = score(growthHeader, "/templates/dailymission/dailyMissionScreenTitle.png",
                HEADER_BOTTOM_RIGHT);

        assertTrue(chapterScore < 90, "Chapter title must not confirm Daily Missions: " + chapterScore);
        assertTrue(growthScore < 90, "Growth title must not confirm Daily Missions: " + growthScore);
    }

    @Test
    void dailyTitleConfirmsAnAutomaticallySelectedDailyScreen() throws IOException {
        byte[] dailyHeader = resource("/dailymission/selected-daily-header-20260716.png");

        double dailyScore = score(dailyHeader, "/templates/dailymission/dailyMissionScreenTitle.png",
                HEADER_BOTTOM_RIGHT);

        assertTrue(dailyScore >= 95, "Daily title should confirm an automatically selected Daily tab: " + dailyScore);
    }

    @Test
    void enabledDailyClaimsRemainDistinctFromTheDisabledClaimPattern() throws IOException {
        byte[] dailyClaims = resource("/dailymission/selected-daily-claims-20260716.png");

        double individualClaim = score(dailyClaims, "/templates/dailymission/claimButton.png",
                new PointData(720, 640));
        double claimAll = score(dailyClaims, "/templates/dailymission/claimAllButton.png",
                new PointData(720, 640));
        double disabledClaim = score(dailyClaims, "/templates/dailymission/claimButtonDisabled.png",
                new PointData(720, 640));

        assertTrue(individualClaim >= 95, "Enabled individual Claim should match strongly: " + individualClaim);
        assertTrue(claimAll >= 95, "Enabled Claim All should match strongly: " + claimAll);
        assertTrue(disabledClaim < 90, "Enabled Claim must not be classified as disabled: " + disabledClaim);
    }

    @Test
    void disabledChapterClaimIsExplicitlyRecognized() throws IOException {
        byte[] chapterFrame = resource("/dailymission/three-tab-chapter-bottom-20260716.png");

        ImageSearchResultData narrowClaimHit = locate(chapterFrame,
                "/templates/dailymission/claimButton.png", BOTTOM_BOTTOM_RIGHT);
        ImageSearchResultData disabledClaimHit = locate(chapterFrame,
                "/templates/dailymission/claimButtonDisabled.png", BOTTOM_BOTTOM_RIGHT);

        assertTrue(narrowClaimHit.getMatchScore() >= 90,
                "Regression frame should preserve the historical false-positive Claim hit: " + narrowClaimHit);
        assertTrue(disabledClaimHit.getMatchScore() >= 95,
                "Disabled Claim pattern should identify the grey button: " + disabledClaimHit);
        assertTrue(narrowClaimHit.getPoint().manhattanDistanceTo(disabledClaimHit.getPoint()) <= 20,
                "Disabled and narrow Claim patterns should refer to the same button");
        assertNotNull(disabledClaimHit.getTemplateSize(),
                "Matched template size should be available for bounded taps");
        assertEquals(165, disabledClaimHit.getTemplateSize().getWidth());
        assertEquals(80, disabledClaimHit.getTemplateSize().getHeight());
    }

    private static ImageSearchResultData locate(byte[] frame, String template, PointData bottomRight) {
        return OpenCvPatternLocator.locatePattern(frame, template, FULL_FRAME_TOP_LEFT, bottomRight, 0);
    }

    private static double score(byte[] frame, String template, PointData bottomRight) {
        return locate(frame, template, bottomRight).getMatchScore();
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = DailyMissionPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
