package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class IntelJourneyVictoryFrameTest {

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library in this JVM.
        }
    }

    @Test
    void detectsJourneyVictoryBeforeLeavingBattleResult() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/intel/journey-victory-20260821.png")) {
            byte[] frame = Objects.requireNonNull(stream, "Missing Journey victory frame").readAllBytes();
            ImageSearchResultData victory = OpenCvPatternLocator.locatePattern(frame,
                    TemplatesEnum.EXPLORATION_VICTORY,
                    new PointData(0, 0), new PointData(720, 1280), 88);

            assertTrue(victory.isFound(), "Expected the shared Exploration victory anchor");
        }
    }
}
