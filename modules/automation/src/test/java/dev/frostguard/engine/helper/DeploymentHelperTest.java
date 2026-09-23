package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

class DeploymentHelperTest {

    @Test
    void parsesTheSelectedFormationTroopCountFromTheDeploymentFraction() {
        assertEquals(57_785, DeploymentHelper.parseSelectedTroopCount("57,785/57,785"));
        assertEquals(120_000, DeploymentHelper.parseSelectedTroopCount("120K / 120K"));
        assertEquals(-1, DeploymentHelper.parseSelectedTroopCount("unreadable"));
    }

    @Test
    void readsFinalSingleDigitCostWithoutHeroKnowledge() {
        DeploymentHelper helper = helperReturning("00:00:36", "9");

        DeploymentScreenRead read = helper.readScreen(DeploymentHelper.MAX_ATTACK_STAMINA_COST);

        assertEquals(36, read.travelTimeSeconds());
        assertEquals(9, read.staminaCost());
        assertFalse(read.staminaCostFallback());
        assertEquals(TextLayout.SINGLE_LINE, CommonOCRSettings.SPENT_STAMINA_SETTINGS.textLayout());
    }

    @Test
    void fallsBackConservativelyWhenCostIsOutsideActionRange() {
        DeploymentHelper helper = helperReturning("", "22");

        DeploymentScreenRead read = helper.readScreen(DeploymentHelper.MAX_ATTACK_STAMINA_COST);

        assertEquals(0, read.travelTimeSeconds());
        assertEquals(10, read.staminaCost());
        assertTrue(read.staminaCostFallback());
    }

    @Test
    void identifiesTheFiveMinuteRallyOptionFromItsGreenTick() {
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        var fiveMinutes = CommonGameAreas.RALLY_SET_TIME_CHECKBOXES[1];
        Graphics2D graphics = frame.createGraphics();
        graphics.setColor(Color.GREEN);
        graphics.fillRect(
                fiveMinutes.origin().getX(),
                fiveMinutes.origin().getY(),
                fiveMinutes.extent().getX() - fiveMinutes.origin().getX(),
                fiveMinutes.extent().getY() - fiveMinutes.origin().getY());
        graphics.dispose();

        assertEquals(5, DeploymentHelper.selectedRallySetTimeMinutes(frame));
    }

    @Test
    void mapsTheBearLeftOptionToFiveMinutesAndRightOptionToTenMinutes() {
        BufferedImage fiveMinuteFrame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        paintGreen(fiveMinuteFrame, CommonGameAreas.BEAR_RALLY_SET_TIME_CHECKBOXES[0]);
        BufferedImage tenMinuteFrame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        paintGreen(tenMinuteFrame, CommonGameAreas.BEAR_RALLY_SET_TIME_CHECKBOXES[1]);

        assertEquals(5, DeploymentHelper.selectedBearRallySetTimeMinutes(fiveMinuteFrame));
        assertEquals(10, DeploymentHelper.selectedBearRallySetTimeMinutes(tenMinuteFrame));
    }

    @Test
    void rallyTimerSelectionFailsClosedWhenNoTickIsVisible() {
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);

        assertEquals(-1, DeploymentHelper.selectedRallySetTimeMinutes(frame));
    }

    private DeploymentHelper helperReturning(String travelText, String costText) {
        ResilientOcrExecutor<Integer> integers = new ResilientOcrExecutor<>(
                (config, topLeft, bottomRight) -> costText);
        ResilientOcrExecutor<Duration> durations = new ResilientOcrExecutor<>(
                (config, topLeft, bottomRight) -> travelText);
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setDisplayName("test");
        return new DeploymentHelper(null, "test", null, integers, durations, profile);
    }

    private void paintGreen(BufferedImage frame, dev.frostguard.api.domain.AreaData area) {
        Graphics2D graphics = frame.createGraphics();
        graphics.setColor(Color.GREEN);
        graphics.fillRect(
                area.origin().getX(),
                area.origin().getY(),
                area.extent().getX() - area.origin().getX(),
                area.extent().getY() - area.origin().getY());
        graphics.dispose();
    }
}
