package dev.frostguard.engine.ranking.capture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reads and reassembles IPv4 TCP payloads from little-endian PCAP or PCAPNG captures. */
public final class PcapngTcpStreamReader {

    private static final int SECTION_HEADER_BLOCK = 0x0a0d0d0a;
    private static final int INTERFACE_DESCRIPTION_BLOCK = 1;
    private static final int ENHANCED_PACKET_BLOCK = 6;
    private static final int BYTE_ORDER_MAGIC = 0x1a2b3c4d;
    private static final int LINKTYPE_ETHERNET = 1;

    public List<byte[]> readInboundStreams(Path capture, int sourcePort) throws IOException {
        return readStreams(capture, sourcePort, Direction.INBOUND);
    }

    public List<byte[]> readOutboundStreams(Path capture, int destinationPort) throws IOException {
        return readStreams(capture, destinationPort, Direction.OUTBOUND);
    }

    private List<byte[]> readStreams(Path capture, int gamePort, Direction direction) throws IOException {
        if (capture == null) {
            throw new IllegalArgumentException("capture must not be null");
        }
        if (gamePort < 1 || gamePort > 65535) {
            throw new IllegalArgumentException("gamePort must be between 1 and 65535");
        }

        byte[] bytes = Files.readAllBytes(capture);
        if (bytes.length >= 24 && littleEndianInt(bytes, 0) == 0xa1b2c3d4) {
            return readClassicPcap(bytes, gamePort, direction);
        }
        Map<Integer, Integer> linkTypes = new HashMap<>();
        Map<ConnectionKey, TreeMap<Long, byte[]>> connections = new LinkedHashMap<>();
        int nextInterfaceId = 0;
        int offset = 0;
        boolean sectionSeen = false;
        while (offset < bytes.length) {
            require(bytes, offset, 12, "PCAPNG block header");
            int blockType = littleEndianInt(bytes, offset);
            int blockLength = littleEndianInt(bytes, offset + 4);
            if (blockLength < 12 || (blockLength & 3) != 0) {
                throw malformed("invalid PCAPNG block length " + blockLength, offset);
            }
            require(bytes, offset, blockLength, "PCAPNG block");
            if (littleEndianInt(bytes, offset + blockLength - 4) != blockLength) {
                throw malformed("mismatched trailing PCAPNG block length", offset);
            }

            if (blockType == SECTION_HEADER_BLOCK) {
                if (littleEndianInt(bytes, offset + 8) != BYTE_ORDER_MAGIC) {
                    throw malformed("only little-endian PCAPNG sections are supported", offset);
                }
                sectionSeen = true;
                linkTypes.clear();
                nextInterfaceId = 0;
            } else if (!sectionSeen) {
                throw malformed("PCAPNG data appeared before a section header", offset);
            } else if (blockType == INTERFACE_DESCRIPTION_BLOCK) {
                require(bytes, offset + 8, blockLength - 12, 8, "interface description");
                linkTypes.put(nextInterfaceId++, littleEndianUnsignedShort(bytes, offset + 8));
            } else if (blockType == ENHANCED_PACKET_BLOCK) {
                readPacket(bytes, offset, blockLength, linkTypes, gamePort, direction, connections);
            }
            offset += blockLength;
        }

        List<byte[]> streams = new ArrayList<>();
        for (TreeMap<Long, byte[]> segments : connections.values()) {
            streams.addAll(reassemble(segments));
        }
        return List.copyOf(streams);
    }

    private List<byte[]> readClassicPcap(byte[] bytes, int gamePort, Direction direction) {
        int linkType = littleEndianInt(bytes, 20);
        if (linkType != LINKTYPE_ETHERNET) {
            throw malformed("only Ethernet PCAP captures are supported", 20);
        }
        Map<ConnectionKey, TreeMap<Long, byte[]>> connections = new LinkedHashMap<>();
        int offset = 24;
        while (offset < bytes.length) {
            if (bytes.length - offset < 16) break;
            long capturedLength = Integer.toUnsignedLong(littleEndianInt(bytes, offset + 8));
            if (capturedLength > Integer.MAX_VALUE) {
                throw malformed("invalid PCAP packet length", offset);
            }
            int packetOffset = offset + 16;
            if (capturedLength > bytes.length - packetOffset) break;
            addPacket(bytes, packetOffset, (int) capturedLength, gamePort, direction, connections);
            offset = packetOffset + (int) capturedLength;
        }
        List<byte[]> streams = new ArrayList<>();
        for (TreeMap<Long, byte[]> segments : connections.values()) {
            streams.addAll(reassemble(segments));
        }
        return List.copyOf(streams);
    }

    private void readPacket(byte[] bytes, int blockOffset, int blockLength,
                            Map<Integer, Integer> linkTypes, int gamePort, Direction direction,
                            Map<ConnectionKey, TreeMap<Long, byte[]>> connections) {
        int body = blockOffset + 8;
        require(bytes, body, blockLength - 12, 20, "enhanced packet header");
        int interfaceId = littleEndianInt(bytes, body);
        if (linkTypes.getOrDefault(interfaceId, -1) != LINKTYPE_ETHERNET) {
            return;
        }
        long capturedLength = Integer.toUnsignedLong(littleEndianInt(bytes, body + 12));
        if (capturedLength > Integer.MAX_VALUE || capturedLength > blockLength - 32L) {
            throw malformed("invalid captured packet length", blockOffset);
        }
        int packetOffset = body + 20;
        int packetLength = (int) capturedLength;
        addPacket(bytes, packetOffset, packetLength, gamePort, direction, connections);
    }

