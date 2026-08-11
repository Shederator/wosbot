package dev.frostguard.update;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Pattern;

public record ManifestVerificationKey(String keyId, String publicKeyBase64) {
    private static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public ManifestVerificationKey {
        keyId = keyId == null ? "" : keyId.trim();
        publicKeyBase64 = publicKeyBase64 == null ? "" : publicKeyBase64.trim();
    }

    public boolean isUsable() {
        try {
            decodePublicKey();
            return isValidKeyId(keyId);
        } catch (UpdateException exception) {
            return false;
        }
    }

    PublicKey decodePublicKey() throws UpdateException {
        if (!isValidKeyId(keyId)) {
            throw new UpdateException("Pinned update-signing key ID is invalid");
        }
        try {
            byte[] encoded = Base64.getDecoder().decode(publicKeyBase64);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new UpdateException("Pinned update-signing public key is invalid", exception);
        }
    }

    static boolean isValidKeyId(String value) {
        return value != null && KEY_ID.matcher(value).matches();
    }
}
