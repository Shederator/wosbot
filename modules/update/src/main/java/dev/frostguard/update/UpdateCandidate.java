package dev.frostguard.update;

import dev.frostguard.api.runtime.RuntimeChannel;

import java.net.URI;
import java.time.Instant;

public record UpdateCandidate(
        RuntimeChannel channel,
        SemanticVersion version,
        Instant publishedAt,
        URI releaseNotes,
        UpdatePlatform platform,
        UpdateArtifact artifact) {
}
