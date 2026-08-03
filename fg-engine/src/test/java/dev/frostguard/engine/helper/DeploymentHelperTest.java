package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TesseractSettingsData.PageAnalysis;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

class DeploymentHelperTest {

    @Test
    void readsFinalSingleDigitCostWithoutHeroKnowledge() {
        DeploymentHelper helper = helperReturning("00:00:36", "9");

        DeploymentScreenRead read = helper.readScreen(DeploymentHelper.MAX_ATTACK_STAMINA_COST);

        assertEquals(36, read.travelTimeSeconds());
        assertEquals(9, read.staminaCost());
        assertFalse(read.staminaCostFallback());
        assertEquals(PageAnalysis.SINGLE_WORD, CommonOCRSettings.SPENT_STAMINA_SETTINGS.pageAnalysis());
    }

    @Test
    void fallsBackConservativelyWhenCostIsOutsideActionRange() {
        DeploymentHelper helper = helperReturning("", "22");

        DeploymentScreenRead read = helper.readScreen(DeploymentHelper.MAX_ATTACK_STAMINA_COST);

        assertEquals(0, read.travelTimeSeconds());
        assertEquals(10, read.staminaCost());
        assertTrue(read.staminaCostFallback());
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
}
