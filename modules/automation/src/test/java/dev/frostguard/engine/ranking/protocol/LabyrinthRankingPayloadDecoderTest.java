package dev.frostguard.engine.ranking.protocol;

import dev.frostguard.api.domain.LabyrinthRankingEntryData;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabyrinthRankingPayloadDecoderTest {

    @Test
    void preservesCompleteAllianceRankingWhenSomeNamesWereCached() {
        byte[] frame = response(
                member(101, 586, 3),
                member(102, 575, 3),
                member(103, 552, 4));

        List<LabyrinthRankingEntryData> result = new LabyrinthRankingPayloadDecoder().decode(
                List.of(frame), Map.of(101L, "Member A", 103L, "Member C"));

        assertEquals(List.of(
                new LabyrinthRankingEntryData(1, 101, "Member A", 586),
                new LabyrinthRankingEntryData(2, 102, null, 575),
                new LabyrinthRankingEntryData(3, 103, "Member C", 552)
        ), result);
    }

    private byte[] response(byte[]... members) {
        ByteArrayOutputStream array = new ByteArrayOutputStream();
        for (byte[] member : members) {
            array.writeBytes(uint32(member.length));
            array.writeBytes(member);
        }
        byte[] rpcHeader = {2, 0, 1, 0, 0x66, (byte) 0xa0};
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.writeBytes(uint16(2));
        response.writeBytes(uint16(2));
        response.writeBytes(uint16(0));
        response.writeBytes(uint32(array.size()));
        response.writeBytes(array.toByteArray());
        ByteArrayOutputStream unpacked = new ByteArrayOutputStream();
        unpacked.writeBytes(rpcHeader);
        unpacked.writeBytes(response.toByteArray());
        return pack(unpacked.toByteArray());
    }

    private byte[] member(long playerId, int score, int category) {
        ByteArrayOutputStream member = new ByteArrayOutputStream();
        member.writeBytes(uint16(3));
        member.writeBytes(uint16(0));
        member.writeBytes(uint16((score + 1) * 2));
        member.writeBytes(uint16((category + 1) * 2));
        member.writeBytes(uint32(4));
        member.writeBytes(uint32(playerId));
        return member.toByteArray();
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
            } else {
                packed.write(tag);
                for (int index = 0; index < remaining; index++) {
                    if (unpacked[offset + index] != 0) {
                        packed.write(unpacked[offset + index]);
                    }
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
