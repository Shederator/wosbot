package dev.frostguard.vision.ocr;

import java.awt.image.BufferedImage;
import dev.frostguard.api.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TesseractOcrSessionTest {
    @Test
    void explicitlyPreparesTheSameOwnedEngineBeforeTheFirstImageAndRejectsClosedPreparation() {
        int[] prepared = {0};
        var engine = new TesseractOcrSession.Engine() {
            public void prepare() { prepared[0]++; }
            public String read(BufferedImage image) { return ""; }
            public void close() { }
        };
        var session = new TesseractOcrSession(OcrSettingsData.forSingleWord(), engine);
        session.prepare();
        assertEquals(1, prepared[0]);
        session.close();
        assertThrows(IllegalStateException.class, session::prepare);
    }
    @Test
    void isolatesOneRegionWithoutChangingTheNextReadOrReplacingTheEngine() throws Exception {
        var seen = new java.util.ArrayList<Integer>();
        var engine = new TesseractOcrSession.Engine() {
            public String read(BufferedImage image) { seen.add(image.getRGB(10, 10) & 0xffffff); return "19m"; }
            public void close() { }
        };
        byte[] pixels = new byte[20 * 20 * 4];
        java.util.Arrays.fill(pixels, (byte) 255);
        var frame = RawImageData.capture(pixels, 20, 20, 32);
        try (var session = new TesseractOcrSession(OcrSettingsData.forSingleWord(), engine)) {
            var region = AreaData.of(0, 0, 10, 10);
            assertEquals("19m", session.recognizeForeground(frame, region, java.awt.Color.WHITE));
            assertEquals("19m", session.recognize(frame, region));
            assertEquals(java.util.List.of(0x000000, 0xffffff), seen);
        }
    }

    @Test
    void reusesOneEngineAndClosesExactlyOnce() throws Exception {
        int[] calls = new int[2];
        var engine = new TesseractOcrSession.Engine() {
            public String read(BufferedImage image) { calls[0]++; return "172M\n550M"; }
            public void close() { calls[1]++; }
        };
        var session = new TesseractOcrSession(OcrSettingsData.forNumberRecognition(), engine);
        var frame = RawImageData.capture(new byte[20 * 20 * 4], 20, 20, 32);
        for (int i = 0; i < 3; i++) assertEquals("172M\n550M", session.recognize(frame, AreaData.of(0, 0, 10, 10)));
        session.close();
        session.close();
        assertArrayEquals(new int[]{3, 1}, calls);
        assertThrows(IllegalStateException.class, () -> session.recognize(frame, AreaData.of(0, 0, 10, 10)));
    }

    @Test
    void rejectsCrossThreadAccess() throws Exception {
        var session = new TesseractOcrSession(OcrSettingsData.forNumberRecognition(), new TesseractOcrSession.Engine() {
            public String read(BufferedImage image) { fail("Cross-thread read reached engine"); return ""; }
            public void close() { }
        });
        var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { session.close(); }
            catch (Throwable error) { result.set(error); }
        });
        thread.start();
        thread.join();
        assertInstanceOf(IllegalStateException.class, result.get());
        session.close();
    }
}
