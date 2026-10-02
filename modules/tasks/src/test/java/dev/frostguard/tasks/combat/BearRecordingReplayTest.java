package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;

class BearRecordingReplayTest {

    private static final String RECORDINGS = "/bear/recordings";

    @Test
    void replaysEveryRecordedFrameThroughTheProductionClassifier() throws Exception {
        List<BearFrameClassifier.Classification> replayed =
                BearRecordingReplay.classify(new ByteArrayInputStream(clipWithoutGameUi(6)), 1);

        assertEquals(6, replayed.size(), "every decoded frame is classified in order");
        assertTrue(replayed.stream().allMatch(c -> c.screen() == BearNavigationPolicy.Screen.UNKNOWN),
                "a frame without game UI must not match any Bear screen");
        assertTrue(replayed.stream().allMatch(c -> !c.templateNanos().isEmpty()),
                "the real template matcher ran for every frame");
    }

    /**
     * Real-frame gate. Each {@code <name>.h264} under {@code bear/recordings} needs a sibling
     * {@code <name>.labels} with one expected {@code Screen} per decoded frame. No recording has
     * been captured yet, so this reports as skipped rather than passing.
     */
    @Test
    void labelledRealRecordingsClassifyAsLabelled() throws Exception {
        URL root = getClass().getResource(RECORDINGS);
        assumeTrue(root != null, "no real Bear recordings captured yet");
        List<Path> segments;
        try (Stream<Path> files = Files.list(Path.of(root.toURI()))) {
            segments = files.filter(file -> file.toString().endsWith(".h264")).sorted().toList();
        }
        assumeTrue(!segments.isEmpty(), "no real Bear recordings captured yet");
        for (Path segment : segments) {
            List<String> expected = Files.readAllLines(
                    Path.of(segment.toString().replaceFirst("\\.h264$", ".labels")));
            List<BearFrameClassifier.Classification> replayed;
            try (InputStream input = Files.newInputStream(segment)) {
                replayed = BearRecordingReplay.classify(input, 1);
            }
            assertEquals(expected, replayed.stream().map(c -> c.screen().name()).toList(),
                    segment.getFileName().toString());
        }
    }

    private static byte[] clipWithoutGameUi(int frames) throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(encoded, 720, 1280);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            recorder.setFormat("h264");
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFrameRate(10);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.start();
            for (int index = 0; index < frames; index++) {
                BufferedImage image = new BufferedImage(720, 1280, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(new Color(30, 60 + index * 10, 120));
                graphics.fillRect(0, 0, 720, 1280);
                graphics.dispose();
                recorder.record(converter.convert(image));
            }
            recorder.stop();
        }
        return encoded.toByteArray();
    }
}
