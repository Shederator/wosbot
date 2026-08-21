package dev.frostguard.engine.ranking.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlliancePowerRankListDecoderTest {

    @Test
    void preservesServerRankOrderForEveryPlayerId() {
        byte[] members = array(member(2003, 900), member(2001, 800), member(2002, 700));
        byte[] rpcHeader = {2, 0, 1, 0, 0x42, 0};
        byte[] response = struct(List.of(members), List.of(0));
        ByteArrayOutputStream unpacked = new ByteArrayOutputStream();
        unpacked.writeBytes(rpcHeader);
        unpacked.writeBytes(response);

        assertEquals(List.of(
                new AlliancePowerRankListDecoder.RankedPlayer(1, 2003, 900),
                new AlliancePowerRankListDecoder.RankedPlayer(2, 2001, 800),
                new AlliancePowerRankListDecoder.RankedPlayer(3, 2002, 700)
        ), new AlliancePowerRankListDecoder().decode(List.of(pack(unpacked.toByteArray()))));
    }

    private byte[] member(long playerId, long power) {
        return struct(List.of(uint32(playerId), uint32(power)), List.of(3));
    }

    private byte[] array(byte[]... members) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] member : members) {
            output.writeBytes(uint32(member.length));
            output.writeBytes(member);
        }
        return output.toByteArray();
    }

    private byte[] struct(List<byte[]> dataFields, List<Integer> inlineValues) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(uint16(dataFields.size() + inlineValues.size()));
        dataFields.forEach(ignored -> output.writeBytes(uint16(0)));
        inlineValues.forEach(value -> output.writeBytes(uint16((value + 1) * 2)));
        dataFields.forEach(field -> {
            output.writeBytes(uint32(field.length));
            output.writeBytes(field);
        });
        return output.toByteArray();
    }

    private byte[] pack(byte[] unpacked) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int offset = 0; offset < unpacked.length; offset += 8) {
            int remaining = Math.min(8, unpacked.length - offset);
            int tag = 0;
            for (int index = 0; index < remaining; index++) {
                if (unpacked[offset + index] != 0) tag |= 1 << index;
            }
            output.write(tag);
            for (int index = 0; index < remaining; index++) {
                if (unpacked[offset + index] != 0) output.write(unpacked[offset + index]);
            }
        }
        return output.toByteArray();
    }

    private byte[] uint16(int value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
    }

    private byte[] uint32(long value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array();
    }
}
