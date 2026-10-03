package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FormationOccupancyVerifierTest {

    @Test
    void readsOccupiedOrangeBadgesFromSanitizedLiveBearFrames() throws IOException {
        assertEquals(List.of(), occupied("formation-none.png"));
        assertEquals(List.of(), occupied("formation-1.png"));
        assertEquals(List.of(1), occupied("formation-2.png"));
        assertEquals(List.of(), occupied("formation-3.png"));
        assertEquals(List.of(3), occupied("formation-4.png"));
        assertEquals(List.of(4), occupied("formation-5.png"));
        assertEquals(List.of(4, 5), occupied("formation-6.png"));
    }

    private List<Integer> occupied(String name) throws IOException {
        BufferedImage crop = ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/bear/" + name), name));
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < crop.getHeight(); y++) {
            for (int x = 0; x < crop.getWidth(); x++) {
                frame.setRGB(x, y + 80, crop.getRGB(x, y));
            }
        }
        List<Integer> occupied = new ArrayList<>();
        for (int flag = 1; flag <= 8; flag++) {
            if (FormationOccupancyVerifier.isOccupied(frame, flag)) {
                occupied.add(flag);
            }
        }
        return occupied;
    }
}
