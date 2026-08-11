package dev.frostguard.update;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;

public final class SignedManifestCodec {
    static final int SUPPORTED_ENVELOPE_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private static final String ALGORITHM = "Ed25519";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    private final ManifestCodec manifestCodec;

    public SignedManifestCodec() {
        this(new ManifestCodec());
    }

    SignedManifestCodec(ManifestCodec manifestCodec) {
        this.manifestCodec = manifestCodec;
    }

    public UpdateManifest read(byte[] envelopeJson, ManifestVerificationKey trustedKey) throws UpdateException {
        if (trustedKey == null || !trustedKey.isUsable()) {
            throw new UpdateException("This build has no valid pinned update-signing key");
        }
        try {
            SignedManifestEnvelope envelope = mapper.readValue(envelopeJson, SignedManifestEnvelope.class);
            validateEnvelope(envelope, trustedKey);
            byte[] payload = decode(envelope.payload(), "payload");
            if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("signed manifest payload size is invalid");
            }
            byte[] signatureBytes = decode(envelope.signature(), "signature");
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(trustedKey.decodePublicKey());
            verifier.update(payload);
            if (!verifier.verify(signatureBytes)) {
                throw new IllegalArgumentException("signed manifest signature is invalid");
            }
            return manifestCodec.read(payload);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
            throw new UpdateException("Signed update manifest is invalid: " + exception.getMessage(), exception);
        }
    }

    private static void validateEnvelope(SignedManifestEnvelope envelope, ManifestVerificationKey trustedKey) {
        if (envelope.envelopeVersion() != SUPPORTED_ENVELOPE_VERSION) {
            throw new IllegalArgumentException("unsupported signed-manifest envelope version "
                    + envelope.envelopeVersion());
        }
        if (!ALGORITHM.equals(envelope.algorithm())) {
            throw new IllegalArgumentException("signed manifest must use Ed25519");
        }
        if (!trustedKey.keyId().equals(envelope.keyId())) {
            throw new IllegalArgumentException("signed manifest key ID does not match this build");
        }
        if (envelope.payload() == null || envelope.payload().isBlank()
                || envelope.signature() == null || envelope.signature().isBlank()) {
            throw new IllegalArgumentException("signed manifest payload and signature are required");
        }
    }

    private static byte[] decode(String value, String label) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("signed manifest " + label + " is not valid Base64", exception);
        }
    }
}
