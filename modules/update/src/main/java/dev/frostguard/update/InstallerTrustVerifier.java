package dev.frostguard.update;

import java.nio.file.Path;

public interface InstallerTrustVerifier {
    void verify(Path installer, SignatureRequirement requirement) throws UpdateException;
}
