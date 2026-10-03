package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.frostguard.api.domain.RawImageData;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BearCaptureRecorderTest {

    @Test
    void segmentCreationFailureLatchesUnhealthyState(@TempDir Path logs) throws Exception {
        try (var recorder = BearCaptureRecorder.open(logs, EVENT_END)) {
            Files.createDirectory(recorder.directory().resolve("segment-001.h264"));
            assertNull(recorder.nextSegment());
            assertEquals("segment-open-failed", recorder.failureReason());
            try (var ignored = recorder.nextSegment()) {
                assertEquals("segment-open-failed", recorder.failureReason(), "a later segment cannot hide lost evidence");
            }
        }
    }

    @Test
    void staticImageWriteFailureLatchesUnhealthyState(@TempDir Path logs) throws Exception {
        try (var recorder = BearCaptureRecorder.open(logs, EVENT_END)) {
            Files.createDirectory(recorder.directory().resolve("screencap-001.png"));
            Files.writeString(recorder.directory().resolve("screencap-001.png/blocker"), "prevent replacement");
            assertNull(recorder.staticScreenshot(RawImageData.capture(new byte[4], 1, 1, 32)));
            assertEquals("screenshot-write-failed", recorder.failureReason());
        }
    }

    @Test
    void backgroundWriterFailureIsVisibleToInputGuard(@TempDir Path logs) throws Exception {
        try (var recorder = BearCaptureRecorder.open(logs, EVENT_END, 4, () -> {
            throw new java.io.UncheckedIOException(new java.io.IOException("injected write failure"));
        })) {
            recorder.transition(1, "test");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (recorder.failureReason() == null && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals("journal-write-failed", recorder.failureReason());
        }
    }

    @Test
    void transitionJournalSeparatesLatestSampleFromAuthorizingFrame(@TempDir Path logs) throws Exception {
        Path journal;
        try (var recorder = BearCaptureRecorder.open(logs, Instant.parse("2026-10-03T13:55:00Z"))) {
            journal = recorder.directory().resolve("frames.jsonl");
            recorder.transition(9, "transition frame=7 expected=\"WORLD\"\nconfirmed");
        }
        var lines = Files.readAllLines(journal);
        assertEquals(1, lines.size());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lines.getFirst());
        assertEquals(9, json.get("latestSampledFrame").asLong());
        assertEquals("transition frame=7 expected=\"WORLD\"\nconfirmed", json.get("diagnostic").asText());
    }

    private static final Instant EVENT_END = Instant.parse("2026-10-03T19:30:00Z");
    private static final BearRealtimeFrameSource.FrameOrigin VIDEO_1 = new BearRealtimeFrameSource.FrameOrigin(
            BearRealtimeFrameSource.FrameOrigin.Kind.VIDEO, 1, 1);

    @Test
    void aStaticScreenshotIsSavedOnceUntilTheScreenChanges(@TempDir Path logs) throws Exception {
        RawImageData screen = RawImageData.capture(new byte[720 * 1280 * 4], 720, 1280, 32);
        byte[] changedPixels = new byte[720 * 1280 * 4];
        changedPixels[0] = 9;
        RawImageData changed = RawImageData.capture(changedPixels, 720, 1280, 32);
        Path directory;
        String first;
        String repeated;
        String next;
        try (BearCaptureRecorder recorder = BearCaptureRecorder.open(logs, EVENT_END)) {
            directory = recorder.directory();
            first = recorder.staticScreenshot(screen);
            repeated = recorder.staticScreenshot(RawImageData.capture(screen.getFrameBytes().clone(), 720, 1280, 32));
            next = recorder.staticScreenshot(changed);
        }

        assertEquals("screencap-001.png", first);
        assertEquals(first, repeated, "an unchanged static screen references the image already saved");
        assertEquals("screencap-002.png", next);
        assertTrue(Files.size(directory.resolve(first)) > 0, "the referenced screenshot is on disk");
        assertTrue(Files.size(directory.resolve(next)) > 0);
    }

    @Test
    void aRecordingFailureIsJournaledOncePerSegment(@TempDir Path logs) throws Exception {
        Path journal;
        try (BearCaptureRecorder recorder = BearCaptureRecorder.open(logs, EVENT_END)) {
            recorder.recordingFailed(2, "disk full");
            recorder.recordingFailed(2, "disk full");
            assertEquals("segment-write-failed", recorder.failureReason());
            journal = recorder.directory().resolve("frames.jsonl");
        }

        assertEquals(List.of("{\"event\":\"recordingFailed\",\"segment\":2,\"reason\":\"disk full\"}"),
                Files.readAllLines(journal));
    }

    @Test
    void eachRecorderRenewalWritesItsOwnNumberedSegment(@TempDir Path logs) throws Exception {
        try (BearCaptureRecorder recorder = BearCaptureRecorder.open(logs, EVENT_END)) {
            try (OutputStream first = recorder.nextSegment()) {
                first.write(new byte[] {1, 2, 3});
            }
            try (OutputStream second = recorder.nextSegment()) {
                second.write(new byte[] {4});
            }

            Path session = recorder.directory();
            assertTrue(session.startsWith(logs.resolve("bear-capture")));
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(session.resolve("segment-001.h264")));
            assertArrayEquals(new byte[] {4}, Files.readAllBytes(session.resolve("segment-002.h264")));
        }
    }

    @Test
    void aLaterRunInTheSameEventNeverOverwritesEarlierEvidence(@TempDir Path logs) throws Exception {
        Path first;
        try (BearCaptureRecorder recorder = BearCaptureRecorder.open(logs, EVENT_END)) {
            first = recorder.directory();
            try (OutputStream segment = recorder.nextSegment()) {
                segment.write(new byte[] {1, 2, 3});
            }
            recorder.observed(1, VIDEO_1, null, EVENT_END, Duration.ZERO, BearNavigationPolicy.Screen.WORLD, 0L, Map.of());
        }
        Path second;
        try (BearCaptureRecorder retry = BearCaptureRecorder.open(logs, EVENT_END)) {
            second = retry.directory();
            try (OutputStream segment = retry.nextSegment()) {
                segment.write(new byte[] {9});
            }
        }

        assertTrue(!first.equals(second), "each run records into its own directory");
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(first.resolve("segment-001.h264")));
        assertEquals(1, Files.readAllLines(first.resolve("frames.jsonl")).size());
    }

    @Test
    void journalKeepsObservationOrderAsJsonLines(@TempDir Path logs) throws Exception {
        Path journal;
        try (BearCaptureRecorder recorder = BearCaptureRecorder.open(logs, EVENT_END)) {
            recorder.observed(1, VIDEO_1, null, Instant.parse("2026-10-03T19:00:00Z"), Duration.ofMillis(120),
                    BearNavigationPolicy.Screen.WORLD, 1_800_000_000L, Map.of("GAME_HOME_WORLD", 90_000_000L));
            recorder.observed(2, new BearRealtimeFrameSource.FrameOrigin(
                    BearRealtimeFrameSource.FrameOrigin.Kind.SCREENCAP, 1, 1), "screencap-001.png",
                    Instant.parse("2026-10-03T19:00:02Z"), Duration.ofMillis(80),
                    BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY, 2_100_000_000L, Map.of());
            journal = recorder.directory().resolve("frames.jsonl");
        }

        List<String> lines = Files.readAllLines(journal);
        assertEquals(2, lines.size());
        assertEquals("{\"frame\":1,\"source\":\"video\",\"segment\":1,\"decodedFrame\":1,"
                + "\"capturedAt\":\"2026-10-03T19:00:00Z\",\"transportAgeMs\":120,"
                + "\"screen\":\"WORLD\",\"classifyMs\":1800,\"templatesMs\":{\"GAME_HOME_WORLD\":90}}",
                lines.get(0));
        assertTrue(lines.get(1).contains("\"screen\":\"WORLD_ACTIVE_BEAR_ICON_READY\""));
        assertTrue(lines.get(1).startsWith("{\"frame\":2,\"source\":\"screencap\",\"segment\":1,"
                + "\"afterDecodedFrame\":1,\"image\":\"screencap-001.png\","), lines.get(1));
    }

    @Test
    void aStalledJournalNeverBlocksTheObserver(@TempDir Path logs) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (BearCaptureRecorder recorder = BearCaptureRecorder.open(logs, EVENT_END, 4, () -> {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        })) {
            long started = System.nanoTime();
            for (int frame = 1; frame <= 1_000; frame++) {
                recorder.observed(frame, VIDEO_1, null, EVENT_END, Duration.ZERO,
                        BearNavigationPolicy.Screen.UNKNOWN, 0L, Map.of());
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

            assertTrue(elapsedMs < 500, "journal writes must not wait for the disk: " + elapsedMs + "ms");
            assertTrue(recorder.droppedJournalLines() > 0, "overflow is counted, not blocked on");
            assertEquals("journal-overflow", recorder.failureReason());
            release.countDown();
        }
    }
}
