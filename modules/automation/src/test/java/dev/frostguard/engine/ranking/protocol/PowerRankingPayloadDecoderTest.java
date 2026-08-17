package dev.frostguard.engine.ranking.protocol;

import dev.frostguard.api.domain.AllianceRankingEntryData;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerRankingPayloadDecoderTest {

    private final PowerRankingPayloadDecoder decoder = new PowerRankingPayloadDecoder();

    @Test
    void decodesSortsAndDeduplicatesCaptureDerivedMemberLayout() {
        byte[] firstBatch = packedResponse(
                member("Member 002", 2002, 19_044_772),
                member("Member 001", 2001, 19_585_025));
        byte[] secondBatch = packedResponse(
                member("Member 003", 2003, 18_869_306),
                member("Member 002 renamed", 2002, 19_044_772));

        List<AllianceRankingEntryData> result = decoder.decode(List.of(firstBatch, secondBatch));

        assertEquals(List.of(
                new AllianceRankingEntryData(1, 2001, "Member 001", 19_585_025),
                new AllianceRankingEntryData(2, 2002, "Member 002 renamed", 19_044_772),
                new AllianceRankingEntryData(3, 2003, "Member 003", 18_869_306)
        ), result);
    }

    @Test
    void ignoresUnrelatedValidSprotoMessages() {
        assertTrue(decoder.decode(List.of(pack(new byte[]{1, 0, 2, 0}))).isEmpty());
    }

    private byte[] packedResponse(byte[]... members) {
        ByteArrayOutputStream memberArray = new ByteArrayOutputStream();
        for (byte[] member : members) {
            memberArray.writeBytes(uint32(member.length));
            memberArray.writeBytes(member);
        }

        byte[] rpcHeader = {2, 0, 1, 0, 0x66, (byte) 0xa0};
        byte[] response = struct(List.of(memberArray.toByteArray()), List.of(2));
        ByteArrayOutputStream unpacked = new ByteArrayOutputStream();
        unpacked.writeBytes(rpcHeader);
        unpacked.writeBytes(response);
        return pack(unpacked.toByteArray());
    }

    private byte[] member(String name, long playerId, long power) {
        return struct(List.of(
                name.getBytes(StandardCharsets.UTF_8),
                uint32(playerId),
                uint32(1_700_000_000L + playerId),
                uint32(power)
        ), List.of());
    }

    private byte[] struct(List<byte[]> dataFields, List<Integer> inlineValues) {
        int fieldCount = dataFields.size() + inlineValues.size();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(uint16(fieldCount));
        for (int ignored = 0; ignored < dataFields.size(); ignored++) {
            output.writeBytes(uint16(0));
        }
        for (int value : inlineValues) {
            output.writeBytes(uint16((value + 1) * 2));
        }
        for (byte[] field : dataFields) {
            output.writeBytes(uint32(field.length));
            output.writeBytes(field);
        }
        return output.toByteArray();
    }

    private byte[] pack(byte[] unpacked) {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        for (int offset = 0; offset < unpacked.length; offset += 8) {
            int remaining = Math.min(8, unpacked.length - offset);
            int tag = 0;
            for (int index = 0; index < remaining; index++) {
                if (unpacked[offset + index] != 0) {
                    tag |= 1 << index;
                }
            }
            if (tag == 0xff) {
                packed.write(0xff);
                packed.write(0);
                for (int index = 0; index < 8; index++) {
                    packed.write(index < remaining ? unpacked[offset + index] : 0);
                }
                continue;
            }
            packed.write(tag);
            for (int index = 0; index < remaining; index++) {
                if (unpacked[offset + index] != 0) {
                    packed.write(unpacked[offset + index]);
                }
            }
        }
        return packed.toByteArray();
    }

    private byte[] uint16(int value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
    }

    private byte[] uint32(long value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array();
    }
}
