package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.telemetry.TelemetryHistoryStore;

/**
 * Where telemetry actually lands for an INSTALLED release, as opposed to a source run.
 *
 * <p>Runtime evidence belongs to the selected workspace, never to generated {@code target/} output.
 * A source or IDE launch uses {@code <worktree>/.frostguard-dev}; installed Stable and Nightly use
 * {@code ~/.frostguard/workspaces/<channel>/<name>}. The original version of this task wrote under
 * {@code user.dir}, which for an installed release is the launcher's directory — so the history
 * silently landed somewhere the dashboard never reads.
 *
 * <p>These tests assert the installed layout without writing into the real home directory: the
 * channel roots are checked as paths, and the file layout beneath a root is exercised against a
 * temporary directory standing in for an installed workspace.
 *
 * <p>Evidence level: automated tests.
 */
class TelemetryInstalledWorkspaceTest {

    @TempDir
    Path installedRoot;

    @Test
    void stableAndNightlyResolveToSeparateCanonicalInstalledRoots() {
        Path stable = WorkspacePaths.userWorkspace(RuntimeChannel.STABLE, "default");
        Path nightly = WorkspacePaths.userWorkspace(RuntimeChannel.NIGHTLY, "default");

        assertNotEquals(stable, nightly, "a Nightly install must not write into the Stable workspace");

        Path home = Path.of(System.getProperty("user.home"), ".frostguard", "workspaces")
                .toAbsolutePath().normalize();
        assertTrue(stable.startsWith(home), "Stable must live under ~/.frostguard/workspaces");
        assertTrue(nightly.startsWith(home), "Nightly must live under ~/.frostguard/workspaces");

        assertEquals(RuntimeChannel.STABLE.directoryName(), stable.getParent().getFileName().toString());
        assertEquals(RuntimeChannel.NIGHTLY.directoryName(), nightly.getParent().getFileName().toString());
        assertEquals("default", stable.getFileName().toString());
    }

    @Test
    void aSourceRunHasNoInstalledWorkspaceOfItsOwn() {
        // DEVELOPMENT is not a public release, so asking for an installed root for it is a bug,
        // not a fallback -- the dev run belongs in <worktree>/.frostguard-dev instead.
        assertThrows(IllegalArgumentException.class,
                () -> WorkspacePaths.userWorkspace(RuntimeChannel.DEVELOPMENT, "default"));
    }

    @Test
    void aWorkspaceNameCannotEscapeItsReleaseChannel() {
        for (String bad : new String[] {"..", ".", "", "  ", "a/b", "a\\b"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> WorkspacePaths.userWorkspace(RuntimeChannel.STABLE, bad),
                    "workspace name '" + bad + "' must be rejected, not resolved");
        }
    }

    @Test
    void telemetryIsReadBackFromTheInstalledWorkspaceLayout() throws IOException {
        new TelemetryHistoryStore(installedRoot, 1L)
                .append(sample("2026-08-20T08:30:00Z", 12_400_000L));

        TelemetryReport report = TelemetryReport.load(installedRoot, 1L);

        assertEquals(1, report.size());
        assertEquals(12_400_000L, report.latest().get("power"));
    }

    @Test
    void aStableAndANightlyInstallDoNotShareOneProfilesHistory() throws IOException {
        Path stableRoot = installedRoot.resolve(RuntimeChannel.STABLE.directoryName()).resolve("default");
        Path nightlyRoot = installedRoot.resolve(RuntimeChannel.NIGHTLY.directoryName()).resolve("default");

        new TelemetryHistoryStore(stableRoot, 1L)
                .append(sample("2026-08-20T08:30:00Z", 100L));
        new TelemetryHistoryStore(nightlyRoot, 1L)
                .append(sample("2026-08-20T08:30:00Z", 999L));

        // Same profile ID, two installed channels: each must see only its own history.
        assertEquals(100L, TelemetryReport.load(stableRoot, 1L).latest().get("power"));
        assertEquals(999L, TelemetryReport.load(nightlyRoot, 1L).latest().get("power"));
    }

    @Test
    void anInstalledWorkspaceWithNoHistoryYetLoadsEmptyRatherThanThrowing() {
        TelemetryReport report = TelemetryReport.load(installedRoot, 42L);

        assertEquals(0, report.size(), "a fresh install has no history and must not fail the dashboard");
    }

    private static Map<String, Object> sample(String capturedAt, long power) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("capturedAt", capturedAt);
        sample.put("profile", "Default");
        sample.put("power", power);
        return sample;
    }
}
