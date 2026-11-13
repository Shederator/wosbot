package dev.frostguard.tasks.city;

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
import dev.frostguard.vision.match.OpenCvPatternLocator;

class BuildingPrerequisiteFrameTest {

    private static final AreaData CONFIRM_AREA = new AreaData(
            new PointData(350, 900), new PointData(700, 1255));

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void barricadeNeedingTheFurnaceLevelUnderConstructionIsPrerequisiteBlocked() throws Exception {
        // Queue 1 upgrading the Furnace to Lv 16; the guide's Barricade requires Furnace Lv 16 (red X, Go, greyed Upgrade).
        byte[] encoded = resource("/city/barricade-furnace-prerequisite-20261028.png");
        ImageSearchResultData upgrade = locate(encoded, TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT, CONFIRM_AREA, 90);
        assertFalse(upgrade.isFound(), () -> "Greyed Upgrade must not confirm: " + upgrade);
        ImageSearchResultData go = locate(encoded, TemplatesEnum.GAME_HOME_SHORTCUTS_GO,
                UpgradeBuildingsRoutine.BUILDING_REQUIREMENT_GO_AREA_VALUE, UpgradeBuildingsRoutine.REQUIREMENT_GO_THRESHOLD);
        assertTrue(BuildingUpgradeConfirmationFlow.isPrerequisiteBlocked(go.isFound(), upgrade.getMatchScore()),
                () -> "go=" + go + "; upgrade=" + upgrade);
    }

    @Test
    void startableUpgradeDialogIsNotPrerequisiteBlocked() throws Exception {
        // Embassy with every requirement met: blue Upgrade, no Go button.
        byte[] encoded = resource("/city/fire-crystal-building-upgrade-ready-20260821.png");
        ImageSearchResultData upgrade = locate(encoded, TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT, CONFIRM_AREA, 90);
        assertTrue(upgrade.isFound(), () -> "Startable Upgrade: " + upgrade);
        ImageSearchResultData go = locate(encoded, TemplatesEnum.GAME_HOME_SHORTCUTS_GO,
                UpgradeBuildingsRoutine.BUILDING_REQUIREMENT_GO_AREA_VALUE, UpgradeBuildingsRoutine.REQUIREMENT_GO_THRESHOLD);
        assertFalse(go.isFound(), () -> "No requirement Go button: " + go);
    }

    @Test
    void cityViewWithoutTheUpgradeDialogIsNotPrerequisiteBlocked() throws Exception {
        byte[] encoded = resource("/city/furnace-detail-upgrade-20260927.png");
        ImageSearchResultData upgrade = locate(encoded, TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT, CONFIRM_AREA, 90);
        ImageSearchResultData go = locate(encoded, TemplatesEnum.GAME_HOME_SHORTCUTS_GO,
                UpgradeBuildingsRoutine.BUILDING_REQUIREMENT_GO_AREA_VALUE, UpgradeBuildingsRoutine.REQUIREMENT_GO_THRESHOLD);
        assertFalse(BuildingUpgradeConfirmationFlow.isPrerequisiteBlocked(go.isFound(), upgrade.getMatchScore()),
                () -> "go=" + go + "; upgrade=" + upgrade);
    }

    private static ImageSearchResultData locate(byte[] encoded, TemplatesEnum template, AreaData area, int threshold) {
        return OpenCvPatternLocator.locatePattern(encoded, template, area.topLeft(), area.bottomRight(), threshold);
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream stream = BuildingPrerequisiteFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing " + path).readAllBytes();
        }
    }
}
