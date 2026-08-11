package dev.frostguard.update;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectUpdateKeyTest {
    @Test
    void readsCommittedPublicTrustAnchor() {
        ManifestVerificationKey expected = TestManifestKeys.trustedKey();
        String properties = "keyId=" + expected.keyId() + "\npublicKey=" + expected.publicKeyBase64();
        ManifestVerificationKey actual = ProjectUpdateKey.read(new ByteArrayInputStream(
                properties.getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, actual);
        assertTrue(actual.isUsable());
        assertFalse(ProjectUpdateKey.read(null).isUsable());
    }
}
