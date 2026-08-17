package dev.frostguard.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ArtifactVerifier {
    public void verify(Path artifact, UpdateArtifact expected) throws UpdateException {
        try {
            long actualSize = Files.size(artifact);
            if (actualSize != expected.size()) {
                throw new UpdateException("Downloaded artifact size mismatch: expected " + expected.size()
                        + " bytes, received " + actualSize);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(artifact)) {
                input.transferTo(new java.io.OutputStream() {
                    @Override
                    public void write(int value) {
                        digest.update((byte) value);
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) {
                        digest.update(bytes, offset, length);
                    }
                });
            }
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(expected.sha256())) {
                throw new UpdateException("Downloaded artifact SHA-256 mismatch");
            }
        } catch (IOException exception) {
            throw new UpdateException("Could not verify downloaded artifact: " + exception.getMessage(), exception);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
