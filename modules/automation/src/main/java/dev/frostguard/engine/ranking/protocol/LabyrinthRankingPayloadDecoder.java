package dev.frostguard.engine.ranking.protocol;

import dev.frostguard.api.domain.LabyrinthRankingEntryData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Joins the Labyrinth response with captured alliance member FIDs. */
public final class LabyrinthRankingPayloadDecoder {

    private final SprotoPackedDecoder packedDecoder = new SprotoPackedDecoder();
    private final SprotoStructReader structReader = new SprotoStructReader();

    public List<LabyrinthRankingEntryData> decode(List<byte[]> packedFrames,
                                                   Map<Long, String> allianceMembers) {
        if (packedFrames == null || allianceMembers == null) {
            throw new IllegalArgumentException("packedFrames and allianceMembers must not be null");
        }

        List<LabyrinthEntry> largestRanking = List.of();
        for (byte[] packedFrame : packedFrames) {
            List<LabyrinthEntry> candidate = decodeFrame(packedFrame);
            if (candidate.size() > largestRanking.size()) {
                largestRanking = candidate;
            }
        }

        List<LabyrinthEntry> ordered = largestRanking.stream()
                .sorted(Comparator.comparingInt(LabyrinthEntry::rank))
                .toList();
        List<LabyrinthRankingEntryData> result = new ArrayList<>(ordered.size());
        for (LabyrinthEntry entry : ordered) {
            result.add(new LabyrinthRankingEntryData(entry.rank(), entry.playerId(),
                    allianceMembers.get(entry.playerId()), entry.score()));
        }
        return List.copyOf(result);
    }

    private List<LabyrinthEntry> decodeFrame(byte[] packedFrame) {
        try {
            byte[] unpacked = packedDecoder.unpack(packedFrame);
            SprotoStructReader.SprotoStruct rpcHeader = structReader.read(unpacked, 0, unpacked.length);
            if (rpcHeader.consumedBytes() >= unpacked.length) {
                return List.of();
            }
            SprotoStructReader.SprotoStruct response = structReader.read(unpacked,
                    rpcHeader.consumedBytes(), unpacked.length - rpcHeader.consumedBytes());
            List<LabyrinthEntry> largest = List.of();
            for (byte[] data : response.dataFields()) {
                List<LabyrinthEntry> candidate = decodeRankingArray(data);
                if (candidate.size() > largest.size()) {
                    largest = candidate;
                }
            }
            return largest;
        } catch (IllegalArgumentException malformed) {
            return List.of();
        }
    }

    private List<LabyrinthEntry> decodeRankingArray(byte[] array) {
        List<LabyrinthEntry> result = new ArrayList<>();
        int offset = 0;
        long previousScore = Long.MAX_VALUE;
        while (offset < array.length) {
            if (array.length - offset < 4) {
                return List.of();
            }
            long length = SprotoStructReader.uint32(array, offset);
            if (length < 1 || length > array.length - offset - 4L) {
                return List.of();
            }
            int memberLength = (int) length;
            SprotoStructReader.SprotoStruct member = structReader.read(array, offset + 4, memberLength);
            if (member.consumedBytes() != memberLength || member.fields().size() != 3) {
                return List.of();
            }
            SprotoStructReader.SprotoField id = field(member, 0);
            SprotoStructReader.SprotoField score = field(member, 1);
            SprotoStructReader.SprotoField category = field(member, 2);
            if (id == null || !id.hasData() || id.data().length != 4
                    || score == null || score.hasData() || score.inlineValue() < 0
                    || category == null || category.hasData() || category.inlineValue() < 0
                    || score.inlineValue() > previousScore) {
                return List.of();
            }
            long playerId = SprotoStructReader.uint32(id.data(), 0);
            if (playerId == 0) {
                return List.of();
            }
            result.add(new LabyrinthEntry(result.size() + 1, playerId, score.inlineValue()));
            previousScore = score.inlineValue();
            offset += 4 + memberLength;
        }
        return result.size() < 2 ? List.of() : List.copyOf(result);
    }

    private SprotoStructReader.SprotoField field(SprotoStructReader.SprotoStruct struct, int tag) {
        return struct.fields().stream().filter(field -> field.tag() == tag).findFirst().orElse(null);
    }

    private record LabyrinthEntry(int rank, long playerId, long score) {
    }
}
