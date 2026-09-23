import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLabelRedactorTest {

    private static final Color NAMEPLATE = new Color(75, 83, 105);
    private static final Color CYAN = new Color(91, 186, 231);

    @Test
    void matchesReviewedSyntheticCityLabelSnapshot() throws IOException {
        BufferedImage frame = loadFixture("synthetic-city-labels.png");
        BufferedImage expected = loadFixture("synthetic-city-labels-redacted.png");

        CityLabelRedactor.RedactionResult result = CityLabelRedactor.redact(frame);

        assertEquals(2, result.areas().size());
        assertImagesEqual(expected, result.image());
        assertEquals(NAMEPLATE.getRGB(), result.image().getRGB(147, 311),
                "The grey nameplate border must remain intact");
        assertEquals(CYAN.getRGB(), result.image().getRGB(205, 325),
                "The fake identity must become an opaque cyan block");
        assertEquals(frame.getRGB(220, 775), result.image().getRGB(220, 775),
                "The green My City signal must remain unchanged");
    }

    @Test
    void matchesReviewedSyntheticProtectedButtonSnapshot() throws IOException {
        BufferedImage frame = loadFixture("synthetic-button-overlap.png");
        BufferedImage expected = loadFixture("synthetic-button-overlap-redacted.png");

        CityLabelRedactor.RedactionResult result = CityLabelRedactor.redact(frame);

        assertEquals(1, result.areas().size());
        assertImagesEqual(expected, result.image());
        assertEquals(CYAN.getRGB(), result.image().getRGB(620, 863),
                "The detected print area must be redacted up to the button column");
        assertEquals(CYAN.getRGB(), result.image().getRGB(635, 863),
                "The protected right-side button column must not be painted");
    }

    @Test
    void reviewedRealFramesAreIdempotent() throws IOException {
        assertIdempotent("wilderness-with-pets-redacted.png");
        assertIdempotent("wilderness-without-pets-redacted.png");
    }

    private static void assertIdempotent(String fixtureName) throws IOException {
        BufferedImage reviewed = loadFixture(fixtureName);
        CityLabelRedactor.RedactionResult repeated = CityLabelRedactor.redact(reviewed);

        assertTrue(repeated.areas().isEmpty(), "Already-redacted fills must not be detected as text");
        assertImagesEqual(reviewed, repeated.image());
    }

    private static void assertImagesEqual(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y),
                        "Pixel changed at (" + x + "," + y + ")");
            }
        }
    }

    private static BufferedImage loadFixture(String fixtureName) throws IOException {
        try (InputStream stream = CityLabelRedactorTest.class.getResourceAsStream("/" + fixtureName)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing fixture: " + fixtureName));
        }
    }
}
