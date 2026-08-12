package dev.frostguard.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsInstallerHandoffTest {
    @TempDir
    Path temp;

    @Test
    void startsHiddenWaiterWithInstallerAndParentIdentity() throws Exception {
        Path installer = Files.writeString(temp.resolve("Frostguard-3.0.1.msi"), "test");
        Path installDirectory = Files.createDirectory(temp.resolve("installed Frostguard"));
        Path launcher = Files.writeString(installDirectory.resolve("Frostguard.exe"), "test");
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        AtomicReference<java.util.List<String>> command = new AtomicReference<>();
        AtomicReference<java.util.Map<String, String>> environment = new AtomicReference<>();
        WindowsInstallerHandoff handoff = new WindowsInstallerHandoff((actualCommand, actualEnvironment) -> {
            command.set(actualCommand);
            environment.set(actualEnvironment);
        });

        InstallerHandoff.HandoffSession session = handoff.stage(
                installer, 4242L, launcher, workspace);

        assertTrue(command.get().contains("Hidden"));
        String script = command.get().getLast();
        assertTrue(script.contains("Get-Process -Id $targetPid"));
        assertTrue(script.contains("Start-Process -FilePath 'msiexec.exe'"));
        assertTrue(script.contains("'/i'"));
        assertTrue(script.contains("('\"{0}\"' -f $installerPath)"));
        assertTrue(script.contains("'/passive'"));
        assertTrue(script.contains("'/norestart'"));
        assertTrue(script.contains("INSTALLDIR=\"{0}\""));
        assertTrue(script.contains("-Wait -PassThru"));
        assertTrue(script.contains("@(0, 3010)"));
        assertTrue(script.contains("The Frostguard restart launcher was not found"));
        assertTrue(script.contains("Start-Frostguard"));
        assertEquals("4242", environment.get().get(WindowsInstallerHandoff.PID_ENV));
        assertEquals(installer.toAbsolutePath().normalize().toString(),
                environment.get().get(WindowsInstallerHandoff.INSTALLER_ENV));
        assertEquals(launcher.toAbsolutePath().normalize().toString(),
                environment.get().get(WindowsInstallerHandoff.RESTART_LAUNCHER_ENV));
        assertEquals(installDirectory.toAbsolutePath().normalize().toString(),
                environment.get().get(WindowsInstallerHandoff.INSTALL_DIR_ENV));
        assertEquals(workspace.toAbsolutePath().normalize().toString(),
                environment.get().get(WindowsInstallerHandoff.WORKSPACE_ENV));
        Path token = Path.of(environment.get().get(WindowsInstallerHandoff.TOKEN_PATH_ENV));
        assertTrue(command.get().getLast().contains("TOKEN_PATH"));
        session.authorize();
        assertEquals(environment.get().get(WindowsInstallerHandoff.TOKEN_VALUE_ENV), Files.readString(token));
        session.cancel();
        assertTrue(Files.notExists(token));
    }

    @Test
    void rejectsInvalidInputsBeforeStartingWaiter() {
        WindowsInstallerHandoff handoff = new WindowsInstallerHandoff((command, environment) -> {
            throw new AssertionError("Waiter should not start");
        });
        Path installer = temp.resolve("missing.msi");
        Path launcher = temp.resolve("missing-launcher.exe");
        assertThrows(UpdateException.class,
                () -> handoff.stage(installer, 10L, launcher, temp));
    }

    @Test
    void reportsWaiterLaunchFailure() throws Exception {
        Path installer = Files.writeString(temp.resolve("Frostguard-3.0.1.msi"), "test");
        Path installDirectory = Files.createDirectory(temp.resolve("installed"));
        Path launcher = Files.writeString(installDirectory.resolve("Frostguard.exe"), "test");
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        WindowsInstallerHandoff handoff = new WindowsInstallerHandoff((command, environment) -> {
            throw new IOException("blocked");
        });
        assertThrows(UpdateException.class,
                () -> handoff.stage(installer, 10L, launcher, workspace));
    }

    @Test
    void rejectsExeWrapperBeforeStartingWaiter() throws Exception {
        Path installer = Files.writeString(temp.resolve("Frostguard-3.0.1.exe"), "test");
        Path installDirectory = Files.createDirectory(temp.resolve("installed-exe"));
        Path launcher = Files.writeString(installDirectory.resolve("Frostguard.exe"), "test");
        Path workspace = Files.createDirectory(temp.resolve("workspace-exe"));
        WindowsInstallerHandoff handoff = new WindowsInstallerHandoff((command, environment) -> {
            throw new AssertionError("Waiter should not start for an EXE wrapper");
        });

        assertThrows(UpdateException.class,
                () -> handoff.stage(installer, 10L, launcher, workspace));
    }
}
