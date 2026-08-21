package dev.frostguard.app;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class RuntimeVersion {
    private RuntimeVersion() {
    }

    public static String current() {
        return Holder.INSTANCE;
    }

    static String resolve(WorkspacePaths workspace, String buildVersion, Function<Path, String> stableTagResolver) {
        if (workspace.channel() != RuntimeChannel.DEVELOPMENT) {
            return buildVersion;
        }
        String tag = stableTagResolver.apply(RuntimeInstanceIdentity.developmentProjectRoot(workspace.root()));
        if (tag != null && tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) {
            return tag.substring(1) + "-dev";
        }
        return null;
    }

    private static String readStableTag(Path projectRoot) {
        Process process = null;
        try {
            process = new ProcessBuilder("git", "-C", projectRoot.toString(), "describe", "--tags", "--abbrev=0",
                    "--match=v[0-9]*", "--exclude=*-*")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
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

    private static final class Holder {
        private static final String INSTANCE = resolve(WorkspacePaths.current(), BuildMetadata.current().version(),
                RuntimeVersion::readStableTag);
    }
}
