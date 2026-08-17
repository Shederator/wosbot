package dev.frostguard.update;

import java.util.Map;

public record UpdateManifest(
        int schemaVersion,
        String channel,
        String version,
        String publishedAt,
        String minimumUpdaterVersion,
        String releaseNotesUrl,
        Map<String, UpdateArtifact> artifacts) {
}
