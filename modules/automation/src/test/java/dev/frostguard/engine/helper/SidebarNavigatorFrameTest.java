package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarRowAction;
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
    void detectsPetAdventureInCurrentAccountDailyFrame() throws IOException {
        byte[] frame = resource("/navigation/sidebar-update-20260818/daily-current-account.png");

        assertDestination(frame, TemplatesEnum.SIDEBAR_DAILY_PET_ADVENTURE, 46, 487);
    }

    @Test
    void detectsDynamicLighthouseIntelRowFromItsLeftIcon() throws IOException {
        byte[] frame = resource("/navigation/sidebar-update-20260821/daily-dynamic-intel.png");

        assertDestination(frame, TemplatesEnum.SIDEBAR_DAILY_LIGHTHOUSE_INTEL, 46, 720);
    }

    @Test
    void waitsTwoSecondsAfterEverySidebarScanSwipe() {
        assertEquals(2_000, SidebarNavigator.SCROLL_SETTLE_MS);
    }

    @Test
    void detectsResearchCenterInTheRealCityFrame() throws IOException {
        byte[] frame = resource("/navigation/sidebar-update-20260817/city.png");

        assertDestination(frame, TemplatesEnum.GAME_HOME_SHORTCUTS_RESEARCH_CENTER, 47, 821);
    }

    @Test
    void derivesTheGoControlFromTheDetectedRowInsteadOfMatchingAnAmbiguousArrow() {
        ImageSearchResultData icon = ImageSearchResultData.hit(46, 373, 99.0, 44, 44);

        assertEquals(AreaData.of(361, 343, 440, 403), SidebarNavigator.rowActionAreaFor(icon));
    }

    @Test
    void detectsCurrentDailyRowsFromTheirLeftIcons() throws IOException {
        byte[] top = resource("/navigation/sidebar-dynamic-20260821/daily-top.png");

        assertDestination(top, TemplatesEnum.SIDEBAR_DAILY_ARENA, 46, 373);
        assertDestination(top, TemplatesEnum.SIDEBAR_DAILY_PET_ADVENTURE, 46, 487);
        assertDestination(top, TemplatesEnum.SIDEBAR_DAILY_LIFE_ESSENCE, 46, 712);
    }

    @Test
    void verifiesTheExpectedActionInsideTheIconAnchoredRow() throws IOException {
        byte[] frame = resource("/navigation/sidebar-dynamic-20260821/daily-top.png");

        assertRowAction(frame, SidebarDestination.ARENA, SidebarRowAction.GO);
        assertRowAction(frame, SidebarDestination.LAND_OF_HEROES, SidebarRowAction.CLAIM);
        assertRowAction(frame, SidebarDestination.LIFE_ESSENCE, SidebarRowAction.CLAIM);
    }

    @Test
    void verifiesGoActionsAcrossEarlierDailyAndCityFrames() throws IOException {
        byte[] daily = resource("/navigation/sidebar-update-20260817/daily-top.png");
        byte[] city = resource("/navigation/sidebar-update-20260817/city.png");

        assertRowAction(daily, SidebarDestination.ARENA, SidebarRowAction.GO);
        assertRowAction(daily, SidebarDestination.PET_ADVENTURE, SidebarRowAction.GO);
        assertRowAction(daily, SidebarDestination.LAND_OF_HEROES, SidebarRowAction.GO);
        assertRowAction(daily, SidebarDestination.LIFE_ESSENCE, SidebarRowAction.GO);
        assertRowAction(city, SidebarDestination.RESEARCH_CENTER, SidebarRowAction.GO);
    }

    @Test
    void doesNotConfuseTrekSuppliesWithPersistentDailyRows() throws IOException {
        for (String frameName : new String[] { "daily-top.png", "daily-middle.png", "daily-bottom.png" }) {
            byte[] frame = resource("/navigation/sidebar-update-20260817/" + frameName);
            ImageSearchResultData hit = OpenCvPatternLocator.locatePattern(frame,
                    TemplatesEnum.TUNDRA_TREK_SUPPLIES,
                    CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.topLeft(),
                    CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.bottomRight(), 88);

            assertFalse(hit.isFound(), () -> "Trek Supplies false positive in " + frameName + ": " + hit);
        }
    }

    private void assertDestination(byte[] frame, TemplatesEnum template, int expectedX, int expectedY) {
        ImageSearchResultData hit = locate(frame, template, CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN);

        assertTrue(hit.isFound(), () -> "Expected sidebar destination template " + template);
        assertTrue(Math.abs(hit.getPoint().getX() - expectedX) <= 2, () -> template + " x=" + hit.getPoint());
        assertTrue(Math.abs(hit.getPoint().getY() - expectedY) <= 2, () -> template + " y=" + hit.getPoint());
    }

    private void assertRowAction(byte[] frame, SidebarDestination destination, SidebarRowAction action) {
        ImageSearchResultData icon = locate(frame, destination.rowIcon(), CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN);
        assertTrue(icon.isFound(), () -> "Missing row icon for " + destination);
        AreaData rowActionArea = SidebarNavigator.rowActionAreaFor(icon);
        boolean found = false;
        for (TemplatesEnum template : action.templates()) {
            if (locate(frame, template, rowActionArea).isFound()) {
                found = true;
                break;
            }
        }
        assertTrue(found, () -> "Missing " + action + " in row " + destination);
    }

    private ImageSearchResultData locate(byte[] frame, TemplatesEnum template, AreaData area) {
        return OpenCvPatternLocator.locatePattern(frame, template,
                area.topLeft(), area.bottomRight(), 88);
    }

    private byte[] resource(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
