package dev.frostguard.update;

import dev.frostguard.api.runtime.RuntimeChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateDownloaderTest {
    private static final byte[] INSTALLER = "signed-installer-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    @Test
    void resumesPartialDownloadWithRangeResponse() throws Exception {
        UpdateCandidate candidate = candidate(INSTALLER, sha256(INSTALLER));
        Path partial = updateDirectory(candidate).resolve(candidate.artifact().fileName() + ".part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, java.util.Arrays.copyOf(INSTALLER, 7));
        AtomicLong requestedOffset = new AtomicLong();
        DownloadTransport transport = (uri, offset) -> {
            requestedOffset.set(offset);
            return response(206, java.util.Arrays.copyOfRange(INSTALLER, (int) offset, INSTALLER.length));
        };

        Path result = new UpdateDownloader(transport, new ArtifactVerifier()).download(candidate, temp);

        assertEquals(7L, requestedOffset.get());
        assertArrayEquals(INSTALLER, Files.readAllBytes(result));
        assertFalse(Files.exists(partial));
    }

    @Test
    void promotesAlreadyCompletePartialDownloadWithoutAnotherRequest() throws Exception {
        UpdateCandidate candidate = candidate(INSTALLER, sha256(INSTALLER));
        Path partial = updateDirectory(candidate).resolve(candidate.artifact().fileName() + ".part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, INSTALLER);
        AtomicBoolean requestAttempted = new AtomicBoolean();

        Path result = new UpdateDownloader((uri, offset) -> {
            requestAttempted.set(true);
            return response(416, new byte[0]);
        }, new ArtifactVerifier()).download(candidate, temp);

        assertFalse(requestAttempted.get());
        assertArrayEquals(INSTALLER, Files.readAllBytes(result));
        assertFalse(Files.exists(partial));
    }

    @Test
    void restartsACompleteButCorruptPartialDownload() throws Exception {
        UpdateCandidate candidate = candidate(INSTALLER, sha256(INSTALLER));
        Path partial = updateDirectory(candidate).resolve(candidate.artifact().fileName() + ".part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, "x".repeat(INSTALLER.length).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicLong requestedOffset = new AtomicLong(-1L);

        Path result = new UpdateDownloader((uri, offset) -> {
            requestedOffset.set(offset);
            return response(200, INSTALLER);
        }, new ArtifactVerifier()).download(candidate, temp);

        assertEquals(0L, requestedOffset.get());
        assertArrayEquals(INSTALLER, Files.readAllBytes(result));
    }

    @Test
    void restartsWhenServerIgnoresRange() throws Exception {
        UpdateCandidate candidate = candidate(INSTALLER, sha256(INSTALLER));
        Path partial = updateDirectory(candidate).resolve(candidate.artifact().fileName() + ".part");
        Files.createDirectories(partial.getParent());
        Files.writeString(partial, "stale");

        Path result = new UpdateDownloader((uri, offset) -> response(200, INSTALLER), new ArtifactVerifier())
                .download(candidate, temp);

        assertArrayEquals(INSTALLER, Files.readAllBytes(result));
    }

    @Test
    void replacesCorruptCompletedDownload() throws Exception {
        UpdateCandidate candidate = candidate(INSTALLER, sha256(INSTALLER));
        Path completed = updateDirectory(candidate).resolve(candidate.artifact().fileName());
        Files.createDirectories(completed.getParent());
        Files.writeString(completed, "corrupt");
        AtomicLong requestedOffset = new AtomicLong(-1L);

        Path result = new UpdateDownloader((uri, offset) -> {
            requestedOffset.set(offset);
            return response(200, INSTALLER);
        }, new ArtifactVerifier()).download(candidate, temp);

        assertEquals(0L, requestedOffset.get());
        assertArrayEquals(INSTALLER, Files.readAllBytes(result));
    }

    @Test
    void neverPromotesHashMismatch() throws Exception {
        UpdateCandidate candidate = candidate(INSTALLER, "a".repeat(64));
        UpdateDownloader downloader = new UpdateDownloader((uri, offset) -> response(200, INSTALLER),
                new ArtifactVerifier());

        assertThrows(UpdateException.class, () -> downloader.download(candidate, temp));
        assertFalse(Files.exists(updateDirectory(candidate).resolve(candidate.artifact().fileName())));
        assertFalse(Files.exists(updateDirectory(candidate).resolve(candidate.artifact().fileName() + ".part")));
    }

    @Test
    void resumesAfterInterruptedTransfer() throws Exception {
        UpdateCandidate candidate = candidate(INSTALLER, sha256(INSTALLER));
        AtomicInteger requestCount = new AtomicInteger();
        AtomicLong resumedOffset = new AtomicLong(-1L);
        DownloadTransport transport = (uri, offset) -> {
            if (requestCount.getAndIncrement() == 0) {
                return response(200, new InputStream() {
                    private int position;

                    @Override
                    public int read() throws IOException {
                        if (position == 7) {
                            throw new IOException("connection reset");
                        }
                        return position < INSTALLER.length ? INSTALLER[position++] : -1;
                    }
                });
            }
            resumedOffset.set(offset);
            return response(206, java.util.Arrays.copyOfRange(INSTALLER, (int) offset, INSTALLER.length));
        };
        UpdateDownloader downloader = new UpdateDownloader(transport, new ArtifactVerifier());

        assertThrows(UpdateException.class, () -> downloader.download(candidate, temp));
        Path result = downloader.download(candidate, temp);

        assertEquals(7L, resumedOffset.get());
        assertArrayEquals(INSTALLER, Files.readAllBytes(result));
    }

    @Test
    void rejectsDeclaredSizeMismatchWithoutPromotingArtifact() throws Exception {
        byte[] truncated = java.util.Arrays.copyOf(INSTALLER, INSTALLER.length - 1);
        UpdateCandidate candidate = candidate(INSTALLER, sha256(INSTALLER));
        UpdateDownloader downloader = new UpdateDownloader((uri, offset) -> response(200, truncated),
                new ArtifactVerifier());

        assertThrows(UpdateException.class, () -> downloader.download(candidate, temp));

        assertFalse(Files.exists(updateDirectory(candidate).resolve(candidate.artifact().fileName())));
        assertFalse(Files.exists(updateDirectory(candidate).resolve(candidate.artifact().fileName() + ".part")));
    }

    @Test
    void rejectsConcurrentDownloadLock() throws Exception {
        UpdateCandidate candidate = candidate(INSTALLER, sha256(INSTALLER));
        Path lockPath = updateDirectory(candidate).resolve(candidate.artifact().fileName() + ".lock");
        Files.createDirectories(lockPath.getParent());
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            UpdateDownloader downloader = new UpdateDownloader((uri, offset) -> response(200, INSTALLER),
                    new ArtifactVerifier());
            assertThrows(UpdateException.class, () -> downloader.download(candidate, temp));
        }
    }

    private Path updateDirectory(UpdateCandidate candidate) {
        return temp.resolve("updates").resolve("stable").resolve(candidate.version().toString());
    }

    private static UpdateCandidate candidate(byte[] bytes, String hash) {
        UpdateArtifact artifact = new UpdateArtifact("windows", "x64",
                "Frostguard-3.0.1-windows-x64.msi",
                "https://example.com/releases/3.0.1/Frostguard-3.0.1-windows-x64.msi",
                hash, bytes.length, new SignatureRequirement("authenticode", "CN=Frostguard Project, O=Frostguard"));
        return new UpdateCandidate(RuntimeChannel.STABLE, SemanticVersion.parse("3.0.1"),
                java.time.Instant.parse("2026-08-10T04:00:00Z"), URI.create("https://example.com/releases/3.0.1"),
                UpdateSelectorTest.windowsX64(), artifact);
    }

    private static DownloadTransport.Response response(int status, byte[] body) {
        return response(status, new ByteArrayInputStream(body));
    }

    private static DownloadTransport.Response response(int status, InputStream body) {
        return new DownloadTransport.Response() {
            private final InputStream input = body;

            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public InputStream body() {
                return input;
            }

            @Override
            public void close() throws IOException {
                input.close();
            }
        };
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
