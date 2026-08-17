package dev.frostguard.update;

import dev.frostguard.api.runtime.RuntimeChannel;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

public final class UpdateSelector {
    public Optional<UpdateCandidate> select(UpdateManifest manifest, RunningBuild running) throws UpdateException {
        if (!running.permitsAutomaticUpdates()) {
            return Optional.empty();
        }
        RuntimeChannel manifestChannel = RuntimeChannel.from(manifest.channel());
        if (manifestChannel != running.channel()) {
            throw new UpdateException("Update channel mismatch: running " + running.channel().directoryName()
                    + ", manifest " + manifestChannel.directoryName());
        }
        SemanticVersion minimumUpdater = SemanticVersion.parse(manifest.minimumUpdaterVersion());
        if (running.updaterVersion().compareTo(minimumUpdater) < 0) {
            throw new UpdateException("Updater " + running.updaterVersion()
                    + " is older than required version " + minimumUpdater);
        }
        SemanticVersion release = SemanticVersion.parse(manifest.version());
        if (release.compareTo(running.version()) <= 0) {
            return Optional.empty();
        }
        UpdateArtifact artifact = manifest.artifacts().get(running.platform().key());
        if (artifact == null) {
            throw new UpdateException("Manifest has no artifact for " + running.platform().key());
        }
        UpdatePlatform artifactPlatform = new UpdatePlatform(
                UpdatePlatform.OperatingSystem.from(artifact.operatingSystem()),
                UpdatePlatform.Architecture.from(artifact.architecture()));
        if (!artifactPlatform.equals(running.platform())) {
            throw new UpdateException("Selected artifact platform does not match the running application");
        }
        String pinnedPublisher = running.authenticodePublisher();
        String manifestPublisher = artifact.signature() == null ? "" : artifact.signature().publisher().trim();
        if (!pinnedPublisher.isBlank() || !manifestPublisher.isBlank()) {
            if (pinnedPublisher.isBlank() || manifestPublisher.isBlank()
                    || !pinnedPublisher.equalsIgnoreCase(manifestPublisher)) {
                throw new UpdateException("Manifest Authenticode publisher does not match this build");
            }
        }
        return Optional.of(new UpdateCandidate(manifestChannel, release, Instant.parse(manifest.publishedAt()),
                URI.create(manifest.releaseNotesUrl()), artifactPlatform, artifact));
    }
}
