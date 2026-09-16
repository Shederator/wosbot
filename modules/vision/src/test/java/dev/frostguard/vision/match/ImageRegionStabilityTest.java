package dev.frostguard.vision.match;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImageRegionStabilityTest {
    @Test
    void rejectsChangedControlsButIgnoresChangesOutsideTheEvidenceRegion() {
        byte[] original = new byte[20 * 20 * 4], changed = original.clone();
        var before = RawImageData.capture(original, 20, 20, 32);
        var region = AreaData.of(5, 5, 15, 15);
        changed[0] = (byte) 255;
        assertTrue(ImageRegionStability.unchanged(before, RawImageData.capture(changed, 20, 20, 32), region));
        changed[(6 * 20 + 6) * 4] = (byte) 255;
        changed[(6 * 20 + 7) * 4] = (byte) 255;
        assertFalse(ImageRegionStability.unchanged(before, RawImageData.capture(changed, 20, 20, 32), region));
    }

    @Test
    void malformedCapturesAndRegionsFailClosed() {
        var frame = RawImageData.capture(new byte[400], 10, 10, 32);
        assertFalse(ImageRegionStability.unchanged(frame, null, AreaData.of(0, 0, 10, 10)));
        assertFalse(ImageRegionStability.unchanged(frame, frame, AreaData.of(-1, 0, 10, 10)));
        assertFalse(ImageRegionStability.unchanged(frame, RawImageData.capture(new byte[1], 10, 10, 32),
                AreaData.of(0, 0, 10, 10)));
    }
}
