package dev.frostguard.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class WindowsAuthenticodeVerifier implements InstallerTrustVerifier {
    private static final String INSTALLER_ENV = "FROSTGUARD_UPDATE_INSTALLER";
    private static final String POWERSHELL = "$signature = Get-AuthenticodeSignature -LiteralPath $env:"
            + INSTALLER_ENV + "; if ($null -eq $signature.SignerCertificate) { "
            + "Write-Output ($signature.Status.ToString() + \"`t\"); exit 0 }; "
            + "Write-Output ($signature.Status.ToString() + \"`t\" + $signature.SignerCertificate.Subject)";

    private final CommandRunner runner;

    public WindowsAuthenticodeVerifier() {
        this(new ProcessCommandRunner());
    }

    WindowsAuthenticodeVerifier(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public void verify(Path installer, SignatureRequirement requirement) throws UpdateException {
        if (!Files.isRegularFile(installer)) {
            throw new UpdateException("Verified installer does not exist: " + installer);
        }
        if (requirement == null) {
            return;
        }
        if (!"authenticode".equalsIgnoreCase(requirement.type())
                || requirement.publisher() == null || requirement.publisher().isBlank()) {
            throw new UpdateException("Windows update has an invalid Authenticode trust requirement");
        }
        try {
            CommandRunner.CommandResult result = runner.run(List.of(
                            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                            "-Command", POWERSHELL),
                    Map.of(INSTALLER_ENV, installer.toAbsolutePath().normalize().toString()), Duration.ofSeconds(30));
            if (result.exitCode() != 0) {
                throw new UpdateException("Authenticode verification command failed with exit " + result.exitCode());
            }
            String[] fields = result.output().split("\\t", 2);
            String status = fields.length > 0 ? fields[0].trim() : "";
            String subject = fields.length > 1 ? fields[1].trim() : "";
            if (!"valid".equalsIgnoreCase(status)) {
                throw new UpdateException(
                        "Installer Authenticode status is " + (status.isBlank() ? "unknown" : status));
            }
            if (!subject.equalsIgnoreCase(requirement.publisher().trim())) {
                throw new UpdateException("Installer signer does not match the required publisher");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpdateException("Authenticode verification was interrupted", exception);
        } catch (IOException exception) {
            throw new UpdateException("Authenticode verification failed: " + exception.getMessage(), exception);
        }
    }
}
