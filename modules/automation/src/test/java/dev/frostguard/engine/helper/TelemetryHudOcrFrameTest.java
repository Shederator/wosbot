package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Reads power, coal and gems out of a real captured 720x1280 HUD frame using the exact regions and
 * OCR settings production uses, so the crop coordinates themselves are covered rather than a copy
 * of them. A test that restated the coordinates locally would keep passing if production's drifted.
 *
 * <p>The fixture is a genuine frame from a live account, irreversibly redacted before being
 * committed: the profile avatar (a personal photograph) and the world-chat lines (other players'
 * names and aliases) are flattened to a single flat colour. Verified at redaction time by comparing
 * distinct-colour counts per region — avatar 5,980 -> 1, chat 6,619 -> 1 — while the three metric
 * regions stayed byte-identical, so the evidence survives and the identifiers do not. Nothing in
 * the remaining frame identifies the operated account or any third party.
 */
class TelemetryHudOcrFrameTest {

    private static final String FIXTURE = "/telemetry/hud-power-coal-gems-20260820-redacted.png";

    /** The values visible on the fixture frame. */
    private static final String EXPECTED_POWER = "25,967,881";
    private static final String EXPECTED_COAL = "16.3M";
    private static final String EXPECTED_GEMS = "66,545";

    @Test
    void readsPowerFromARealFrameUsingTheProductionRegion() throws Exception {
        assertEquals(EXPECTED_POWER, read(CommonGameAreas.TELEMETRY_POWER_OCR_AREA, CommonOCRSettings.TELEMETRY_FULL_NUMBER_SETTINGS));
    }

    @Test
    void readsAbbreviatedCoalFromARealFrameUsingTheProductionRegion() throws Exception {
        // Coal is the one slot the game abbreviates, which is why it uses the K/M/B whitelist.
        assertEquals(EXPECTED_COAL, read(CommonGameAreas.TELEMETRY_COAL_OCR_AREA, CommonOCRSettings.TELEMETRY_ABBREVIATED_NUMBER_SETTINGS));
    }

    @Test
    void readsGemsFromARealFrameUsingTheProductionRegion() throws Exception {
        assertEquals(EXPECTED_GEMS, read(CommonGameAreas.TELEMETRY_GEMS_OCR_AREA, CommonOCRSettings.TELEMETRY_FULL_NUMBER_SETTINGS));
    }

    @Test
    void bothWhitelistsAgreeOnThisFramesPowerValue() throws Exception {
        // Records what this fixture actually shows rather than what would be convenient. The
        // separate FULL_NUMBER_SETTINGS exists because allowing K/M/B on a full-number slot has
        // been observed making Tesseract invent a digit ("56,256" read as "596,256") -- but this
        // frame's power value does NOT reproduce that, and both whitelists read it identically.
        //
        // So this test deliberately does not claim the two settings differ in general; that claim
        // would need a fixture captured on a value that actually triggers the misread, which this
        // one is not. Asserting a difference here would have passed only by luck and would have
        // been evidence of nothing.
        String withAbbreviatedSettings = read(CommonGameAreas.TELEMETRY_POWER_OCR_AREA, CommonOCRSettings.TELEMETRY_ABBREVIATED_NUMBER_SETTINGS);
        String withFullSettings = read(CommonGameAreas.TELEMETRY_POWER_OCR_AREA, CommonOCRSettings.TELEMETRY_FULL_NUMBER_SETTINGS);
        assertEquals(EXPECTED_POWER, withFullSettings);
        assertEquals(EXPECTED_POWER, withAbbreviatedSettings);
    }

    @Test
    void aWrongCropRegionDoesNotProduceTheExpectedValue() throws Exception {
        // Guards against the test passing for the wrong reason. If a badly-placed region still
        // returned the right number, these assertions would prove nothing about the coordinates.
        String shiftedDown = read(new dev.frostguard.api.domain.PointData(130, 148),
                new dev.frostguard.api.domain.PointData(272, 196),
                CommonOCRSettings.TELEMETRY_FULL_NUMBER_SETTINGS);
        assertNotEquals(EXPECTED_POWER, shiftedDown);
    }

    @Test
    void theRedactedRegionsCarryNoRecoverableDetail() throws Exception {
        // The privacy guarantee, asserted rather than asserted-in-prose: both redacted regions are
        // a single flat colour, so nothing about the avatar photo or the chat text survives.
        BufferedImage image = fixture();
        assertEquals(1, distinctColours(image, 0, 0, 100, 100), "avatar region is not flattened");
        assertEquals(1, distinctColours(image, 0, 1100, 720, 1180), "chat region is not flattened");
        // And the frame really is the standard layout these regions were measured against.
        assertEquals(720, image.getWidth());
        assertEquals(1280, image.getHeight());
    }

    @Test
    void theMetricRegionsSitOutsideEveryRedactedArea() throws Exception {
        // If a redaction ever overlapped a metric crop, the reads above would start failing for a
        // reason that has nothing to do with OCR. State the separation explicitly.
        assertTrue(CommonGameAreas.TELEMETRY_POWER_OCR_AREA.topLeft().getY() >= 40, "power crop overlaps the avatar band");
        assertTrue(CommonGameAreas.TELEMETRY_POWER_OCR_AREA.topLeft().getX() >= 100, "power crop overlaps the avatar band");
        assertTrue(CommonGameAreas.TELEMETRY_COAL_OCR_AREA.topLeft().getX() >= 100, "coal crop overlaps the avatar band");
        assertTrue(CommonGameAreas.TELEMETRY_GEMS_OCR_AREA.topLeft().getX() >= 100, "gems crop overlaps the avatar band");
        assertTrue(CommonGameAreas.TELEMETRY_POWER_OCR_AREA.bottomRight().getY() < 1100, "power crop overlaps the chat band");
    }

    private static int distinctColours(BufferedImage image, int x0, int y0, int x1, int y1) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                seen.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return seen.size();
    }

    private String read(AreaData area, OcrSettingsData settings) throws Exception {
        return OcrEngine.recognizeText(rgbaFrame(fixture()), area.topLeft(), area.bottomRight(), settings).trim();
    }

    private String read(PointData tl, PointData br, OcrSettingsData settings) throws Exception {
        return OcrEngine.recognizeText(rgbaFrame(fixture()), tl, br, settings).trim();
    }

    private BufferedImage fixture() throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(FIXTURE),
                "missing test fixture " + FIXTURE));
    }

    private RawImageData rgbaFrame(BufferedImage image) {
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