    private void addPacket(byte[] bytes, int packetOffset, int packetLength, int gamePort,
                           Direction direction,
                           Map<ConnectionKey, TreeMap<Long, byte[]>> connections) {
        TcpSegment segment = parseEthernetIpv4Tcp(bytes, packetOffset, packetLength, gamePort, direction);
        if (segment == null || segment.payload().length == 0) {
            return;
        }

        TreeMap<Long, byte[]> bySequence = connections.computeIfAbsent(
                segment.connection(), ignored -> new TreeMap<>());
        byte[] previous = bySequence.putIfAbsent(segment.sequence(), segment.payload());
        if (previous != null && !Arrays.equals(previous, segment.payload())) {
            if (segment.payload().length > previous.length
                    && startsWith(segment.payload(), previous)) {
                bySequence.put(segment.sequence(), segment.payload());
            } else if (!startsWith(previous, segment.payload())) {
                throw malformed("conflicting TCP retransmission at sequence " + segment.sequence(), packetOffset);
            }
        }
    }

    private TcpSegment parseEthernetIpv4Tcp(byte[] bytes, int offset, int length,
                                            int gamePort, Direction direction) {
        if (length < 14 || bigEndianUnsignedShort(bytes, offset + 12) != 0x0800) {
            return null;
        }
        int ipOffset = offset + 14;
        if (length - 14 < 20 || (bytes[ipOffset] >>> 4) != 4 || (bytes[ipOffset + 9] & 0xff) != 6) {
            return null;
        }
        int ipHeaderLength = (bytes[ipOffset] & 0x0f) * 4;
        int ipTotalLength = bigEndianUnsignedShort(bytes, ipOffset + 2);
        if (ipHeaderLength < 20 || ipTotalLength < ipHeaderLength + 20 || ipTotalLength > length - 14) {
            return null;
        }

        int tcpOffset = ipOffset + ipHeaderLength;
        int tcpHeaderLength = ((bytes[tcpOffset + 12] & 0xff) >>> 4) * 4;
        if (tcpHeaderLength < 20 || ipTotalLength < ipHeaderLength + tcpHeaderLength) {
            return null;
        }
        int actualSourcePort = bigEndianUnsignedShort(bytes, tcpOffset);
        int destinationPort = bigEndianUnsignedShort(bytes, tcpOffset + 2);
        if ((direction == Direction.INBOUND && actualSourcePort != gamePort)
                || (direction == Direction.OUTBOUND && destinationPort != gamePort)) {
            return null;
        }
        long sequence = bigEndianUnsignedInt(bytes, tcpOffset + 4);
        int payloadOffset = tcpOffset + tcpHeaderLength;
        int payloadLength = ipTotalLength - ipHeaderLength - tcpHeaderLength;
        byte[] payload = Arrays.copyOfRange(bytes, payloadOffset, payloadOffset + payloadLength);
        ConnectionKey connection = new ConnectionKey(
                ipv4(bytes, ipOffset + 12), actualSourcePort,
                ipv4(bytes, ipOffset + 16), destinationPort);
        return new TcpSegment(connection, sequence, payload);
    }

    private List<byte[]> reassemble(TreeMap<Long, byte[]> segments) {
        List<byte[]> streams = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        long expected = -1;
        for (Map.Entry<Long, byte[]> entry : segments.entrySet()) {
            long sequence = entry.getKey();
            byte[] payload = entry.getValue();
            if (expected < 0 || sequence > expected) {
                if (current.size() > 0) {
                    streams.add(current.toByteArray());
                    current.reset();
                }
                expected = sequence;
            }

            long overlap = expected - sequence;
            if (overlap >= payload.length) {
                continue;
            }
            int payloadOffset = overlap > 0 ? (int) overlap : 0;
            current.write(payload, payloadOffset, payload.length - payloadOffset);
            expected = sequence + payload.length;
        }
        if (current.size() > 0) {
            streams.add(current.toByteArray());
        }
        return streams;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private String ipv4(byte[] source, int offset) {
        return (source[offset] & 0xff) + "." + (source[offset + 1] & 0xff) + "."
                + (source[offset + 2] & 0xff) + "." + (source[offset + 3] & 0xff);
    }

    private int littleEndianInt(byte[] source, int offset) {
        return ByteBuffer.wrap(source, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private int littleEndianUnsignedShort(byte[] source, int offset) {
        return (source[offset] & 0xff) | ((source[offset + 1] & 0xff) << 8);
    }

    private int bigEndianUnsignedShort(byte[] source, int offset) {
        return ((source[offset] & 0xff) << 8) | (source[offset + 1] & 0xff);
    }

    private long bigEndianUnsignedInt(byte[] source, int offset) {
        return ((source[offset] & 0xffL) << 24)
                | ((source[offset + 1] & 0xffL) << 16)
                | ((source[offset + 2] & 0xffL) << 8)
                | (source[offset + 3] & 0xffL);
    }

    private void require(byte[] source, int offset, int available, int required, String part) {
        if (offset < 0 || available < required || offset > source.length - required) {
            throw malformed("truncated " + part, offset);
        }
    }

    private void require(byte[] source, int offset, int required, String part) {
        require(source, offset, source.length - offset, required, part);
    }

    private IllegalArgumentException malformed(String reason, int offset) {
        return new IllegalArgumentException(reason + " at capture offset " + offset);
    }

    private record ConnectionKey(String sourceAddress, int sourcePort,
                                 String destinationAddress, int destinationPort) {
    }

    private record TcpSegment(ConnectionKey connection, long sequence, byte[] payload) {
    }

    private enum Direction {
        INBOUND,
        OUTBOUND
    }
}
