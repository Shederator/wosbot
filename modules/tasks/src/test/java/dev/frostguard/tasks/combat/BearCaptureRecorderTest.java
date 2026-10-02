package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final Instant EVENT_END = Instant.parse("2026-10-03T19:30:00Z");

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
            recorder.observed(1, EVENT_END, Duration.ZERO, BearNavigationPolicy.Screen.WORLD, 0L, Map.of());
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
            recorder.observed(1, Instant.parse("2026-10-03T19:00:00Z"), Duration.ofMillis(120),
                    BearNavigationPolicy.Screen.WORLD, 1_800_000_000L, Map.of("GAME_HOME_WORLD", 90_000_000L));
            recorder.observed(2, Instant.parse("2026-10-03T19:00:02Z"), Duration.ofMillis(80),
                    BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY, 2_100_000_000L, Map.of());
            journal = recorder.directory().resolve("frames.jsonl");
        }

        List<String> lines = Files.readAllLines(journal);
        assertEquals(2, lines.size());
        assertEquals("{\"frame\":1,\"capturedAt\":\"2026-10-03T19:00:00Z\",\"transportAgeMs\":120,"
                + "\"screen\":\"WORLD\",\"classifyMs\":1800,\"templatesMs\":{\"GAME_HOME_WORLD\":90}}",
                lines.get(0));
        assertTrue(lines.get(1).contains("\"screen\":\"WORLD_ACTIVE_BEAR_ICON_READY\""));
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
                recorder.observed(frame, EVENT_END, Duration.ZERO,
                        BearNavigationPolicy.Screen.UNKNOWN, 0L, Map.of());
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

            assertTrue(elapsedMs < 500, "journal writes must not wait for the disk: " + elapsedMs + "ms");
            assertTrue(recorder.droppedJournalLines() > 0, "overflow is counted, not blocked on");
            release.countDown();
        }
    }
}
