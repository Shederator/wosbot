package dev.frostguard.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Stages a detached macOS handoff that waits for Frostguard to exit, then opens
 * the downloaded {@code .pkg} so the user can finish Gatekeeper / Installer UI.
 * Apple notarization is out of scope; Gatekeeper may require Open Once.
 */
public final class MacInstallerHandoff implements InstallerHandoff {
    static final String INSTALLER_ENV = "FROSTGUARD_UPDATE_INSTALLER";
    static final String PID_ENV = "FROSTGUARD_UPDATE_PARENT_PID";
    static final String TOKEN_PATH_ENV = "FROSTGUARD_UPDATE_TOKEN_PATH";
    static final String TOKEN_VALUE_ENV = "FROSTGUARD_UPDATE_TOKEN_VALUE";
    static final String RESTART_LAUNCHER_ENV = "FROSTGUARD_UPDATE_RESTART_LAUNCHER";
    static final String WORKSPACE_ENV = "FROSTGUARD_WORKSPACE";

    private final DetachedProcessStarter processStarter;

    public MacInstallerHandoff() {
        this((command, environment) -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(environment);
            builder.start();
        });
    }

    MacInstallerHandoff(DetachedProcessStarter processStarter) {
        this.processStarter = processStarter;
    }

    @Override
    public HandoffSession stage(Path installer, long parentPid, Path restartLauncher, Path workspaceRoot)
            throws UpdateException {
        if (!Files.isRegularFile(installer)) {
            throw new UpdateException("Installer handoff target does not exist: " + installer);
        }
        if (!installer.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pkg")) {
            throw new UpdateException("macOS update handoff requires a pkg installer");
        }
        if (parentPid <= 0) {
            throw new UpdateException("Installer handoff requires a valid Frostguard process ID");
        }
        if (!Files.isRegularFile(restartLauncher) && !Files.isDirectory(restartLauncher)) {
            // jpackage restart target is the .app bundle path or Contents/MacOS launcher
            throw new UpdateException("Frostguard restart launcher does not exist: " + restartLauncher);
        }
        if (!Files.isDirectory(workspaceRoot)) {
            throw new UpdateException("Frostguard restart workspace does not exist: " + workspaceRoot);
        }

        String tokenValue = UUID.randomUUID().toString();
        Path tokenPath = installer.resolveSibling(installer.getFileName() + ".handoff-ready");
        try {
            Files.deleteIfExists(tokenPath);
        } catch (IOException exception) {
            throw new UpdateException("Could not clear an earlier installer handoff token", exception);
        }

        Path script;
        try {
            script = Files.createTempFile("frostguard-macos-handoff-", ".sh");
            Files.writeString(script, createHandoffScript(), StandardCharsets.UTF_8);
            script.toFile().setExecutable(true);
        } catch (IOException exception) {
            throw new UpdateException("Could not stage the macOS installer handoff script", exception);
        }

        Map<String, String> environment = Map.of(
                INSTALLER_ENV, installer.toAbsolutePath().normalize().toString(),
                PID_ENV, Long.toString(parentPid),
                TOKEN_PATH_ENV, tokenPath.toAbsolutePath().normalize().toString(),
                TOKEN_VALUE_ENV, tokenValue,
                RESTART_LAUNCHER_ENV, restartLauncher.toAbsolutePath().normalize().toString(),
                WORKSPACE_ENV, workspaceRoot.toAbsolutePath().normalize().toString());
        try {
            processStarter.start(List.of("/bin/bash", script.toAbsolutePath().toString()), environment);
        } catch (IOException exception) {
            throw new UpdateException(
                    "Could not start the external installer handoff: " + exception.getMessage(), exception);
        }
        return new HandoffSession() {
            @Override
            public void authorize() throws UpdateException {
                Path temporary = tokenPath.resolveSibling(tokenPath.getFileName() + ".tmp");
                try {
                    Files.writeString(temporary, tokenValue);
                    try {
                        Files.move(temporary, tokenPath, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                        Files.move(temporary, tokenPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException exception) {
                    throw new UpdateException("Could not authorize the installer handoff", exception);
                }
            }

            @Override
            public void cancel() {
                try {
                    Files.deleteIfExists(tokenPath);
                } catch (IOException ignored) {
                    // Best-effort cleanup when Frostguard aborts the handoff.
                }
            }
        };
    }

    static String createHandoffScript() {
        return """
                #!/bin/bash
                set -euo pipefail
                installer="${%s}"
                parent_pid="${%s}"
                token_path="${%s}"
                token_value="${%s}"
                restart_launcher="${%s}"
                workspace="${%s}"

                for _ in $(seq 1 600); do
                  if [[ -f "$token_path" ]] && [[ "$(cat "$token_path")" == "$token_value" ]]; then
                    break
                  fi
                  sleep 0.5
                done
                if [[ ! -f "$token_path" ]] || [[ "$(cat "$token_path")" != "$token_value" ]]; then
                  echo "Frostguard macOS handoff was not authorized" >&2
                  exit 1
                fi
                rm -f "$token_path"

                for _ in $(seq 1 600); do
                  if ! kill -0 "$parent_pid" 2>/dev/null; then
                    break
                  fi
                  sleep 0.5
                done
                if kill -0 "$parent_pid" 2>/dev/null; then
                  echo "Frostguard process $parent_pid is still running" >&2
                  exit 1
                fi

                open "$installer"
                # Best-effort relaunch after the user finishes the pkg UI.
                sleep 20
                if [[ -e "$restart_launcher" ]]; then
                  open "$restart_launcher" --args --workspace "$(basename "$workspace")" || true
                fi
                """.formatted(INSTALLER_ENV, PID_ENV, TOKEN_PATH_ENV, TOKEN_VALUE_ENV,
                RESTART_LAUNCHER_ENV, WORKSPACE_ENV);
    }

    @FunctionalInterface
    interface DetachedProcessStarter {
        void start(List<String> command, Map<String, String> environment) throws IOException;
    }
}
