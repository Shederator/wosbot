package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.*;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Exact private frames from the failed October 3 afternoon session; no device backend. */
@EnabledIfEnvironmentVariable(named = "FROSTGUARD_BEAR_GLD_FRAMES", matches = ".+")
class BearFailedSessionReplayTest {
    @Test
    void narrowedSearchPreservesRecordedRowsAndArmingDoesNotRejectTheirIdentity() throws Exception {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
        OpenCvPatternLocator.loadNativeLibrary();
        var controller = EmulatorController.getInstance();
        var helper = new TemplateSearchHelper(controller, "offline",
                new AccountDescriptor(990999L, "offline", "0", true, 100L, 30L),
                () -> { throw new AssertionError("No device input or capture in replay"); });
        var directory = Path.of(System.getenv("FROSTGUARD_BEAR_GLD_FRAMES"));
        BearRallyListTraversal.Row previousCandidate = null;
        for (int id : new int[]{631, 637, 656, 660}) {
            RawImageData raw = BearLiveFrameReplayTest.raw(ImageIO.read(
                    directory.resolve("decoded-" + id + ".png").toFile()));
            var frame = helper.frame(raw);
            var full = new BearRallyScanner(
                    () -> frame.locateAllPatterns(TemplatesEnum.BEAR_JOIN_PLUS_ICON,
                            TemplateSearchHelper.SearchConfig.builder().withThreshold(80).withMaxResults(8).build()),
                    () -> frame.locateAllPatterns(TemplatesEnum.BEAR_RALLY_TARGET,
                            TemplateSearchHelper.SearchConfig.builder().withThreshold(90).withMaxResults(8).build()),
                    (tl, br) -> {
                        try { return frame.extractText(CommonOCRSettings.BEAR_RALLY_LEADER_SETTINGS, tl, br); }
                        catch (Exception e) { throw new AssertionError(e); }
                    });
            var oldRows = full.scanRows();
            long started = System.nanoTime();
            assertEquals(BearNavigationPolicy.Screen.WAR_LIST,
                    new BearFrameClassifier(new BearTemplateMatcher(controller, "offline"), 1).classify(raw).screen());
            var rows = new BearRallyScanner(helper, raw).scanRows();
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertFalse(rows.isEmpty());
            assertEquals(oldRows.stream().map(BearRallyScanner.RallyRow::rowY).toList(),
                    rows.stream().map(BearRallyScanner.RallyRow::rowY).toList());
            assertEquals(oldRows.stream().map(BearRallyScanner.RallyRow::identityFingerprint).toList(),
                    rows.stream().map(BearRallyScanner.RallyRow::identityFingerprint).toList());
            var traversal = new BearRallyListTraversal();
            var candidates = rows.stream().map(BearRallyListTraversal.Row::new).toList();
            var candidate = traversal.currentTopmostJoinable(candidates).orElseThrow();
            traversal.checkpointWritten("JOIN_ARMED", candidate);
            assertTrue(traversal.authorizeCandidate(candidate, candidates).isPresent());
            if (id == 637) {
                assertTrue(traversal.authorizeCandidate(previousCandidate, candidates).isPresent(),
                        "the recorded candidate survives a genuinely newer authorizing frame");
            }
            previousCandidate = candidate;
            System.out.println("BEAR_FAILED_SESSION frame=" + id + " rows=" + rows.size()
                    + " classificationAndScanMs=" + millis);
        }
    }
}
