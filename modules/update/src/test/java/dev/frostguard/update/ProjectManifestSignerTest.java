package dev.frostguard.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectManifestSignerTest {
    @TempDir
    Path temp;

    @Test
    void producesRuntimeCompatibleEnvelope() throws Exception {
        byte[] payload = ManifestCodecTest.validUnsignedManifest().getBytes(StandardCharsets.UTF_8);
        byte[] envelope = ProjectManifestSigner.sign(
                payload, TestManifestKeys.KEY_ID, TestManifestKeys.privateKey());

        UpdateManifest manifest = new SignedManifestCodec().read(envelope, TestManifestKeys.trustedKey());
        assertEquals("3.0.1", manifest.version());
    }

    @Test
    void generatesNonOverwritingEd25519KeyPair() throws Exception {
        Path privateKey = temp.resolve("private.txt");
        Path publicKey = temp.resolve("public.txt");
        ProjectManifestSigner.generate(privateKey, publicKey);

        ManifestVerificationKey key = new ManifestVerificationKey("generated-test-key",
                Files.readString(publicKey));
        byte[] payload = ManifestCodecTest.validUnsignedManifest().getBytes(StandardCharsets.UTF_8);
        byte[] envelope = ProjectManifestSigner.sign(payload, key.keyId(), Files.readString(privateKey));
        assertTrue(key.isUsable());
        assertEquals("3.0.1", new SignedManifestCodec().read(envelope, key).version());
        assertThrows(FileAlreadyExistsException.class,
                () -> ProjectManifestSigner.generate(privateKey, publicKey));
    }
}
