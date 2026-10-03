package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.*;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import org.junit.jupiter.api.Test;

class BearTemplateMatcherTest {
    @Test
    void copiesExactExclusiveRegionWithoutReusingMutableFrameData() {
        for (int depth : new int[]{2, 16, 4, 32}) {
            int bytes = depth == 2 || depth == 16 ? 2 : 4;
            byte[] data = new byte[4 * 4 * bytes];
            for (int i = 0; i < data.length; i++) data[i] = (byte) i;
            var frame = RawImageData.capture(data, 4, 4, depth);
            var region = BearTemplateMatcher.copyRegion(frame,
                    new AreaData(new PointData(1, 1), new PointData(3, 3)));
            assertEquals(2, region.getWidth());
            assertEquals(2, region.getHeight());
            assertEquals(data[5 * bytes], region.getData()[0]);
            assertEquals(data[9 * bytes], region.getData()[2 * bytes]);
            data[5 * bytes] = 0;
            assertNotEquals(data[5 * bytes], region.getData()[0]);
            assertNull(BearTemplateMatcher.copyRegion(frame,
                    new AreaData(new PointData(3, 3), new PointData(5, 5))));
        }
    }
}
