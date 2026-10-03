package dev.frostguard.app;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionBuildIdentityTest {
    @TempDir
    Path tempDir;

    @Test
    void labelsDevelopmentCheckoutSeparatelyFromTheBuild() {
        WorkspacePaths workspace = new WorkspacePaths(tempDir.resolve("repo").resolve(".frostguard-dev"),
                RuntimeChannel.DEVELOPMENT);
        BuildMetadata build = new BuildMetadata("3.0.2", false, "", "unknown", "unknown");

        List<String> lines = SessionBuildIdentity.describe(workspace, build, root -> {
            assertEquals(tempDir.resolve("repo"), root);
            return new SessionBuildIdentity.Checkout("0123456789abcdef0123456789abcdef01234567", "present");
        });

        assertTrue(lines.contains("Bot Version: 3.0.2 (Maven revision)"));
        assertTrue(lines.contains("Channel: Development"));
        assertTrue(lines.contains("Build Commit: unknown"));
        assertTrue(lines.contains("Checkout Commit: 0123456789abcdef0123456789abcdef01234567"));
        assertTrue(lines.contains("Tracked Changes: present"));
    }

    @Test
    void releaseUsesEmbeddedIdentityWithoutInspectingGit() {
        WorkspacePaths workspace = new WorkspacePaths(tempDir.resolve("installed"), RuntimeChannel.NIGHTLY);
        BuildMetadata build = new BuildMetadata("3.0.2-nightly.20261002.1", false, "",
                "0123456789abcdef0123456789abcdef01234567", "2026-10-02T12:34:56Z");

        List<String> lines = SessionBuildIdentity.describe(workspace, build, root -> {
            throw new AssertionError("Release identity must not inspect Git");
        });

        assertTrue(lines.contains("Bot Version: 3.0.2-nightly.20261002.1"));
        assertTrue(lines.contains("Channel: Nightly"));
        assertTrue(lines.contains("Build Commit: 0123456789abcdef0123456789abcdef01234567"));
        assertTrue(lines.contains("Build Time: 2026-10-02T12:34:56Z"));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("Checkout")));
    }
}
