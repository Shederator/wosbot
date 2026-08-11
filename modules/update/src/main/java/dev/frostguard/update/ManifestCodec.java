package dev.frostguard.update;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.frostguard.api.runtime.RuntimeChannel;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;

public final class ManifestCodec {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public UpdateManifest read(byte[] json) throws UpdateException {
        try {
            UpdateManifest manifest = mapper.readValue(json, UpdateManifest.class);
            validate(manifest);
            return manifest;
        } catch (IOException | IllegalArgumentException exception) {
            throw new UpdateException("Update manifest is invalid: " + exception.getMessage(), exception);
        }
    }

    private void validate(UpdateManifest manifest) {
        if (manifest.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schema version " + manifest.schemaVersion());
        }
        RuntimeChannel channel = RuntimeChannel.from(required(manifest.channel(), "channel"));
        if (channel == RuntimeChannel.DEVELOPMENT) {
            throw new IllegalArgumentException("development manifests are not publishable");
        }
        SemanticVersion version = SemanticVersion.parse(manifest.version());
        SemanticVersion.parse(manifest.minimumUpdaterVersion());
        parseInstant(manifest.publishedAt());
        requireHttps(manifest.releaseNotesUrl(), "release notes URL");
        if (manifest.artifacts() == null || manifest.artifacts().isEmpty()) {
            throw new IllegalArgumentException("at least one update artifact is required");
        }
        for (Map.Entry<String, UpdateArtifact> entry : manifest.artifacts().entrySet()) {
            validateArtifact(entry.getKey(), entry.getValue(), version);
        }
    }

    private void validateArtifact(String key, UpdateArtifact artifact, SemanticVersion version) {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact " + key + " is missing");
        }
        UpdatePlatform platform = new UpdatePlatform(
                UpdatePlatform.OperatingSystem.from(required(artifact.operatingSystem(), "artifact operating system")),
                UpdatePlatform.Architecture.from(required(artifact.architecture(), "artifact architecture")));
        if (!platform.key().equals(key)) {
            throw new IllegalArgumentException("artifact key " + key + " does not match " + platform.key());
        }
        String fileName = required(artifact.fileName(), "artifact file name");
        if (fileName.contains("/") || fileName.contains("\\") || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("artifact file name must not contain a path");
        }
        URI artifactUri = requireHttps(artifact.url(), "artifact URL");
        String path = artifactUri.getPath();
        if (path == null || !path.endsWith("/" + fileName) || !fileName.contains(version.toString())) {
            throw new IllegalArgumentException("artifact URL and file name must carry the immutable release version");
        }
        if (artifact.size() <= 0) {
            throw new IllegalArgumentException("artifact size must be positive");
        }
        String hash = required(artifact.sha256(), "artifact SHA-256");
        if (hash.length() != 64) {
            throw new IllegalArgumentException("artifact SHA-256 must contain 64 hexadecimal characters");
        }
        HexFormat.of().parseHex(hash);
        if (platform.operatingSystem() == UpdatePlatform.OperatingSystem.WINDOWS) {
            SignatureRequirement signature = artifact.signature();
            if (signature != null && (!"authenticode".equalsIgnoreCase(signature.type())
                    || required(signature.publisher(), "Authenticode publisher").isBlank())) {
                throw new IllegalArgumentException("Windows Authenticode requirement is invalid");
            }
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(required(value, "publication time"));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("publication time must use ISO-8601 UTC", exception);
        }
    }

    private static URI requireHttps(String value, String label) {
        URI uri = URI.create(required(value, label));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException(label + " must use HTTPS");
        }
        return uri;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
