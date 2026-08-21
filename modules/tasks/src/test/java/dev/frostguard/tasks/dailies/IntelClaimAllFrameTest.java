package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class IntelClaimAllFrameTest {

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library in this JVM.
        }
    }

    @Test
    void detectsClaimAllInTheCompletedIntelMap() throws IOException {
        assertTrue(matches("intel-claim-all-live.png"));
    }

    @Test
    void rejectsClaimAllOnTheJourneyVictoryScreen() throws IOException {
        assertFalse(matches("journey-victory-20260821.png"));
    }

    private boolean matches(String fixture) throws IOException {
        return OpenCvPatternLocator.locatePattern(resource(fixture), TemplatesEnum.INTEL_CLAIM_ALL,
                CommonGameAreas.INTEL_CLAIM_ALL_AREA.topLeft(),
                CommonGameAreas.INTEL_CLAIM_ALL_AREA.bottomRight(), 88).isFound();
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/intel/" + name)) {
            return Objects.requireNonNull(stream, "Missing test fixture: " + name).readAllBytes();
        }
    }
}
