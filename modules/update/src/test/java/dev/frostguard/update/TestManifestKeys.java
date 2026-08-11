package dev.frostguard.update;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

final class TestManifestKeys {
    static final String KEY_ID = "frostguard-test-1";
    private static final KeyPair KEY_PAIR = generate();

    private TestManifestKeys() {
    }

    static ManifestVerificationKey trustedKey() {
        return new ManifestVerificationKey(KEY_ID,
                Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
    }

    static String privateKey() {
        return Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded());
    }

    static byte[] signedEnvelope(byte[] payload) throws GeneralSecurityException {
        return ProjectManifestSigner.sign(payload, KEY_ID, privateKey());
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
