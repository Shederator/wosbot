package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WelcomeBackPromptPatternEvidenceTest {

    private static final PointData TITLE_TOP_LEFT = new PointData(170, 170);
    private static final PointData TITLE_BOTTOM_RIGHT = new PointData(550, 340);
    private static final PointData CONFIRM_TOP_LEFT = new PointData(150, 880);
    private static final PointData CONFIRM_BOTTOM_RIGHT = new PointData(570, 1140);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void detectsTitleAndConcreteConfirmTargetFromSavedFrame() throws IOException {
        Evidence evidence = inspect("/startup/welcome-back-dialog-20260821.png");

        assertTrue(evidence.title().isFound());
        assertTrue(evidence.confirm().isFound());
        assertTrue(evidence.confirm().hasMatchedArea());
    }

    @Test
    void rejectsMandatoryUpdateAndResourceDownloadDialogs() throws IOException {
        for (String path : new String[] {
                "/startup/mandatory-update-dialog-20260820.png",
                "/startup/resource-download-prompt-20260817.png" }) {
            Evidence evidence = inspect(path);
            assertFalse(evidence.title().isFound() && evidence.confirm().isFound(), path);
        }
    }

    private static Evidence inspect(String path) throws IOException {
        byte[] frame = bytes(path);
        ImageSearchResultData title = OpenCvPatternLocator.locatePattern(
                frame, TemplatesEnum.GAME_START_WELCOME_BACK_TITLE.getTemplate(),
                TITLE_TOP_LEFT, TITLE_BOTTOM_RIGHT, 90);
        ImageSearchResultData confirm = OpenCvPatternLocator.locatePattern(
                frame, TemplatesEnum.GAME_START_WELCOME_BACK_CONFIRM_BUTTON.getTemplate(),
                CONFIRM_TOP_LEFT, CONFIRM_BOTTOM_RIGHT, 90);
        return new Evidence(title, confirm);
    }

    private static byte[] bytes(String path) throws IOException {
        try (InputStream stream = WelcomeBackPromptPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }

    private record Evidence(ImageSearchResultData title, ImageSearchResultData confirm) {
    }
}
