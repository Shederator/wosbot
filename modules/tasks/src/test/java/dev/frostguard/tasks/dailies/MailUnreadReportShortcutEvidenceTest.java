package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailUnreadReportShortcutEvidenceTest {

    private static final PointData HEADER_TOP_LEFT = new PointData(300, 0);
    private static final PointData HEADER_BOTTOM_RIGHT = new PointData(420, 95);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void detectsUnreadReportShortcutFromReportsTab() throws IOException {
        ImageSearchResultData result = locate("/mail/unread-report-shortcut-20260817.png");

        assertTrue(result.isFound(), "Unread report shortcut should be detected: " + result);
        assertTrue(result.getMatchScore() >= 90, "Shortcut should meet the runtime threshold: " + result);
    }

    @Test
    void rejectsOpenedReportContent() throws IOException {
        ImageSearchResultData result = locate("/mail/unread-report-open-20260817.png");

        assertFalse(result.isFound(), "Opened report content must not expose the shortcut: " + result);
    }

    @Test
    void detectsRemainingUnreadReportAfterReturningToSystemTab() throws IOException {
        ImageSearchResultData result = locate("/mail/unread-report-after-back-20260817.png");

        assertTrue(result.isFound(), "Remaining unread report shortcut should be detected: " + result);
        assertTrue(result.getMatchScore() >= 90, "Shortcut should meet the runtime threshold: " + result);
    }

    private static ImageSearchResultData locate(String frameResource) throws IOException {
        return OpenCvPatternLocator.locatePattern(
                resource(frameResource),
                TemplatesEnum.MAIL_UNREAD_REPORT_SHORTCUT,
                HEADER_TOP_LEFT,
                HEADER_BOTTOM_RIGHT,
                90);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = MailUnreadReportShortcutEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
