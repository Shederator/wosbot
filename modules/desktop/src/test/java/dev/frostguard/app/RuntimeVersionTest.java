package dev.frostguard.app;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeVersionTest {
    @TempDir
    Path tempDir;

    @Test
    void marksLatestStableTagAsDevelopmentBaseline() {
        WorkspacePaths workspace = new WorkspacePaths(tempDir.resolve("repo").resolve(".frostguard-dev"),
                RuntimeChannel.DEVELOPMENT);

        assertEquals("3.0.0-dev", RuntimeVersion.resolve(workspace, "2.1.0", root -> "v3.0.0"));
    }

    @Test
    void omitsMisleadingVersionWhenDevelopmentTagIsUnavailable() {
        WorkspacePaths workspace = new WorkspacePaths(tempDir.resolve("repo").resolve(".frostguard-dev"),
                RuntimeChannel.DEVELOPMENT);

        assertNull(RuntimeVersion.resolve(workspace, "2.1.0", root -> null));
        assertNull(RuntimeVersion.resolve(workspace, "2.1.0", root -> "v3.0.0-nightly.1"));
    }

    @Test
    void keepsExactEmbeddedVersionForReleaseChannels() {
        WorkspacePaths workspace = new WorkspacePaths(tempDir.resolve("nightly").resolve("bot-2"),
                RuntimeChannel.NIGHTLY);

        assertEquals("3.0.0-nightly.20260817.5",
                RuntimeVersion.resolve(workspace, "3.0.0-nightly.20260817.5", root -> {
                    throw new AssertionError("Release versions must not inspect Git");
                }));
    }
}
