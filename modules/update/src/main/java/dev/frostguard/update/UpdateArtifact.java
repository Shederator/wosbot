package dev.frostguard.update;

public record UpdateArtifact(
        String operatingSystem,
        String architecture,
        String fileName,
        String url,
        String sha256,
        long size,
        SignatureRequirement signature) {
}
