package dev.frostguard.update;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Trust verifier for project-signed update feeds that do not embed an extra
 * platform publisher check (macOS pkg packages before notarization).
 */
public final class ProjectSignedInstallerVerifier implements InstallerTrustVerifier {
    @Override
    public void verify(Path installer, SignatureRequirement requirement) throws UpdateException {
        if (!Files.isRegularFile(installer)) {
            throw new UpdateException("Verified installer does not exist: " + installer);
        }
        if (requirement != null) {
            throw new UpdateException("Unexpected platform signature requirement: " + requirement.type());
        }
    }
}
