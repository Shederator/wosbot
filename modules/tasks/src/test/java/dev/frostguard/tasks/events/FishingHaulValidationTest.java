package dev.frostguard.tasks.events;

import java.util.Arrays;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.match.ImageRegionStability;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import static org.junit.jupiter.api.Assertions.*;

class FishingHaulValidationTest {
    @BeforeAll
    static void loadNative() throws Exception { OpenCvPatternLocator.loadNativeLibrary(); }

    @Test
    void acceptsTheHaulDespiteTheObservedBackgroundLightAnimation() throws Exception {
        var before = fixture("haul-light-a");
        var after = fixture("haul-light-b");
        assertFalse(ImageRegionStability.unchanged(before, after, CommonGameAreas.FISHING_HAUL_HEADING));
        assertTrue(ImageRegionStability.unchanged(before, after, CommonGameAreas.FISHING_HAUL_EXIT_BUTTON));
        assertTrue(FishingMinigameRoutine.haulControlsStillVisible(before, after));
    }

    @Test
    void rejectsALostHaulTitleEvenWhenTheExitButtonIsUnchanged() throws Exception {
        var before = fixture("haul-light-a");
        var after = fixture("haul-light-a");
        erase(after, CommonGameAreas.FISHING_HAUL_HEADING);
        assertFalse(FishingMinigameRoutine.haulControlsStillVisible(before, after));
    }

    @Test
    void rejectsChangedExitControlsAndUnrelatedEventScreens() throws Exception {
        var before = fixture("haul-light-a");
        var after = fixture("haul-light-a");
        erase(after, CommonGameAreas.FISHING_HAUL_EXIT_BUTTON);
        assertFalse(FishingMinigameRoutine.haulControlsStillVisible(before, after));
        assertFalse(FishingMinigameRoutine.haulControlsStillVisible(before, fixture("event")));
        assertFalse(FishingMinigameRoutine.haulControlsStillVisible(before, null));
    }

    private static void erase(RawImageData frame, dev.frostguard.api.domain.AreaData area) {
        for (int y = area.topLeft().getY(); y < area.bottomRight().getY(); y++) {
            Arrays.fill(frame.getData(), (y * frame.getWidth() + area.topLeft().getX()) * 4,
                    (y * frame.getWidth() + area.bottomRight().getX()) * 4, (byte) 0);
        }
    }

    private static RawImageData fixture(String name) throws Exception {
        var image = ImageIO.read(Objects.requireNonNull(FishingHaulValidationTest.class.getResource("/fishing/" + name + ".png")));
        byte[] pixels = new byte[image.getWidth() * image.getHeight() * 4];
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y), offset = (y * image.getWidth() + x) * 4;
            pixels[offset] = (byte) (rgb >> 16); pixels[offset + 1] = (byte) (rgb >> 8);
            pixels[offset + 2] = (byte) rgb; pixels[offset + 3] = (byte) 255;
        }
        return RawImageData.capture(pixels, image.getWidth(), image.getHeight(), 32);
    }
}
