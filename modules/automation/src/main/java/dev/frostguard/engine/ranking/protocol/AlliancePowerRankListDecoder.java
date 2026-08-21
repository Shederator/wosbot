package dev.frostguard.engine.ranking.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decodes the ordered alliance-member IDs returned by the power-ranking list RPC. */
public final class AlliancePowerRankListDecoder {

    private final SprotoPackedDecoder packedDecoder = new SprotoPackedDecoder();
    private final SprotoStructReader structReader = new SprotoStructReader();

    public List<RankedPlayer> decode(List<byte[]> packedFrames) {
        Map<Long, RankedPlayer> players = new LinkedHashMap<>();
        for (byte[] packedFrame : packedFrames) {
            for (RankedPlayer player : decodeFrame(packedFrame)) {
                players.putIfAbsent(player.playerId(), new RankedPlayer(
                        players.size() + 1, player.playerId(), player.power()));
            }
        }
        return List.copyOf(players.values());
    }

    private List<RankedPlayer> decodeFrame(byte[] packedFrame) {
        byte[] unpacked = packedDecoder.unpack(packedFrame);
        SprotoStructReader.SprotoStruct rpcHeader = structReader.read(unpacked, 0, unpacked.length);
        if (rpcHeader.consumedBytes() >= unpacked.length) return List.of();
        int payloadOffset = rpcHeader.consumedBytes();
        SprotoStructReader.SprotoStruct response = structReader.read(
                unpacked, payloadOffset, unpacked.length - payloadOffset);
        for (byte[] candidate : response.dataFields()) {
            List<RankedPlayer> players = decodePlayerArray(candidate);
            if (!players.isEmpty()) return players;
        }
        return List.of();
    }

    private List<RankedPlayer> decodePlayerArray(byte[] array) {
        List<RankedPlayer> players = new ArrayList<>();
        int offset = 0;
        while (offset < array.length) {
            if (array.length - offset < 4) return List.of();
            long unsignedLength = SprotoStructReader.uint32(array, offset);
            if (unsignedLength < 1 || unsignedLength > array.length - offset - 4L) return List.of();
            int memberLength = (int) unsignedLength;
            int memberOffset = offset + 4;
            SprotoStructReader.SprotoStruct member = structReader.read(array, memberOffset, memberLength);
            if (member.consumedBytes() != memberLength) return List.of();
            byte[] playerId = member.fields().stream()
                    .filter(field -> field.tag() == 0 && field.hasData() && field.data().length == 4)
                    .map(SprotoStructReader.SprotoField::data)
                    .findFirst().orElse(null);
            byte[] power = member.fields().stream()
                    .filter(field -> field.tag() == 1 && field.hasData() && field.data().length == 4)
                    .map(SprotoStructReader.SprotoField::data)
                    .findFirst().orElse(null);
            if (playerId == null || power == null) return List.of();
            long id = SprotoStructReader.uint32(playerId, 0);
            long powerValue = SprotoStructReader.uint32(power, 0);
            if (id < 1 || powerValue < 1) return List.of();
            players.add(new RankedPlayer(players.size() + 1, id, powerValue));
            offset = memberOffset + memberLength;
        }
        return List.copyOf(players);
    }

    public record RankedPlayer(int rank, long playerId, long power) {
    }
}
