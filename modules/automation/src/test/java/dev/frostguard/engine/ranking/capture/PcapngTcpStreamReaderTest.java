package dev.frostguard.engine.ranking.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PcapngTcpStreamReaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reassemblesInboundSegmentsAndIgnoresPktmonDuplicates() throws IOException {
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        capture.writeBytes(sectionHeader());
        capture.writeBytes(interfaceDescription());
        capture.writeBytes(interfaceDescription());
        byte[] first = {0, 5, 1, 2};
        byte[] second = {3, 4, 5};
        capture.writeBytes(enhancedPacket(0, tcpPacket(30101, 50000, 100, first)));
        capture.writeBytes(enhancedPacket(1, tcpPacket(30101, 50000, 100, first)));
        capture.writeBytes(enhancedPacket(0, tcpPacket(30101, 50000, 104, second)));
        capture.writeBytes(enhancedPacket(0, tcpPacket(13321, 50000, 200, new byte[]{9})));
        Path file = temporaryDirectory.resolve("capture.pcapng");
        Files.write(file, capture.toByteArray());

        List<byte[]> streams = new PcapngTcpStreamReader().readInboundStreams(file, 30101);

        assertEquals(1, streams.size());
        assertArrayEquals(new byte[]{0, 5, 1, 2, 3, 4, 5}, streams.getFirst());
        assertEquals(0, new PcapngTcpStreamReader().readOutboundStreams(file, 30101).size());
    }

    @Test
    void readsClassicTcpdumpPcap() throws IOException {
        byte[] first = {0, 5, 1, 2};
        byte[] second = {3, 4, 5};
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        capture.writeBytes(classicHeader());
        capture.writeBytes(classicPacket(tcpPacket(30101, 50000, 100, first)));
        capture.writeBytes(classicPacket(tcpPacket(30101, 50000, 104, second)));
        Path file = temporaryDirectory.resolve("capture.pcap");
        Files.write(file, capture.toByteArray());

        List<byte[]> streams = new PcapngTcpStreamReader().readInboundStreams(file, 30101);

        assertEquals(1, streams.size());
        assertArrayEquals(new byte[]{0, 5, 1, 2, 3, 4, 5}, streams.getFirst());
    }

    @Test
    void keepsCompletePacketsBeforeTruncatedFinalRecord() throws IOException {
        byte[] payload = {0, 3, 1, 2, 3};
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        capture.writeBytes(classicHeader());
        capture.writeBytes(classicPacket(tcpPacket(30101, 50000, 100, payload)));
        capture.writeBytes(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 20, 0, 0, 0});
        Path file = temporaryDirectory.resolve("truncated.pcap");
        Files.write(file, capture.toByteArray());

        List<byte[]> streams = new PcapngTcpStreamReader().readInboundStreams(file, 30101);

        assertEquals(1, streams.size());
        assertArrayEquals(payload, streams.getFirst());
    }

    private byte[] classicHeader() {
        ByteBuffer header = littleEndian(24);
        header.putInt(0xa1b2c3d4).putShort((short) 2).putShort((short) 4);
        header.putInt(0).putInt(0).putInt(65_535).putInt(1);
        return header.array();
    }

    private byte[] classicPacket(byte[] packet) {
        ByteBuffer record = littleEndian(16 + packet.length);
        record.putInt(0).putInt(0).putInt(packet.length).putInt(packet.length).put(packet);
        return record.array();
    }

    private byte[] sectionHeader() {
        ByteBuffer block = littleEndian(28);
        block.putInt(0x0a0d0d0a).putInt(28).putInt(0x1a2b3c4d);
        block.putShort((short) 1).putShort((short) 0).putLong(-1).putInt(28);
        return block.array();
    }

    private byte[] interfaceDescription() {
        ByteBuffer block = littleEndian(20);
        block.putInt(1).putInt(20).putShort((short) 1).putShort((short) 0).putInt(65_535).putInt(20);
        return block.array();
    }

    private byte[] enhancedPacket(int interfaceId, byte[] packet) {
        int paddedLength = (packet.length + 3) & ~3;
        int blockLength = 32 + paddedLength;
        ByteBuffer block = littleEndian(blockLength);
        block.putInt(6).putInt(blockLength).putInt(interfaceId).putInt(0).putInt(0);
        block.putInt(packet.length).putInt(packet.length).put(packet);
        block.position(blockLength - 4).putInt(blockLength);
        return block.array();
    }

    private byte[] tcpPacket(int sourcePort, int destinationPort, long sequence, byte[] payload) {
        ByteBuffer packet = ByteBuffer.allocate(14 + 20 + 20 + payload.length).order(ByteOrder.BIG_ENDIAN);
        packet.position(12).putShort((short) 0x0800);
        packet.put((byte) 0x45).put((byte) 0).putShort((short) (40 + payload.length));
        packet.putShort((short) 0).putShort((short) 0).put((byte) 64).put((byte) 6).putShort((short) 0);
        packet.putInt(0x23000001).putInt(0x0a000002);
        packet.putShort((short) sourcePort).putShort((short) destinationPort).putInt((int) sequence);
        packet.putInt(0).put((byte) 0x50).put((byte) 0x18).putShort((short) 65_535);
        packet.putShort((short) 0).putShort((short) 0).put(payload);
        return packet.array();
    }

    private ByteBuffer littleEndian(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
