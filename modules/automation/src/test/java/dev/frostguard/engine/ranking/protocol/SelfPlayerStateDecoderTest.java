package dev.frostguard.engine.ranking.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelfPlayerStateDecoderTest {

    @Test
    void decodesIdentityBoundToTheStartupPlayerId() {
        ByteArrayOutputStream unpacked = new ByteArrayOutputStream();
        unpacked.writeBytes(uint16(1));
        unpacked.writeBytes(uint16((1010 + 1) * 2));
        unpacked.writeBytes(playerState("Dave", 200217707L, 29452901L));

        assertEquals(new SelfPlayerStateDecoder.PlayerState(200217707L, "Dave", 29452901L),
                new SelfPlayerStateDecoder().decode(List.of(pack(unpacked.toByteArray()))).orElseThrow());
    }

    private byte[] playerState(String name, long playerId, long power) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(uint16(4));
        output.writeBytes(uint16(0));
        output.writeBytes(uint16(3));
        output.writeBytes(uint16(0));
        output.writeBytes(uint16(0));
        writeData(output, name.getBytes(StandardCharsets.UTF_8));
        writeData(output, uint32(playerId));
        writeData(output, uint32(power));
        return output.toByteArray();
    }

    private void writeData(ByteArrayOutputStream output, byte[] data) {
        output.writeBytes(uint32(data.length));
        output.writeBytes(data);
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
