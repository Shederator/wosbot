package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.*;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Private exact-frame OCR replay: do not commit source-account names to make this run on CI. */
@EnabledIfEnvironmentVariable(named = "FROSTGUARD_BEAR_RECORDING_FRAMES", matches = ".+")
class BearPrivateRallyReplayTest {
    @Test
    void consecutiveRecordedFramesPreserveUniqueCaptainAuthorization() throws Exception {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
        OpenCvPatternLocator.loadNativeLibrary();
        var helper = new TemplateSearchHelper(EmulatorController.getInstance(), "offline",
                new AccountDescriptor(990999L, "offline", "0", true, 100L, 30L),
                () -> { throw new AssertionError("No device capture in replay"); });
        var directory = Path.of(System.getenv("FROSTGUARD_BEAR_RECORDING_FRAMES"));
        List<BearRallyListTraversal.Row> before = rows(helper, directory, 115);
        long started = System.nanoTime();
        List<BearRallyListTraversal.Row> after = rows(helper, directory, 128);
        System.out.println("BEAR_PRIVATE_REPLAY classificationAndFreshRowScanMs=" + (System.nanoTime() - started) / 1_000_000);
        assertEquals(2, before.size());
        assertEquals(2, after.size());
        var traversal = new BearRallyListTraversal();
        assertTrue(traversal.authorizeCandidate(before.getFirst(), after).isPresent(),
                "same recorded captain must authorize despite changed H.264 pixels");
        assertFalse(before.getFirst().sameIdentity(after.get(1)));
        // The decorated second name was not stable under OCR in the recorded pair. It must not
        // borrow the first captain's identity or trigger a fuzzy/coordinate fallback.
        traversal.completed(before.getFirst());
        boolean secondStable = before.get(1).sameIdentity(after.get(1));
        assertEquals(secondStable, traversal.authorizeCandidate(before.get(1), after).isPresent());
        System.out.println("BEAR_PRIVATE_REPLAY secondCaptainOcrStable=" + secondStable);
    }

    private static List<BearRallyListTraversal.Row> rows(TemplateSearchHelper helper, Path directory, int frame)
            throws Exception {
        var image = ImageIO.read(directory.resolve("decoded-" + frame + ".png").toFile());
        var raw = BearLiveFrameReplayTest.raw(image);
        assertEquals(BearNavigationPolicy.Screen.WAR_LIST, new BearFrameClassifier(
                new BearTemplateMatcher(EmulatorController.getInstance(), "offline"), 1).classify(raw).screen());
        return new BearRallyScanner(helper, raw).scanRows().stream()
                .map(BearRallyListTraversal.Row::new).toList();
    }
}
