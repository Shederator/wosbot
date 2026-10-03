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
    void recordedScrollRequiresCurrentGreenPlusAndExactIdentity() throws Exception {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
        OpenCvPatternLocator.loadNativeLibrary();
        var helper = new TemplateSearchHelper(EmulatorController.getInstance(), "offline",
                new AccountDescriptor(990999L, "offline", "0", true, 100L, 30L),
                () -> { throw new AssertionError("No device capture in replay"); });
        var directory = Path.of(System.getenv("FROSTGUARD_BEAR_RECORDING_FRAMES"));
        var beforeScroll = rows(helper, directory, 171);
        var moving = rows(helper, directory, 182);
        var settled = rows(helper, directory, 194);
        var plusGone = rows(helper, directory, 235);
        var traversal = new BearRallyListTraversal();
        assertTrue(traversal.currentTopmostJoinable(beforeScroll).isEmpty(),
                "the recorded grey controls are not join permission");
        var candidate = traversal.currentTopmostJoinable(moving).orElseThrow();
        var settledCandidate = traversal.currentTopmostJoinable(settled).orElseThrow();
        assertTrue(candidate.y() != settledCandidate.y(), "this pair must exercise actual scrolling");
        // Decorations are not OCR-stable in these frames. Green alone cannot override that.
        assertEquals(candidate.sameIdentity(settledCandidate),
                traversal.authorizeCandidate(candidate, settled).isPresent());
        assertFalse(plusGone.isEmpty());
        assertTrue(plusGone.stream().noneMatch(BearRallyListTraversal.Row::joinable));
        assertTrue(traversal.authorizeCandidate(candidate, plusGone).isEmpty(),
                "identity alone must not authorize a now-grey plus");
    }

    @Test
    void consecutiveRecordedFramesPreserveIdentityButRevokeAGreyJoinControl() throws Exception {
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
        assertTrue(before.getFirst().sameIdentity(after.getFirst()));
        assertTrue(before.getFirst().joinable());
        assertFalse(after.getFirst().joinable());
        assertTrue(traversal.authorizeCandidate(before.getFirst(), after).isEmpty(),
                "same captain is not permission to tap a now-grey plus");
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
