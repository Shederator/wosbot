package dev.frostguard.engine.diagnostics;

import dev.frostguard.api.domain.RawImageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticSnapshotStoreTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-09-21T14:30:12.483Z");

    @TempDir
    Path workspace;

    @Test
    void writesOneDecodablePngWithTimestampActivityAndType() throws IOException {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);

        Optional<String> relative = store.write(frame(4, 2), "Initialize", "initialize blocked", CAPTURED_AT);

        assertEquals(
                "logs/snapshot/20260921T143012.483Z-initialize-initialize-blocked.png",
                relative.orElseThrow());
        Path image = workspace.resolve(relative.orElseThrow());
        BufferedImage decoded = ImageIO.read(image.toFile());
        assertEquals(4, decoded.getWidth());
        assertEquals(2, decoded.getHeight());
        assertFalse(relative.orElseThrow().contains("Default"));
        assertFalse(image.getFileName().toString().contains(".."));
    }

    @Test
    void sanitizesIdentifiersOutOfTheFilename() {
        String fileName = DiagnosticSnapshotStore.fileName(
                "Profile Default / 127.0.0.1:16384",
                "../serial exception: device offline",
                CAPTURED_AT);

        assertEquals(
                "20260921T143012.483Z-profiledefault12700116384-serial-exception-device-offline.png",
                fileName);
        assertEquals("bear", DiagnosticSnapshotStore.activityToken("bear"));
        assertEquals("bearrally", DiagnosticSnapshotStore.activityToken("Bear Rally"));
        assertFalse(fileName.contains("/"));
        assertFalse(fileName.contains(":"));
        assertFalse(fileName.contains(".."));
    }

    @Test
    void collisionAddsANumericSuffixWithoutOverwriting() throws IOException {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);
        Optional<String> first = store.write(frame(2, 2), "initialize", "initialize-blocked", CAPTURED_AT);
        long firstSize = Files.size(workspace.resolve(first.orElseThrow()));

        Optional<String> second = store.write(frame(3, 2), "initialize", "initialize-blocked", CAPTURED_AT);

        assertEquals(
                "logs/snapshot/20260921T143012.483Z-initialize-initialize-blocked-2.png",
                second.orElseThrow());
        assertEquals(firstSize, Files.size(workspace.resolve(first.orElseThrow())));
        assertEquals(3, ImageIO.read(workspace.resolve(second.orElseThrow()).toFile()).getWidth());
    }

    @Test
    void retentionKeepsTwentyNewestCapturesPerActivity() throws IOException {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);
        Files.createDirectories(store.directory());
        Path notes = store.directory().resolve("notes.png");
        Files.writeString(notes, "keep");
        Instant initializeStart = Instant.parse("2026-09-21T00:00:00.000Z");
        Instant bearStart = Instant.parse("2026-09-21T01:00:00.000Z");

        for (int index = 0; index < 3; index++) {
            store.write(frame(2, 2), "bear", "rally-button-missing", bearStart.plusSeconds(index));
        }
        for (int index = 0; index < DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY + 1; index++) {
            store.write(frame(2, 2), "initialize", "initialize-blocked", initializeStart.plusSeconds(index));
        }

        assertEquals(DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY,
                capturesFor(store.directory(), "initialize").size());
        assertEquals(3, capturesFor(store.directory(), "bear").size());
        assertFalse(capturesFor(store.directory(), "initialize").stream()
                .anyMatch(path -> path.getFileName().toString().startsWith("20260921T000000.000Z-")));
        assertEquals("keep", Files.readString(notes));

        for (int index = 0; index < DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY + 1; index++) {
            store.write(frame(2, 2), "bear", "rally-button-missing", bearStart.plusSeconds(10 + index));
        }

        assertEquals(DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY,
                capturesFor(store.directory(), "initialize").size());
        assertEquals(DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY,
                capturesFor(store.directory(), "bear").size());
        assertFalse(capturesFor(store.directory(), "bear").stream()
                .anyMatch(path -> path.getFileName().toString().startsWith("20260921T010000.000Z-")));
        assertTrue(capturesFor(store.directory(), "bear").stream()
                .anyMatch(path -> path.getFileName().toString().contains("-bear-rally-button-missing")));
        assertEquals("keep", Files.readString(notes));
    }

    @Test
    void directoryOrConversionFailureReturnsEmptyWithoutThrowing() throws IOException {
        Path blockedRoot = workspace.resolve("not-a-directory");
        Files.writeString(blockedRoot, "occupied");
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(blockedRoot);

        assertTrue(store.write(frame(2, 2), "initialize", "initialize-blocked", CAPTURED_AT).isEmpty());
        assertTrue(new DiagnosticSnapshotStore(workspace)
                .write(RawImageData.capture(new byte[0], 0, 0, 0), "initialize", "initialize-blocked", CAPTURED_AT)
                .isEmpty());
    }

    @Test
    void concurrentWritesDoNotOverwriteOrEscapeTheRetentionCap() throws InterruptedException, IOException {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            for (int index = 0; index < 24; index++) {
                Instant capturedAt = CAPTURED_AT.plusMillis(index);
                executor.submit(() -> store.write(frame(2, 2), "initialize", "initialize-blocked", capturedAt));
            }
        } finally {
            executor.shutdown();
        }
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        List<Path> managed = managedInitializeCaptures(store.directory());
        assertEquals(DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY, managed.size());
        for (Path image : managed) {
            assertTrue(image.getFileName().toString().endsWith(".png"));
            assertEquals(2, ImageIO.read(image.toFile()).getWidth());
        }
    }

    @Test
    void disabledDesktopSettingDoesNotCaptureTheDesktop() {
        AtomicInteger captures = new AtomicInteger();
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(
                workspace, () -> false, () -> {
                    captures.incrementAndGet();
                    return Optional.of(desktopImage());
                });

        Optional<String> relative = store.write(frame(2, 2), "initialize", "initialize-blocked", CAPTURED_AT);

        assertTrue(relative.isPresent());
        assertEquals(0, captures.get());
        assertEquals(0, countFiles(store, "desktop"));
    }

    @Test
    void enabledDesktopSettingSavesASeparateDesktopFrame() throws IOException {
        DiagnosticSnapshotStore store = enabledStore(workspace, () -> Optional.of(desktopImage()));

        Optional<String> relative = store.write(frame(2, 2), "initialize", "initialize-blocked", CAPTURED_AT);

        assertEquals(
                "logs/snapshot/20260921T143012.483Z-initialize-initialize-blocked.png",
                relative.orElseThrow());
        assertTrue(Files.exists(workspace.resolve(
                "logs/snapshot/20260921T143012.483Z-desktop-initialize-blocked.png")));
        assertEquals(3, ImageIO.read(store.directory().resolve(
                "20260921T143012.483Z-desktop-initialize-blocked.png").toFile()).getWidth());
    }

    @Test
    void desktopCaptureFailureKeepsTheEmulatorSnapshot() {
        DiagnosticSnapshotStore store = enabledStore(workspace, () -> {
            throw new IllegalStateException("portal unavailable");
        });

        Optional<String> relative = store.write(frame(2, 2), "bear", "rally-button-missing", CAPTURED_AT);

        assertTrue(relative.isPresent());
        assertTrue(Files.exists(workspace.resolve(relative.orElseThrow())));
        assertEquals(0, countFiles(store, "desktop"));
    }

    @Test
    void desktopRetentionDoesNotRemoveInitializeOrBearFrames() throws IOException {
        DiagnosticSnapshotStore store = enabledStore(workspace, () -> Optional.of(desktopImage()));
        Instant start = Instant.parse("2026-09-21T02:00:00.000Z");

        for (int index = 0; index < 3; index++) {
            store.write(frame(2, 2), "bear", "rally-button-missing", start.plusSeconds(index));
        }
        for (int index = 0; index < DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY + 1; index++) {
            store.write(frame(2, 2), "initialize", "initialize-blocked", start.plusSeconds(60L + index));
        }

        assertEquals(3, capturesFor(store.directory(), "bear").size());
        assertEquals(DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY,
                capturesFor(store.directory(), "initialize").size());
        assertEquals(DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY,
                capturesFor(store.directory(), "desktop").size());
    }

    private static DiagnosticSnapshotStore enabledStore(Path workspace, DesktopFrameSource source) {
        return new DiagnosticSnapshotStore(workspace, () -> true, source);
    }

    private static BufferedImage desktopImage() {
        return new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
    }

    private static int countFiles(DiagnosticSnapshotStore store, String activity) {
        try {
            return capturesFor(store.directory(), activity).size();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static List<Path> capturesFor(Path directory, String activity) throws IOException {
        String marker = "Z-" + activity + "-";
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(path -> path.getFileName().toString().contains(marker))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static List<Path> managedInitializeCaptures(Path directory) throws IOException {
        return capturesFor(directory, "initialize");
    }

    private static RawImageData frame(int width, int height) {
        return RawImageData.capture(new byte[width * height * 4], width, height, 4);
    }
}
