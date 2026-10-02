package dev.frostguard.vision.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;

class RecordingInputStreamTest {

    @Test
    void recordsTheExactBytesTheDecoderConsumes() throws Exception {
        byte[] source = synthesizedH264(12);
        ByteArrayOutputStream recording = new ByteArrayOutputStream();
        int liveFrames;
        try (var recorded = new RecordingInputStream(new ByteArrayInputStream(source), recording, ignored -> { });
             var decoder = new H264FrameDecoder(recorded)) {
            decoder.start();
            liveFrames = countFrames(decoder);
        }

        assertTrue(liveFrames > 0);
        assertArrayEquals(source, recording.toByteArray());
        try (var replay = new H264FrameDecoder(new ByteArrayInputStream(recording.toByteArray()))) {
            replay.start();
            assertEquals(liveFrames, countFrames(replay), "the recording replays the same frames");
        }
    }

    @Test
    void aFailingRecordingNeverBreaksTheLiveStream() throws Exception {
        byte[] source = {1, 2, 3, 4, 5, 6, 7, 8};
        AtomicReference<IOException> reported = new AtomicReference<>();
        OutputStream full = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }
        };
        byte[] live = new byte[source.length];
        try (var recorded = new RecordingInputStream(new ByteArrayInputStream(source), full, reported::set)) {
            int read = recorded.read(live, 0, 4);
            read += recorded.read(live, 4, 4);
            assertEquals(source.length, read);
        }

        assertArrayEquals(source, live);
        assertNotNull(reported.get(), "the recording failure is reported once");
    }

    private static int countFrames(H264FrameDecoder decoder) throws Exception {
        int frames = 0;
        while (decoder.nextFrame() != null) {
            frames++;
        }
        return frames;
    }

    /** A raw Annex-B H.264 clip at the recorder's 720x1280 size, so no account footage is needed. */
    private static byte[] synthesizedH264(int frames) throws Exception {
        H264FrameDecoder.prepareRuntime();
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
                graphics.setColor(new Color(20 * index % 255, 90, 160));
                graphics.fillRect(0, 0, 720, 1280);
                graphics.setColor(Color.WHITE);
                graphics.fillRect(40 + index * 20, 600, 120, 120);
                graphics.dispose();
                recorder.record(converter.convert(image));
            }
            recorder.stop();
        }
        return encoded.toByteArray();
    }
}
