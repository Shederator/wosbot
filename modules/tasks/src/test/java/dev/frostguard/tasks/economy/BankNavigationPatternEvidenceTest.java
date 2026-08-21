package dev.frostguard.tasks.economy;

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

class BankNavigationPatternEvidenceTest {

    private static final PointData TAB_BAR_TOP_LEFT = new PointData(0, 80);
    private static final PointData TAB_BAR_BOTTOM_RIGHT = new PointData(720, 190);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void rejectsDealsCarouselBeforeBankTabIsVisible() throws IOException {
        ImageSearchResultData result = locate("/bank/deals-carousel-before-bank-20260817.png");

        assertFalse(result.isFound(), "Hidden bank tab must not produce a clickable candidate: " + result);
    }

    @Test
    void detectsBankOnlyAfterCarouselExposesItsTab() throws IOException {
        ImageSearchResultData result = locate("/bank/deals-carousel-bank-visible-20260817.png");

        assertTrue(result.isFound(), "Visible bank tab should be detected from the live frame: " + result);
        assertTrue(result.getMatchScore() >= 90, "Visible bank tab should meet the runtime threshold: " + result);
        assertTrue(result.getPoint().getY() >= TAB_BAR_TOP_LEFT.getY()
                        && result.getPoint().getY() <= TAB_BAR_BOTTOM_RIGHT.getY(),
                "The detected candidate must remain inside the deals tab bar: " + result);
    }

    private static ImageSearchResultData locate(String frameResource) throws IOException {
        return OpenCvPatternLocator.locatePattern(
                resource(frameResource),
                TemplatesEnum.EVENTS_DEALS_BANK,
                TAB_BAR_TOP_LEFT,
                TAB_BAR_BOTTOM_RIGHT,
                90);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = BankNavigationPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
