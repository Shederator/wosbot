package dev.frostguard.engine.ranking.protocol;

import dev.frostguard.api.domain.AllianceRankingEntryData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts alliance power records from validated packed sproto response frames. */
public final class PowerRankingPayloadDecoder {

    private static final int MINIMUM_MEMBER_DATA_FIELDS = 4;
    private static final int MAXIMUM_NAME_CODE_POINTS = 64;

    private final SprotoPackedDecoder packedDecoder = new SprotoPackedDecoder();
    private final SprotoStructReader structReader = new SprotoStructReader();

    public List<AllianceRankingEntryData> decode(List<byte[]> packedFrames) {
        if (packedFrames == null) {
            throw new IllegalArgumentException("packedFrames must not be null");
        }

        Map<Long, DecodedMember> members = new LinkedHashMap<>();
        for (byte[] packedFrame : packedFrames) {
            for (DecodedMember member : decodeFrame(packedFrame)) {
                members.put(member.playerId(), member);
            }
        }

        List<DecodedMember> ranked = members.values().stream()
                .sorted(Comparator.comparingLong(DecodedMember::power).reversed()
                        .thenComparingLong(DecodedMember::playerId))
                .toList();
        List<AllianceRankingEntryData> result = new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            DecodedMember member = ranked.get(index);
            result.add(new AllianceRankingEntryData(index + 1, member.playerId(), member.playerName(), member.power()));
        }
        return List.copyOf(result);
    }

    /** Returns whether a byte sequence is a complete packed sproto response frame. */
    public boolean isValidFrame(byte[] packedFrame) {
        try {
            byte[] unpacked = packedDecoder.unpack(packedFrame);
            SprotoStructReader.SprotoStruct rpcHeader = structReader.read(unpacked, 0, unpacked.length);
            if (rpcHeader.consumedBytes() >= unpacked.length) {
                return true;
            }
            structReader.read(unpacked, rpcHeader.consumedBytes(), unpacked.length - rpcHeader.consumedBytes());
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    List<DecodedMember> decodeFrame(byte[] packedFrame) {
        byte[] unpacked = packedDecoder.unpack(packedFrame);
        SprotoStructReader.SprotoStruct rpcHeader = structReader.read(unpacked, 0, unpacked.length);
        if (rpcHeader.consumedBytes() >= unpacked.length) {
            return List.of();
        }

        int payloadOffset = rpcHeader.consumedBytes();
        SprotoStructReader.SprotoStruct response = structReader.read(
                unpacked, payloadOffset, unpacked.length - payloadOffset);
        for (byte[] candidate : response.dataFields()) {
            List<DecodedMember> decoded = decodeMemberArray(candidate);
            if (!decoded.isEmpty()) {
                return decoded;
            }
        }
        return List.of();
    }

    private List<DecodedMember> decodeMemberArray(byte[] array) {
        List<DecodedMember> members = new ArrayList<>();
        int offset = 0;
        while (offset < array.length) {
            if (array.length - offset < 4) {
                return List.of();
            }
            long unsignedLength = SprotoStructReader.uint32(array, offset);
            if (unsignedLength < 1 || unsignedLength > array.length - offset - 4L) {
                return List.of();
            }
            int memberLength = (int) unsignedLength;
            int memberOffset = offset + 4;
            SprotoStructReader.SprotoStruct member;
            try {
                member = structReader.read(array, memberOffset, memberLength);
            } catch (IllegalArgumentException malformed) {
                return List.of();
            }
            if (member.consumedBytes() != memberLength) {
                return List.of();
            }
            decodeMember(member.dataFields()).ifPresent(members::add);
            offset = memberOffset + memberLength;
        }
        return List.copyOf(members);
    }

    private java.util.Optional<DecodedMember> decodeMember(List<byte[]> dataFields) {
        if (dataFields.size() < MINIMUM_MEMBER_DATA_FIELDS) {
            return java.util.Optional.empty();
        }
        byte[] playerIdBytes = dataFields.get(1);
        byte[] powerBytes = dataFields.get(3);
        if (playerIdBytes.length != 4 || powerBytes.length != 4) {
            return java.util.Optional.empty();
        }

        String playerName;
        try {
            playerName = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(dataFields.get(0)))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            return java.util.Optional.empty();
        }
        if (!validName(playerName)) {
            return java.util.Optional.empty();
        }

        long playerId = littleEndianUnsignedInt(playerIdBytes);
        long power = littleEndianUnsignedInt(powerBytes);
        if (playerId == 0 || power == 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new DecodedMember(playerId, playerName, power));
    }

    private boolean validName(String value) {
        if (value.isBlank() || value.codePointCount(0, value.length()) > MAXIMUM_NAME_CODE_POINTS) {
            return false;
        }
        return value.codePoints().noneMatch(Character::isISOControl);
    }

    private long littleEndianUnsignedInt(byte[] value) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    record DecodedMember(long playerId, String playerName, long power) {
    }
}
