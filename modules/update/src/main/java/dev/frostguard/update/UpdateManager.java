package dev.frostguard.update;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateManager {
    private final ManifestClient manifests;
    private final UpdateSelector selector;
    private final UpdateDownloader downloader;
    private final InstallerTrustVerifier trustVerifier;
    private final InstallerHandoff handoff;
    private final AtomicBoolean operationActive = new AtomicBoolean();

    public UpdateManager(InstallerTrustVerifier trustVerifier, InstallerHandoff handoff) {
        this(new ManifestClient(), new UpdateSelector(), new UpdateDownloader(), trustVerifier, handoff);
    }

    UpdateManager(ManifestClient manifests, UpdateSelector selector, UpdateDownloader downloader,
                  InstallerTrustVerifier trustVerifier, InstallerHandoff handoff) {
        this.manifests = manifests;
        this.selector = selector;
        this.downloader = downloader;
        this.trustVerifier = trustVerifier;
        this.handoff = handoff;
    }

    public Optional<UpdateCandidate> check(URI manifestUri, RunningBuild running) throws UpdateException {
        return exclusive(() -> selector.select(manifests.fetch(manifestUri, running.manifestKey()), running));
    }

    public PreparedUpdate prepare(UpdateCandidate candidate, Path workspaceCache) throws UpdateException {
        return exclusive(() -> {
            Path installer = downloader.download(candidate, workspaceCache);
            trustVerifier.verify(installer, candidate.artifact().signature());
            return new PreparedUpdate(candidate, installer);
        });
    }

    public InstallerHandoff.HandoffSession stageHandoff(
            PreparedUpdate update, long parentPid, Path restartLauncher, Path workspaceRoot) throws UpdateException {
        return exclusive(() -> handoff.stage(
                update.installer(), parentPid, restartLauncher, workspaceRoot));
    }

    public boolean isOperationActive() {
        return operationActive.get();
    }

    private <T> T exclusive(UpdateOperation<T> operation) throws UpdateException {
        if (!operationActive.compareAndSet(false, true)) {
            throw new UpdateException("Another update operation is already running");
        }
        try {
            return operation.run();
        } finally {
            operationActive.set(false);
        }
    }

    @FunctionalInterface
    private interface UpdateOperation<T> {
        T run() throws UpdateException;
    }
}
