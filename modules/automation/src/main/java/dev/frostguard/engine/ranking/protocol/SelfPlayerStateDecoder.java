package dev.frostguard.engine.ranking.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Decodes the local player's identity from the protocol 1010 startup state. */
public final class SelfPlayerStateDecoder {

    private static final long PLAYER_STATE_PROTOCOL = 1010;
    private static final int MAXIMUM_NAME_CODE_POINTS = 64;

    private final SprotoPackedDecoder packedDecoder = new SprotoPackedDecoder();
    private final SprotoStructReader structReader = new SprotoStructReader();

    public Optional<PlayerState> decode(List<byte[]> packedFrames) {
        if (packedFrames == null) throw new IllegalArgumentException("packedFrames must not be null");
        PlayerState latest = null;
        for (byte[] frame : packedFrames) {
            Optional<PlayerState> candidate = decodeFrame(frame);
            if (candidate.isPresent()) latest = candidate.get();
        }
        return Optional.ofNullable(latest);
    }

    private Optional<PlayerState> decodeFrame(byte[] frame) {
        try {
            byte[] unpacked = packedDecoder.unpack(frame);
            SprotoStructReader.SprotoStruct header = structReader.read(unpacked, 0, unpacked.length);
            Long protocol = inline(header, 0);
            if (protocol == null || protocol != PLAYER_STATE_PROTOCOL
                    || header.consumedBytes() >= unpacked.length) {
                return Optional.empty();
            }
            SprotoStructReader.SprotoStruct payload = structReader.read(unpacked,
                    header.consumedBytes(), unpacked.length - header.consumedBytes());
            byte[] nameBytes = data(payload, 0);
            byte[] playerIdBytes = data(payload, 3);
            byte[] powerBytes = data(payload, 4);
            if (nameBytes == null || playerIdBytes == null || playerIdBytes.length != 4
                    || powerBytes == null || powerBytes.length != 4) {
                return Optional.empty();
            }
            String name = decodeName(nameBytes);
            long playerId = uint32(playerIdBytes);
            long power = uint32(powerBytes);
            if (name == null || playerId < 1 || power < 1) return Optional.empty();
            return Optional.of(new PlayerState(playerId, name, power));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private Long inline(SprotoStructReader.SprotoStruct struct, int tag) {
        return struct.fields().stream()
                .filter(field -> field.tag() == tag && !field.hasData())
                .map(SprotoStructReader.SprotoField::inlineValue)
                .findFirst().orElse(null);
    }

    private byte[] data(SprotoStructReader.SprotoStruct struct, int tag) {
        return struct.fields().stream()
                .filter(field -> field.tag() == tag && field.hasData())
                .map(SprotoStructReader.SprotoField::data)
                .findFirst().orElse(null);
    }

    private String decodeName(byte[] value) {
        try {
            String name = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
            if (name.isBlank() || name.codePointCount(0, name.length()) > MAXIMUM_NAME_CODE_POINTS
                    || name.codePoints().anyMatch(Character::isISOControl)) {
                return null;
            }
            return name;
        } catch (CharacterCodingException invalidUtf8) {
            return null;
        }
    }

    private long uint32(byte[] value) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    public record PlayerState(long playerId, String playerName, long power) {
    }
}
