package dev.frostguard.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class UpdateDownloader {
    private final DownloadTransport transport;
    private final ArtifactVerifier verifier;

    public UpdateDownloader() {
        this(new JdkDownloadTransport(), new ArtifactVerifier());
    }

    UpdateDownloader(DownloadTransport transport, ArtifactVerifier verifier) {
        this.transport = transport;
        this.verifier = verifier;
    }

    public Path download(UpdateCandidate candidate, Path workspaceCache) throws UpdateException {
        UpdateArtifact artifact = candidate.artifact();
        Path directory = workspaceCache.resolve("updates")
                .resolve(candidate.channel().directoryName())
                .resolve(candidate.version().toString());
        Path completed = directory.resolve(artifact.fileName());
        Path partial = directory.resolve(artifact.fileName() + ".part");
        Path lockPath = directory.resolve(artifact.fileName() + ".lock");
        try {
            Files.createDirectories(directory);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = tryLock(channel)) {
                if (Files.isRegularFile(completed)) {
                    try {
                        verifier.verify(completed, artifact);
                        return completed;
                    } catch (UpdateException invalidCompletedArtifact) {
                        Files.delete(completed);
                    }
                }
                long offset = Files.isRegularFile(partial) ? Files.size(partial) : 0L;
                if (offset == artifact.size()) {
                    try {
                        verifier.verify(partial, artifact);
                        promote(partial, completed);
                        return completed;
                    } catch (UpdateException invalidPartialArtifact) {
                        Files.delete(partial);
                        offset = 0L;
                    }
                } else if (offset > artifact.size()) {
                    Files.delete(partial);
                    offset = 0L;
                }
                transfer(URI.create(artifact.url()), partial, offset, artifact.size());
                try {
                    verifier.verify(partial, artifact);
                } catch (UpdateException invalidDownload) {
                    Files.deleteIfExists(partial);
                    throw invalidDownload;
                }
                promote(partial, completed);
                return completed;
            }
        } catch (OverlappingFileLockException exception) {
            throw new UpdateException("Another update download is already running", exception);
        } catch (IOException exception) {
            throw new UpdateException("Update download failed: " + exception.getMessage(), exception);
        }
    }

    private FileLock tryLock(FileChannel channel) throws IOException, UpdateException {
        FileLock lock = channel.tryLock();
        if (lock == null) {
            throw new UpdateException("Another update download is already running");
        }
        return lock;
    }

    private void transfer(URI uri, Path partial, long offset, long expectedSize) throws UpdateException, IOException {
        try (DownloadTransport.Response response = transport.open(uri, offset)) {
            boolean append = offset > 0 && response.statusCode() == 206;
            if (response.statusCode() != 200 && !append) {
                throw new UpdateException("Artifact request returned HTTP " + response.statusCode());
            }
            if (!append) {
                offset = 0L;
            }
            StandardOpenOption[] options = append
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING};
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(partial, options)) {
                byte[] buffer = new byte[64 * 1024];
                long total = offset;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > expectedSize) {
                        throw new UpdateException("Artifact download exceeded its declared size");
                    }
                    output.write(buffer, 0, read);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpdateException("Update download was interrupted", exception);
        }
    }

    private static void promote(Path partial, Path completed) throws IOException {
        Files.move(partial, completed, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
