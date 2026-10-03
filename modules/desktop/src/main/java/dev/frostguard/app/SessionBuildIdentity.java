package dev.frostguard.app;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Build provenance for the account log header. Git describes a checkout, not compiled classes. */
public final class SessionBuildIdentity {
    private static final int GIT_TIMEOUT_SECONDS = 3;

    private SessionBuildIdentity() {
    }

    public static List<String> forWorkspace(WorkspacePaths workspace) {
        return describe(workspace, BuildMetadata.current(), SessionBuildIdentity::readCheckout);
    }

    static List<String> describe(WorkspacePaths workspace, BuildMetadata build,
            Function<Path, Checkout> checkoutReader) {
        List<String> lines = new ArrayList<>();
        boolean development = workspace.channel() == RuntimeChannel.DEVELOPMENT;
        lines.add("Bot Version: " + build.version() + (development ? " (Maven revision)" : ""));
        lines.add("Channel: " + workspace.channel().displayName());
        lines.add("Build Commit: " + build.commit());
        lines.add("Build Time: " + build.buildTime());

        if (development) {
            Path projectRoot = RuntimeInstanceIdentity.developmentProjectRoot(workspace.root());
            Checkout checkout = checkoutReader.apply(projectRoot);
            lines.add("Checkout Commit: " + (checkout == null ? "unknown" : checkout.commit()));
            lines.add("Tracked Changes: " + (checkout == null ? "unknown" : checkout.trackedChanges()));
        }
        return List.copyOf(lines);
    }

    private static Checkout readCheckout(Path projectRoot) {
        if (!Files.exists(projectRoot.resolve(".git"))) {
            return null;
        }
        GitResult head = git(projectRoot, "rev-parse", "--verify", "HEAD");
        if (head == null || head.exitCode() != 0 || !head.output().matches("(?i)[0-9a-f]{40,64}")) {
            return null;
        }
        GitResult diff = git(projectRoot, "diff", "--quiet", "HEAD", "--");
        String trackedChanges = diff == null ? "unknown"
                : switch (diff.exitCode()) {
                    case 0 -> "none";
                    case 1 -> "present";
                    default -> "unknown";
                };
        return new Checkout(head.output().toLowerCase(java.util.Locale.ROOT), trackedChanges);
    }

    private static GitResult git(Path projectRoot, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-C", projectRoot.toString()));
        command.addAll(List.of(arguments));
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return new GitResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    record Checkout(String commit, String trackedChanges) {
    }

    private record GitResult(int exitCode, String output) {
    }
}
