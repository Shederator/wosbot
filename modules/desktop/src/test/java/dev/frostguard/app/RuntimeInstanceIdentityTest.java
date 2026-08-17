package dev.frostguard.app;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeInstanceIdentityTest {
    @TempDir
    Path tempDir;

    @Test
    void usesWorktreeDirectoryForLinkedDevelopmentWorktree() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("wosbot-arena-refresh-budget"));
        Files.writeString(project.resolve(".git"), "gitdir: ../wosbot/.git/worktrees/arena-refresh-budget");

        assertEquals("wosbot-arena-refresh-budget", resolveDevelopment(project));
    }

    @Test
    void usesBranchForNormalDevelopmentClone() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("wosbot"));
        Path git = Files.createDirectories(project.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/fix/arena-refresh-budget\n");

        assertEquals("fix/arena-refresh-budget", resolveDevelopment(project));
    }

    @Test
    void usesShortCommitForDetachedDevelopmentClone() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("wosbot"));
        Path git = Files.createDirectories(project.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "7da0b440c25cc552ae2ea15a9f615331f9238f8b\n");

        assertEquals("7da0b440", resolveDevelopment(project));
    }

    @Test
    void fallsBackToProjectDirectoryWithoutReadableGitMetadata() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("wosbot-local"));

        assertEquals("wosbot-local", resolveDevelopment(project));
    }

    @Test
    void usesNamedReleaseWorkspaceAndAllowsExplicitOverride() {
        WorkspacePaths nightly = new WorkspacePaths(tempDir.resolve("nightly").resolve("bot-2"),
                RuntimeChannel.NIGHTLY);

        assertEquals("bot-2", RuntimeInstanceIdentity.resolve(nightly, null));
        assertEquals("live-test", RuntimeInstanceIdentity.resolve(nightly, " live-test "));
    }

    private String resolveDevelopment(Path project) {
        WorkspacePaths workspace = new WorkspacePaths(project.resolve(".frostguard-dev"),
                RuntimeChannel.DEVELOPMENT);
        return RuntimeInstanceIdentity.resolve(workspace, null);
    }
}
