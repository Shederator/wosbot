package dev.frostguard.update;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignedManifestCodecTest {
    private final SignedManifestCodec codec = new SignedManifestCodec();
    private final byte[] payload = ManifestCodecTest.validUnsignedManifest()
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void verifiesEnvelopeBeforeParsingManifest() throws Exception {
        UpdateManifest manifest = codec.read(TestManifestKeys.signedEnvelope(payload),
                TestManifestKeys.trustedKey());

        assertEquals("3.0.1", manifest.version());
    }

    @Test
    void rejectsModifiedPayloadAndSignature() throws Exception {
        String envelope = new String(TestManifestKeys.signedEnvelope(payload), StandardCharsets.UTF_8);
        String encodedPayload = Base64.getEncoder().encodeToString(payload);
        String modifiedPayload = Base64.getEncoder().encodeToString(
                ManifestCodecTest.validUnsignedManifest().replace("3.0.1", "3.0.2")
                        .getBytes(StandardCharsets.UTF_8));
        assertThrows(UpdateException.class, () -> codec.read(
                envelope.replace(encodedPayload, modifiedPayload).getBytes(StandardCharsets.UTF_8),
                TestManifestKeys.trustedKey()));

        String signatureField = envelope.substring(envelope.indexOf("\"signature\": \"") + 14);
        String signature = signatureField.substring(0, signatureField.indexOf('"'));
        String damaged = (signature.charAt(0) == 'A' ? "B" : "A") + signature.substring(1);
        byte[] damagedEnvelope = envelope.replace(signature, damaged).getBytes(StandardCharsets.UTF_8);
        assertThrows(UpdateException.class, () -> codec.read(
                damagedEnvelope, TestManifestKeys.trustedKey()));
    }

    @Test
    void rejectsWrongKeyAndKeyId() throws Exception {
        byte[] envelope = TestManifestKeys.signedEnvelope(payload);
        var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ManifestVerificationKey wrongKey = new ManifestVerificationKey(TestManifestKeys.KEY_ID,
                Base64.getEncoder().encodeToString(other.getPublic().getEncoded()));
        assertThrows(UpdateException.class, () -> codec.read(envelope, wrongKey));

        ManifestVerificationKey wrongId = new ManifestVerificationKey("frostguard-test-2",
                TestManifestKeys.trustedKey().publicKeyBase64());
        assertThrows(UpdateException.class, () -> codec.read(envelope, wrongId));
    }

    @Test
    void rejectsMalformedUnsignedOrUnknownEnvelopeData() throws Exception {
        String envelope = new String(TestManifestKeys.signedEnvelope(payload), StandardCharsets.UTF_8);
        assertThrows(UpdateException.class, () -> codec.read(payload, TestManifestKeys.trustedKey()));
        assertThrows(UpdateException.class, () -> codec.read(
                envelope.replace("\"algorithm\": \"Ed25519\"", "\"algorithm\": \"RSA\"")
                        .getBytes(StandardCharsets.UTF_8), TestManifestKeys.trustedKey()));
        assertThrows(UpdateException.class, () -> codec.read(
                envelope.replace("\"payload\": \"", "\"extra\": true, \"payload\": \"")
                        .getBytes(StandardCharsets.UTF_8), TestManifestKeys.trustedKey()));
        assertThrows(UpdateException.class, () -> codec.read(
                envelope.replace("\"algorithm\": \"Ed25519\"",
                                "\"algorithm\": \"Ed25519\", \"algorithm\": \"Ed25519\"")
                        .getBytes(StandardCharsets.UTF_8), TestManifestKeys.trustedKey()));
        assertThrows(UpdateException.class, () -> codec.read(
                envelope.replaceFirst("\"payload\": \"[^\"]+\"", "\"payload\": \"%%%\"")
                        .getBytes(StandardCharsets.UTF_8), TestManifestKeys.trustedKey()));
    }
}
