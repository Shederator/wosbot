package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.convert.ImageConverter;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local evidence for one Bear event: the raw H.264 recorder output, one segment per bounded
 * recorder renewal, and a per-frame classification journal. It exists to calibrate templates,
 * areas, and classification latency against real frames; it never influences a Bear decision.
 *
 * <p>Journal writes go through a bounded background queue. A slow disk drops and counts lines
 * instead of delaying the frame loop.
 */
final class BearCaptureRecorder implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BearCaptureRecorder.class);
    private static final DateTimeFormatter SESSION_NAME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final int JOURNAL_CAPACITY = 4_096;
    private static final String END_OF_JOURNAL = "\u0000";

    private final Path directory;
    private final AtomicInteger segments = new AtomicInteger();
    private final AtomicLong droppedLines = new AtomicLong();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final BlockingQueue<String> journal;
    private final Thread journalWriter;
    private final Runnable beforeEachWrite;
    private final AtomicInteger screenshots = new AtomicInteger();
    private String lastScreenshot;
    private long lastScreenshotChecksum;
    private int lastFailedSegment;

    private BearCaptureRecorder(Path directory, int journalCapacity, Runnable beforeEachWrite)
            throws IOException {
        this.directory = directory;
        this.journal = new ArrayBlockingQueue<>(journalCapacity);
        this.beforeEachWrite = beforeEachWrite;
        BufferedWriter writer = Files.newBufferedWriter(directory.resolve("frames.jsonl"), StandardCharsets.UTF_8);
        this.journalWriter = new Thread(() -> drain(writer), "BearCaptureJournal");
        this.journalWriter.setDaemon(true);
        this.journalWriter.start();
    }

    static BearCaptureRecorder open(Path logsRoot, Instant eventEnd) throws IOException {
        return open(logsRoot, eventEnd, JOURNAL_CAPACITY, () -> { });
    }

    static BearCaptureRecorder open(Path logsRoot, Instant eventEnd, int journalCapacity,
            Runnable beforeEachWrite) throws IOException {
        Path event = Files.createDirectories(
                logsRoot.resolve("bear-capture").resolve("event-" + SESSION_NAME.format(eventEnd)));
        return new BearCaptureRecorder(claimRunDirectory(event), journalCapacity, beforeEachWrite);
    }

    /** A retry or restart in the same event records beside earlier runs, never over them. */
    private static Path claimRunDirectory(Path event) throws IOException {
        for (int run = 1; run < 10_000; run++) {
            Path candidate = event.resolve(String.format("run-%03d", run));
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException taken) {
                // Earlier run in this event; try the next number.
            }
        }
        throw new IOException("No free Bear capture run directory in " + event);
    }

    Path directory() {
        return directory;
    }

    String failureReason() {
        if (droppedLines.get() > 0) failure.compareAndSet(null, "journal-overflow");
        return failure.get();
    }

    void transition(long frame, String diagnostic) {
        String escaped = diagnostic.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        if (!journal.offer("{\"event\":\"transition\",\"latestSampledFrame\":" + frame
                + ",\"at\":\"" + Instant.now() + "\",\"diagnostic\":\"" + escaped + "\"}")) {
            droppedLines.incrementAndGet();
        }
    }

    /** Opens the file for the next recorder renewal, or {@code null} when it cannot be created. */
    OutputStream nextSegment() {
        Path segment = directory.resolve(String.format("segment-%03d.h264", segments.incrementAndGet()));
        try {
            return new BufferedOutputStream(Files.newOutputStream(segment), 1 << 16);
        } catch (IOException failure) {
            this.failure.compareAndSet(null, "segment-open-failed");
            LOG.warn("Bear capture segment {} could not be created: {}", segment, failure.getMessage());
            return null;
        }
    }

    void observed(
            long frame,
            BearRealtimeFrameSource.FrameOrigin origin,
            String image,
            Instant capturedAt,
            Duration transportAge,
            BearNavigationPolicy.Screen screen,
            long classifyNanos,
            Map<String, Long> templateNanos) {
        StringBuilder line = new StringBuilder(200).append("{\"frame\":").append(frame);
        if (origin != null) {
            boolean video = origin.kind() == BearRealtimeFrameSource.FrameOrigin.Kind.VIDEO;
            line.append(",\"source\":\"").append(video ? "video" : "screencap").append('"')
                    .append(",\"segment\":").append(origin.segment())
                    .append(video ? ",\"decodedFrame\":" : ",\"afterDecodedFrame\":").append(origin.decodedFrame());
        }
        if (image != null) {
            line.append(",\"image\":\"").append(image).append('"');
        }
        line.append(",\"capturedAt\":\"").append(capturedAt).append('"')
                .append(",\"transportAgeMs\":").append(transportAge.toMillis())
                .append(",\"screen\":\"").append(screen.name()).append('"')
                .append(",\"classifyMs\":").append(TimeUnit.NANOSECONDS.toMillis(classifyNanos))
                .append(",\"templatesMs\":{");
        boolean first = true;
        for (Map.Entry<String, Long> template : new TreeMap<>(templateNanos).entrySet()) {
            if (!first) line.append(',');
            first = false;
            line.append('"').append(template.getKey()).append("\":")
                    .append(TimeUnit.NANOSECONDS.toMillis(template.getValue()));
        }
        line.append("}}");
        if (!journal.offer(line.toString())) {
            droppedLines.incrementAndGet();
        }
    }

    /**
     * Saves a static-screen screenshot, which the video does not contain, and returns its file name.
     * An unchanged screen references the image already saved instead of writing it again.
     */
    String staticScreenshot(RawImageData screen) {
        CRC32 checksum = new CRC32();
        checksum.update(screen.getFrameBytes());
        long content = checksum.getValue();
        if (lastScreenshot != null && content == lastScreenshotChecksum) {
            return lastScreenshot;
        }
        String name = String.format("screencap-%03d.png", screenshots.incrementAndGet());
        try {
            if (!ImageIO.write(ImageConverter.toBufferedImage(screen), "png", directory.resolve(name).toFile())) {
                throw new IOException("no PNG writer");
            }
        } catch (IOException | RuntimeException failure) {
            this.failure.compareAndSet(null, "screenshot-write-failed");
            LOG.warn("Bear capture screenshot {} could not be saved: {}", name, failure.getMessage());
            return null;
        }
        lastScreenshot = name;
        lastScreenshotChecksum = content;
        return name;
    }

    /** Journals once per segment that its evidence recording stopped; observation continues. */
    void recordingFailed(int segment, String reason) {
        failure.compareAndSet(null, "segment-write-failed");
        if (segment <= lastFailedSegment) return;
        lastFailedSegment = segment;
        LOG.warn("Bear capture segment {} stopped recording: {}", segment, reason);
        String line = "{\"event\":\"recordingFailed\",\"segment\":" + segment
                + ",\"reason\":\"" + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        if (!journal.offer(line)) {
            droppedLines.incrementAndGet();
        }
    }

    long droppedJournalLines() {
        return droppedLines.get();
    }

    @Override
    public void close() {
        try {
            while (!journal.offer(END_OF_JOURNAL, 100, TimeUnit.MILLISECONDS)) {
                if (!journalWriter.isAlive()) break;
                journal.poll();
                droppedLines.incrementAndGet();
            }
            journalWriter.join(2_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (droppedLines.get() > 0) {
            LOG.warn("Bear capture journal dropped {} lines in {}", droppedLines.get(), directory);
        }
    }

    private void drain(BufferedWriter writer) {
        try (writer) {
            while (true) {
                String line = journal.take();
                if (END_OF_JOURNAL.equals(line)) return;
                beforeEachWrite.run();
                writer.write(line);
                writer.newLine();
                if (journal.isEmpty()) writer.flush();
            }
        } catch (IOException | RuntimeException failure) {
            this.failure.compareAndSet(null, "journal-write-failed");
            LOG.warn("Bear capture journal stopped: {}", failure.getMessage());
        } catch (InterruptedException interrupted) {
            failure.compareAndSet(null, "journal-interrupted");
            Thread.currentThread().interrupt();
        }
    }
}
