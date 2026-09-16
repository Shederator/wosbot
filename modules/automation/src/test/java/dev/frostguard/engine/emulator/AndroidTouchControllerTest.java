package dev.frostguard.engine.emulator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AndroidTouchControllerTest {
    @Test void primesStationaryContactOnceAndRetainsItForSteering() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var input = new AndroidTouchController(bytes)) {
            assertFalse(input.isSteering());
            input.primeSteering(360, 600);
            assertTrue(input.isSteering());
            assertTrue(input.canContinueSteering(100));
            assertFalse(input.canContinueSteering(400));
            int count = bytes.size();
            input.primeSteering(360, 600);
            assertEquals(count, bytes.size(), "Already-held contact needs no new startup gesture");
            byte[] packets = bytes.toByteArray();
            for (int offset = 0; offset < packets.length; offset += 32) {
                assertEquals(360, ByteBuffer.wrap(packets, offset, 32).slice().getInt(10));
            }
            assertEquals(100, input.steer(360, 600, 100, 1));
            input.endSteering();
            assertFalse(input.isSteering());
        }
        assertEquals(1, java.util.stream.IntStream.range(0, bytes.size() / 32)
                .filter(i -> bytes.toByteArray()[i * 32 + 1] == 0).count());
    }
    @Test void reanchorsAClippedPointerBeforeRequestingMoreMovementInThatDirection() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var input = new AndroidTouchController(bytes)) {
            assertEquals(30, input.steer(660, 600, 30, 1));
            assertEquals(80, input.steer(500, 600, 80, 1),
                    "Finger at the wall is not evidence that the observed hook is at the wall");
        }
        byte[] packets = bytes.toByteArray();
        int downs = 0, ups = 0;
        for (int i = 1; i < packets.length; i += 32) {
            if (packets[i] == 0) downs++;
            if (packets[i] == 1) ups++;
        }
        assertEquals(2, downs); assertEquals(2, ups);
        assertEquals(580, ByteBuffer.wrap(packets, packets.length - 32, 32).slice().getInt(10));
    }

    @Test void letsTheGameObserveContactBeforeTheFirstMove() throws Exception {
        long[] downAndMove = new long[2];
        var bytes = new ByteArrayOutputStream() {
            @Override public void write(byte[] packet) throws IOException {
                super.write(packet);
                if (packet[1] == 0) downAndMove[0] = System.nanoTime();
                if (packet[1] == 2 && downAndMove[1] == 0) downAndMove[1] = System.nanoTime();
            }
        };
        try (var input = new AndroidTouchController(bytes)) { input.steer(360, 600, 100, 80); }
        assertTrue(downAndMove[1] - downAndMove[0] >= 75_000_000L,
                "First MOVE must not replace DOWN before a slow game frame observes contact");
    }

    @Test void encodesThePinnedProtocolAndRefusesOutOfBoundsInput() {
        var packet = ByteBuffer.wrap(AndroidTouchController.packet(0, 360, 600));
        assertEquals(32, packet.remaining());
        assertEquals(2, packet.get()); assertEquals(0, packet.get()); assertEquals(0, packet.getLong());
        assertEquals(360, packet.getInt()); assertEquals(600, packet.getInt());
        assertEquals(720, packet.getShort()); assertEquals(1280, packet.getShort());
        assertEquals(65535, Short.toUnsignedInt(packet.getShort()));
        assertEquals(0, packet.getInt()); assertEquals(0, packet.getInt());
        assertEquals(0, ByteBuffer.wrap(AndroidTouchController.packet(1, 360, 600)).getShort(22));
        assertThrows(IllegalArgumentException.class, () -> AndroidTouchController.packet(0, 720, 600));
        assertThrows(IllegalArgumentException.class, () -> AndroidTouchController.packet(3, 360, 600));
    }

    @Test void repeatedSteeringKeepsOnePointerThenReleasesExactlyOnce() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var input = new AndroidTouchController(bytes);
        assertEquals(100, input.steer(360, 600, 100, 1));
        assertEquals(30, input.steer(420, 700, 30, 1));
        input.close(); input.close();
        byte[] packets = bytes.toByteArray();
        assertEquals(0, packets[1]); assertEquals(1, packets[packets.length - 31]);
        long downs = 0, ups = 0;
        for (int i = 1; i < packets.length; i += 32) { if (packets[i] == 0) downs++; if (packets[i] == 1) ups++; }
        assertEquals(1, downs); assertEquals(1, ups);
        var release = ByteBuffer.wrap(packets, packets.length - 32, 32).slice();
        assertEquals(490, release.getInt(10)); assertEquals(600, release.getInt(14));
        assertThrows(IOException.class, () -> input.steer(360, 600, 10, 1));
    }

    @Test void cancellationBeforeDownSendsNothing() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var input = new AndroidTouchController(bytes)) {
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedException.class, () -> input.steer(360, 600, 100, 80)); }
            finally { Thread.interrupted(); }
        }
        assertEquals(0, bytes.size());
    }

    @Test void cancellationAfterDownReleasesWithoutSendingAMove() throws Exception {
        var bytes = new ByteArrayOutputStream() {
            @Override public void write(byte[] packet) throws IOException {
                super.write(packet);
                if (packet[1] == 0) Thread.currentThread().interrupt();
            }
        };
        try (var input = new AndroidTouchController(bytes)) {
            try { assertThrows(InterruptedException.class, () -> input.steer(360, 600, 100, 80)); }
            finally { Thread.interrupted(); }
        }
        assertEquals(64, bytes.size()); assertEquals(1, bytes.toByteArray()[33]);
    }

    @Test void failedDownIsNotRetriedAndStillAttemptsRelease() throws Exception {
        var bytes = new ByteArrayOutputStream() {
            @Override public void write(byte[] packet) throws IOException {
                if (packet[1] == 0) throw new IOException("Unknown DOWN delivery");
                super.write(packet);
            }
        };
        try (var input = new AndroidTouchController(bytes)) {
            assertThrows(IOException.class, () -> input.steer(360, 600, 100, 80));
        }
        assertEquals(32, bytes.size()); assertEquals(1, bytes.toByteArray()[1]);
    }
}
