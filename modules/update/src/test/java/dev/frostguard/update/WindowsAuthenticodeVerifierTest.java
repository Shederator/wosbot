package dev.frostguard.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowsAuthenticodeVerifierTest {
    @TempDir
    Path temp;

    @Test
    void acceptsValidExpectedPublisher() throws Exception {
        Path installer = Files.writeString(temp.resolve("installer.exe"), "test");
        WindowsAuthenticodeVerifier verifier = new WindowsAuthenticodeVerifier(
                (command, environment, timeout) -> new CommandRunner.CommandResult(
                        0, "Valid\tCN=Frostguard Project, O=Frostguard"));

        assertDoesNotThrow(() -> verifier.verify(installer,
                new SignatureRequirement("authenticode", "CN=Frostguard Project, O=Frostguard")));
    }

    @Test
    void acceptsProjectAuthenticatedInstallerWhenAuthenticodeIsNotRequired() throws Exception {
        Path installer = Files.writeString(temp.resolve("project-signed-installer.exe"), "test");
        WindowsAuthenticodeVerifier verifier = new WindowsAuthenticodeVerifier(
                (command, environment, timeout) -> {
                    throw new AssertionError("PowerShell should not run without an Authenticode requirement");
                });

        assertDoesNotThrow(() -> verifier.verify(installer, null));
    }

    @Test
    void rejectsUnsignedInstaller() throws Exception {
        Path installer = Files.writeString(temp.resolve("installer.exe"), "test");
        WindowsAuthenticodeVerifier verifier = new WindowsAuthenticodeVerifier(
                (command, environment, timeout) -> new CommandRunner.CommandResult(0, "NotSigned\t"));

        assertThrows(UpdateException.class, () -> verifier.verify(installer,
                new SignatureRequirement("authenticode", "Frostguard Project")));
    }

    @Test
    void rejectsUnexpectedPublisher() throws Exception {
        Path installer = Files.writeString(temp.resolve("installer.exe"), "test");
        WindowsAuthenticodeVerifier verifier = new WindowsAuthenticodeVerifier(
                (command, environment, timeout) -> new CommandRunner.CommandResult(0, "Valid\tCN=Someone Else"));

        assertThrows(UpdateException.class, () -> verifier.verify(installer,
                new SignatureRequirement("authenticode", "Frostguard Project")));
    }

    @Test
    void rejectsMissingInstallerBeforeRunningPowerShell() {
        WindowsAuthenticodeVerifier verifier = new WindowsAuthenticodeVerifier(
                (command, environment, timeout) -> {
                    throw new AssertionError("PowerShell should not run");
                });
        assertThrows(UpdateException.class, () -> verifier.verify(temp.resolve("missing.exe"),
                new SignatureRequirement("authenticode", "Frostguard Project")));
    }
}
