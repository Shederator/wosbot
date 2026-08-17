package dev.frostguard.update;

record SignedManifestEnvelope(
        int envelopeVersion,
        String algorithm,
        String keyId,
        String payload,
        String signature) {
}
