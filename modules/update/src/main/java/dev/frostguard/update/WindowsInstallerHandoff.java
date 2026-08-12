package dev.frostguard.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class WindowsInstallerHandoff implements InstallerHandoff {
    static final String INSTALLER_ENV = "FROSTGUARD_UPDATE_INSTALLER";
    static final String PID_ENV = "FROSTGUARD_UPDATE_PARENT_PID";
    static final String TOKEN_PATH_ENV = "FROSTGUARD_UPDATE_TOKEN_PATH";
    static final String TOKEN_VALUE_ENV = "FROSTGUARD_UPDATE_TOKEN_VALUE";
    static final String RESTART_LAUNCHER_ENV = "FROSTGUARD_UPDATE_RESTART_LAUNCHER";
    static final String INSTALL_DIR_ENV = "FROSTGUARD_UPDATE_INSTALL_DIR";
    static final String WORKSPACE_ENV = "FROSTGUARD_WORKSPACE";
    private static final String HANDOFF_SCRIPT = createHandoffScript();

    private final DetachedProcessStarter processStarter;

    public WindowsInstallerHandoff() {
        this((command, environment) -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(environment);
            builder.start();
        });
    }

    WindowsInstallerHandoff(DetachedProcessStarter processStarter) {
        this.processStarter = processStarter;
    }

    @Override
    public HandoffSession stage(Path installer, long parentPid, Path restartLauncher, Path workspaceRoot)
            throws UpdateException {
        if (!Files.isRegularFile(installer)) {
            throw new UpdateException("Installer handoff target does not exist: " + installer);
        }
        if (!installer.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".msi")) {
            throw new UpdateException("Windows update handoff requires an MSI package");
        }
        if (parentPid <= 0) {
            throw new UpdateException("Installer handoff requires a valid Frostguard process ID");
        }
        if (!Files.isRegularFile(restartLauncher)) {
            throw new UpdateException("Frostguard restart launcher does not exist: " + restartLauncher);
        }
        if (!Files.isDirectory(workspaceRoot)) {
            throw new UpdateException("Frostguard restart workspace does not exist: " + workspaceRoot);
        }
        Path normalizedLauncher = restartLauncher.toAbsolutePath().normalize();
        Path installDirectory = normalizedLauncher.getParent();
        if (installDirectory == null) {
            throw new UpdateException("Frostguard restart launcher has no installation directory");
        }
        List<String> command = List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-Command", HANDOFF_SCRIPT);
        String tokenValue = UUID.randomUUID().toString();
        Path tokenPath = installer.resolveSibling(installer.getFileName() + ".handoff-ready");
        try {
            Files.deleteIfExists(tokenPath);
        } catch (IOException exception) {
            throw new UpdateException("Could not clear an earlier installer handoff token", exception);
        }
        Map<String, String> environment = Map.of(
                INSTALLER_ENV, installer.toAbsolutePath().normalize().toString(),
                PID_ENV, Long.toString(parentPid),
                TOKEN_PATH_ENV, tokenPath.toAbsolutePath().normalize().toString(),
                TOKEN_VALUE_ENV, tokenValue,
                RESTART_LAUNCHER_ENV, normalizedLauncher.toString(),
                INSTALL_DIR_ENV, installDirectory.toString(),
                WORKSPACE_ENV, workspaceRoot.toAbsolutePath().normalize().toString());
        try {
            processStarter.start(command, environment);
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
                    Files.deleteIfExists(tokenPath.resolveSibling(tokenPath.getFileName() + ".tmp"));
                } catch (IOException ignored) {
                }
            }
        };
    }

    private static String createHandoffScript() {
        return """
                $ErrorActionPreference = 'Stop'
                $parentExited = $false
                $targetPid = [int]$env:%1$s
                $restartLauncher = $env:%2$s
                $installerPath = $env:%3$s
                $tokenPath = $env:%4$s
                $tokenValue = $env:%5$s
                $installDirectory = $env:%6$s

                function Start-Frostguard {
                    @('Env:%1$s', 'Env:%2$s', 'Env:%3$s', 'Env:%4$s', 'Env:%5$s', 'Env:%6$s') |
                        ForEach-Object { Remove-Item -LiteralPath $_ -ErrorAction SilentlyContinue }
                    if (-not (Test-Path -LiteralPath $restartLauncher)) {
                        throw 'The Frostguard restart launcher was not found.'
                    }
                    Start-Process -FilePath $restartLauncher
                }

                function Show-UpdateFailure {
                    param([string]$Details)
                    try {
                        Add-Type -AssemblyName PresentationFramework
                        [System.Windows.MessageBox]::Show(
                            "Frostguard could not complete the update.`n`n$Details",
                            'Frostguard update failed',
                            [System.Windows.MessageBoxButton]::OK,
                            [System.Windows.MessageBoxImage]::Error) | Out-Null
                    } catch {
                    }
                }

                try {
                    $deadline = [DateTime]::UtcNow.AddMinutes(5)
                    while ((Get-Process -Id $targetPid -ErrorAction SilentlyContinue) -and
                            [DateTime]::UtcNow -lt $deadline) {
                        Start-Sleep -Milliseconds 200
                    }
                    if (Get-Process -Id $targetPid -ErrorAction SilentlyContinue) {
                        throw 'Frostguard did not exit before the update timeout.'
                    }
                    $parentExited = $true
                    if (-not (Test-Path -LiteralPath $tokenPath)) {
                        throw 'The update handoff was not authorized.'
                    }
                    $token = Get-Content -Raw -LiteralPath $tokenPath
                    if ($token -ne $tokenValue) {
                        throw 'The update handoff authorization was invalid.'
                    }
                    Remove-Item -LiteralPath $tokenPath -Force

                    $installArguments = @(
                        '/i',
                        ('"{0}"' -f $installerPath),
                        '/passive',
                        '/norestart',
                        ('INSTALLDIR="{0}"' -f $installDirectory)
                    )
                    $installerProcess = Start-Process -FilePath 'msiexec.exe' `
                        -ArgumentList $installArguments -Wait -PassThru
                    if (@(0, 3010) -notcontains $installerProcess.ExitCode) {
                        throw "Windows Installer exited with code $($installerProcess.ExitCode). " +
                            'The previous installation was restored.'
                    }
                    Start-Frostguard
                    exit 0
                } catch {
                    $failureDetails = $_.Exception.Message
                    if ($parentExited) {
                        try {
                            Start-Frostguard
                        } catch {
                            $failureDetails += "`nFrostguard could not be restarted: $($_.Exception.Message)"
                        }
                    }
                    Show-UpdateFailure -Details $failureDetails
                    exit 1
                }
                """.formatted(
                PID_ENV,
                RESTART_LAUNCHER_ENV,
                INSTALLER_ENV,
                TOKEN_PATH_ENV,
                TOKEN_VALUE_ENV,
                INSTALL_DIR_ENV);
    }

    @FunctionalInterface
    interface DetachedProcessStarter {
        void start(List<String> command, Map<String, String> environment) throws IOException;
    }
}
