package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.video.H264FrameDecoder;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wire proof for observe-only capture against a real Android device. It sends no input. Run with
 * {@code FROSTGUARD_LIVE_ADB=<adb path> FROSTGUARD_LIVE_ADB_SERIAL=<serial>}; it records across one
 * bounded recorder renewal and replays every saved segment through the production decoder.
 */
@EnabledIfEnvironmentVariable(named = "FROSTGUARD_LIVE_ADB_SERIAL", matches = ".+")
class BearCaptureLiveDeviceTest {

    @Test
    void recordsDecodableSegmentsAcrossARecorderRenewal(@TempDir Path logs) throws Exception {
        Duration span = Duration.ofSeconds(Long.parseLong(
                System.getenv().getOrDefault("FROSTGUARD_LIVE_CAPTURE_SECONDS", "135")));
        int liveFrames = 0;
        int[] staticSamples = {0};
        Path session;
        try (BearCaptureRecorder recorder = BearCaptureRecorder.open(logs, Instant.now().plus(span));
             BearRealtimeFrameSource source = new BearRealtimeFrameSource(
                     System.getenv("FROSTGUARD_LIVE_ADB"),
                     System.getenv("FROSTGUARD_LIVE_ADB_SERIAL"),
                     () -> false,
                     recorder::nextSegment,
                     () -> {
                         staticSamples[0]++;
                         return screencap();
                     })) {
            session = recorder.directory();
            Instant end = Instant.now().plus(span);
            while (Instant.now().isBefore(end)) {
                source.next();
                liveFrames++;
            }
        }

        List<Path> segments;
        try (Stream<Path> files = Files.list(session)) {
            segments = files.filter(file -> file.getFileName().toString().endsWith(".h264")).sorted().toList();
        }
        assertTrue(liveFrames > 0, "the live stream produced no fresh frame");
        assertTrue(segments.size() >= 2, "expected a segment per recorder renewal: " + segments);
        int replayed = 0;
        for (Path segment : segments) {
            int frames = 0;
            try (InputStream input = Files.newInputStream(segment);
                 H264FrameDecoder decoder = new H264FrameDecoder(input)) {
                decoder.start();
                while (decoder.nextFrame() != null) {
                    frames++;
                }
            }
            System.out.printf("live-capture segment=%s bytes=%d frames=%d%n",
                    segment.getFileName(), Files.size(segment), frames);
            replayed += frames;
        }
        System.out.printf("live-capture framesConsumed=%d staticSamples=%d replayedFrames=%d segments=%d%n",
                liveFrames, staticSamples[0], replayed, segments.size());
        assertTrue(replayed >= liveFrames - staticSamples[0],
                "every streamed frame must be replayable from disk");
        assertTrue(replayed > 0, "the recording must contain decodable frames");
    }

    /** {@code screencap} raw output: a little-endian width, height, format header, then RGBA. */
    static RawImageData screencap() {
        try {
            Process process = new ProcessBuilder(System.getenv("FROSTGUARD_LIVE_ADB"), "-s",
                    System.getenv("FROSTGUARD_LIVE_ADB_SERIAL"), "exec-out", "screencap").start();
            byte[] raw = process.getInputStream().readAllBytes();
            process.waitFor();
            ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int width = header.getInt(0);
            int height = header.getInt(4);
            int offset = raw.length - width * height * 4;
            return RawImageData.capture(Arrays.copyOfRange(raw, offset, raw.length), width, height, 32);
        } catch (Exception failure) {
            throw new IllegalStateException("screencap failed", failure);
        }
    }
}
