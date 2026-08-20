package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.error.ActionRequiredContext;
import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MandatoryUpdateScreenClassifierTest {

    private static final PointData TITLE_AREA_TOP_LEFT = new PointData(250, 250);
    private static final PointData TITLE_AREA_BOTTOM_RIGHT = new PointData(470, 350);
    private static final PointData BUTTON_AREA_TOP_LEFT = new PointData(200, 850);
    private static final PointData BUTTON_AREA_BOTTOM_RIGHT = new PointData(520, 1050);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void detectsCurrentMandatoryUpdateDialogFromSavedFrame() throws IOException {
        MandatoryUpdateScreenClassifier.Evidence evidence = inspect(
                "/startup/mandatory-update-dialog-20260820.png");

        assertTrue(evidence.detected(), evidence::technicalSummary);
        assertTrue(evidence.titlePatternFound(), evidence::technicalSummary);
        assertTrue(evidence.buttonPatternFound(), evidence::technicalSummary);
    }

    @Test
    void rejectsResourceDownloadDialogHandledByExistingFlow() throws IOException {
        MandatoryUpdateScreenClassifier.Evidence evidence = inspect(
                "/startup/resource-download-prompt-20260817.png");

        assertFalse(evidence.detected(), evidence::technicalSummary);
        assertFalse(evidence.titlePatternFound(), evidence::technicalSummary);
        assertFalse(evidence.buttonPatternFound(), evidence::technicalSummary);
    }

    @Test
    void rejectsMatchingColorsAndLayoutWithoutUpdateIdentityPattern() throws IOException {
        MandatoryUpdateScreenClassifier.Evidence evidence = MandatoryUpdateScreenClassifier.inspect(
                image("/startup/mandatory-update-dialog-20260820.png"), false, 0, true, 100);

        assertFalse(evidence.detected(), evidence::technicalSummary);
    }

    @Test
    void refusesDialogTapWhenConcreteUpdateButtonPatternIsMissing() throws IOException {
        MandatoryUpdateScreenClassifier.Evidence evidence = MandatoryUpdateScreenClassifier.inspect(
                image("/startup/mandatory-update-dialog-20260820.png"), true, 100, false, 0);

        assertFalse(evidence.detected(), evidence::technicalSummary);
    }

    @Test
    void playStoreRedirectRequiresForegroundPackageAndFreshPostClickFrame() {
        assertFalse(InitializeRoutine.isVerifiedPlayStoreRedirect(false, false));
        assertFalse(InitializeRoutine.isVerifiedPlayStoreRedirect(true, false));
        assertFalse(InitializeRoutine.isVerifiedPlayStoreRedirect(false, true));
        assertTrue(InitializeRoutine.isVerifiedPlayStoreRedirect(true, true));
    }

    @Test
    void storeRedirectPathHasNoStoreLanguageTemplateMapping() throws IOException {
        assertFalse(java.util.Arrays.stream(TemplatesEnum.values())
                .map(Enum::name)
                .anyMatch(name -> name.startsWith("GAME_START_STORE_")));
        try (InputStream stream = MandatoryUpdateScreenClassifierTest.class
                .getResourceAsStream("/config/templates.properties")) {
            String mappings = new String(Objects.requireNonNull(stream).readAllBytes(),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(mappings.contains("googlePlay"));
        }
    }

    @Test
    void playStoreCooldownCarriesStableHumanReadableIncidentContext() {
        LocalDateTime retryAt = LocalDateTime.of(2026, 8, 21, 1, 30);

        ProfileCooldownException cooldown = InitializeRoutine.playStoreRedirectCooldown(retryAt);
        ActionRequiredContext context = cooldown.getActionRequiredContext().orElseThrow();

        assertEquals(retryAt, cooldown.getRetryAt());
        assertEquals("Google Play is waiting for the game update to be completed.", cooldown.getMessage());
        assertEquals("startup.play-store-redirect", context.signature());
        assertEquals("Complete the game update in Google Play", context.title());
        assertEquals("Google Play foreground package after the in-game update action",
                context.observedState());
        assertTrue(context.lastAction().contains(
                "captured a fresh frame while Google Play was foreground"));
        assertFalse(context.observedState().contains("Pattern"));
    }

    private static MandatoryUpdateScreenClassifier.Evidence inspect(String path) throws IOException {
        ImageSearchResultData title = OpenCvPatternLocator.locatePattern(
                bytes(path),
                TemplatesEnum.GAME_START_MANDATORY_UPDATE_TITLE.getTemplate(),
                TITLE_AREA_TOP_LEFT,
                TITLE_AREA_BOTTOM_RIGHT,
                90);
        ImageSearchResultData button = OpenCvPatternLocator.locatePattern(
                bytes(path),
                TemplatesEnum.GAME_START_MANDATORY_UPDATE_BUTTON.getTemplate(),
                BUTTON_AREA_TOP_LEFT,
                BUTTON_AREA_BOTTOM_RIGHT,
                90);
        return MandatoryUpdateScreenClassifier.inspect(
                image(path), title.isFound(), title.getMatchScore(),
                button.isFound(), button.getMatchScore());
    }

    private static BufferedImage image(String path) throws IOException {
        try (InputStream stream = MandatoryUpdateScreenClassifierTest.class.getResourceAsStream(path)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing test resource: " + path));
        }
    }

    private static byte[] bytes(String path) throws IOException {
        try (InputStream stream = MandatoryUpdateScreenClassifierTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
