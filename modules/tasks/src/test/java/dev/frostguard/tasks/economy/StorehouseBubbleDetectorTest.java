package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class StorehouseBubbleDetectorTest {

    @Test
    void acceptsTheDayCrateBubbleAsChest() throws IOException {
        List<StorehouseBubbleDetector.Candidate> accepted = StorehouseBubbleDetector.locate(load("day-crate-ready.png"));
        assertEquals(1, accepted.size());
        assertEquals(StorehouseBubbleDetector.Kind.CHEST, accepted.getFirst().kind());
    }

    @Test
    void acceptsTheNightCrateBubbleAsChest() throws IOException {
        List<StorehouseBubbleDetector.Candidate> accepted = StorehouseBubbleDetector.locate(load("night-crate-ready.png"));
        assertEquals(1, accepted.size());
        assertEquals(StorehouseBubbleDetector.Kind.CHEST, accepted.getFirst().kind());
    }

    @Test
    void acceptsTheVisibleCanAsStamina() throws IOException {
        List<StorehouseBubbleDetector.Candidate> accepted = StorehouseBubbleDetector.locate(load("city-can-visible.png"));
        assertEquals(1, accepted.size());
        assertEquals(StorehouseBubbleDetector.Kind.STAMINA, accepted.getFirst().kind());
    }

    @Test
    void acceptsTheConstructionCanAsStamina() throws IOException {
        List<StorehouseBubbleDetector.Candidate> accepted = StorehouseBubbleDetector.locate(load("construction-stamina-3d.png"));
        assertEquals(1, accepted.size());
        assertEquals(StorehouseBubbleDetector.Kind.STAMINA, accepted.getFirst().kind());
    }

    @Test
    void findsNoRewardBubbleOnACooldownPill() throws IOException {
        assertTrue(StorehouseBubbleDetector.locate(load("cooldown-white-pill.png")).isEmpty());
    }

    @Test
    void colorAndTemplateBothFindTheDayCrate() throws IOException {
        byte[] png = loadBytes("day-crate-ready.png");
        BufferedImage frame = load("day-crate-ready.png");
        assertEquals(1, StorehouseIconSearchKind.COLOR.open(png).find(frame).size());
        assertFalse(StorehouseIconSearchKind.TEMPLATE.open(png).find(frame).isEmpty());
        assertFalse(StorehouseIconSearchKind.CHEST3.open(png).find(frame).isEmpty());
    }

    private BufferedImage load(String frame) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/storehouse/" + frame)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing storehouse frame: " + frame));
        }
    }

    private byte[] loadBytes(String frame) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/storehouse/" + frame)) {
            return Objects.requireNonNull(stream, "Missing storehouse frame: " + frame).readAllBytes();
        }
    }
}
