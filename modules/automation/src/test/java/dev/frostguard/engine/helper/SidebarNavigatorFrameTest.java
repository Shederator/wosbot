package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class SidebarNavigatorFrameTest {

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library in this JVM.
        }
    }

    @Test
    void detectsDailyDestinationIconsInTheRealTopFrame() throws IOException {
        byte[] frame = resource("/navigation/sidebar-update-20260817/daily-top.png");

        assertDestination(frame, TemplatesEnum.SIDEBAR_DAILY_ARENA, 46, 373);
        assertDestination(frame, TemplatesEnum.SIDEBAR_DAILY_LAND_OF_HEROES, 46, 599);
        assertDestination(frame, TemplatesEnum.SIDEBAR_DAILY_LIFE_ESSENCE, 46, 712);
    }

    @Test
    void detectsResearchCenterInTheRealCityFrame() throws IOException {
        byte[] frame = resource("/navigation/sidebar-update-20260817/city.png");

        assertDestination(frame, TemplatesEnum.GAME_HOME_SHORTCUTS_RESEARCH_CENTER, 47, 821);
    }

    @Test
    void derivesTheGoControlFromTheDetectedRowInsteadOfMatchingAnAmbiguousArrow() {
        ImageSearchResultData icon = ImageSearchResultData.hit(46, 373, 99.0, 44, 44);

        assertEquals(AreaData.of(383, 348, 429, 398), SidebarNavigator.goButtonFor(icon));
    }

    private void assertDestination(byte[] frame, TemplatesEnum template, int expectedX, int expectedY) {
        ImageSearchResultData hit = OpenCvPatternLocator.locatePattern(frame, template,
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.topLeft(),
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.bottomRight(), 88);

        assertTrue(hit.isFound(), () -> "Expected sidebar destination template " + template);
        assertTrue(Math.abs(hit.getPoint().getX() - expectedX) <= 2, () -> template + " x=" + hit.getPoint());
        assertTrue(Math.abs(hit.getPoint().getY() - expectedY) <= 2, () -> template + " y=" + hit.getPoint());
    }

    private byte[] resource(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
