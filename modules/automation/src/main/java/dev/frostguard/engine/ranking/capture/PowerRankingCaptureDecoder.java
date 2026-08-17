package dev.frostguard.engine.ranking.capture;

import dev.frostguard.api.domain.AllianceRankingEntryData;
import dev.frostguard.api.domain.LabyrinthRankingEntryData;
import dev.frostguard.engine.ranking.protocol.LengthPrefixedFrameDecoder;
import dev.frostguard.engine.ranking.protocol.LabyrinthRankingPayloadDecoder;
import dev.frostguard.engine.ranking.protocol.AlliancePowerRankListDecoder;
import dev.frostguard.engine.ranking.protocol.PowerRankingPayloadDecoder;
import dev.frostguard.engine.ranking.protocol.SprotoRpcHeaderDecoder;
import dev.frostguard.engine.ranking.protocol.SelfPlayerStateDecoder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decodes validated alliance power entries from a bounded PCAPNG capture. */
public final class PowerRankingCaptureDecoder {

    public static final int GAME_PORT = 30101;
    private static final int MAXIMUM_FRAME_LENGTH = 32 * 1024;

    private final PcapngTcpStreamReader streamReader;
    private final PowerRankingPayloadDecoder payloadDecoder;
    private final LabyrinthRankingPayloadDecoder labyrinthDecoder;
    private final AlliancePowerRankListDecoder rankListDecoder = new AlliancePowerRankListDecoder();
    private final SprotoRpcHeaderDecoder headerDecoder = new SprotoRpcHeaderDecoder();
    private final SelfPlayerStateDecoder selfPlayerStateDecoder = new SelfPlayerStateDecoder();

    public PowerRankingCaptureDecoder() {
        this(new PcapngTcpStreamReader(), new PowerRankingPayloadDecoder(),
                new LabyrinthRankingPayloadDecoder());
    }

    PowerRankingCaptureDecoder(PcapngTcpStreamReader streamReader,
                               PowerRankingPayloadDecoder payloadDecoder,
                               LabyrinthRankingPayloadDecoder labyrinthDecoder) {
        this.streamReader = streamReader;
        this.payloadDecoder = payloadDecoder;
        this.labyrinthDecoder = labyrinthDecoder;
    }

    public List<AllianceRankingEntryData> decode(Path capture) throws IOException {
        return decodeAll(capture).power();
    }

    public AllianceRankingCaptureResult decodeAll(Path capture) throws IOException {
        List<byte[]> frames = readFrames(capture);
        List<AllianceRankingEntryData> power = payloadDecoder.decode(frames);
        Map<Long, String> allianceMembers = new LinkedHashMap<>();
        for (AllianceRankingEntryData entry : power) {
            allianceMembers.put(entry.playerId(), entry.playerName());
        }
        List<LabyrinthRankingEntryData> labyrinth = labyrinthDecoder.decode(frames, allianceMembers);
        return new AllianceRankingCaptureResult(power, labyrinth);
    }

    public AllianceRankingCaptureResult decode(Path capture, GameAnalyticsCollectionType type) throws IOException {
        List<byte[]> inbound = readFrames(capture, true);
        List<byte[]> outbound = readFrames(capture, false);
        if (type == GameAnalyticsCollectionType.POWER) {
            // Startup traffic includes the local player's state, while the ranking screen only
            // requests protocol 1029 details for other alliance members.
            List<AllianceRankingEntryData> profiles = payloadDecoder.decode(inbound);
            Map<Long, AllianceRankingEntryData> profilesById = new LinkedHashMap<>();
            profiles.forEach(profile -> profilesById.put(profile.playerId(), profile));
            selfPlayerStateDecoder.decode(inbound).ifPresent(self -> profilesById.put(
                    self.playerId(), new AllianceRankingEntryData(1, self.playerId(),
                            self.playerName(), self.power())));
            var rankedPlayers = rankListDecoder.decode(correlatedResponses(inbound, outbound, 5404));
            if (rankedPlayers.isEmpty()) {
                return new AllianceRankingCaptureResult(profiles, List.of());
            }
            List<AllianceRankingEntryData> ranked = rankedPlayers.stream().map(player -> {
                AllianceRankingEntryData profile = profilesById.get(player.playerId());
                return profile == null
                        ? new AllianceRankingEntryData(player.rank(), player.playerId(), null,
                        player.power(), false, false)
                        : new AllianceRankingEntryData(player.rank(), player.playerId(),
                        profile.playerName(), player.power(), false, false);
            }).toList();
            return new AllianceRankingCaptureResult(ranked, List.of());
        }
        List<AllianceRankingEntryData> profiles = payloadDecoder.decode(
                correlatedResponses(inbound, outbound, 1029));
        Map<Long, String> memberNames = new LinkedHashMap<>();
        for (AllianceRankingEntryData profile : profiles) {
            memberNames.put(profile.playerId(), profile.playerName());
        }
        return new AllianceRankingCaptureResult(List.of(), labyrinthDecoder.decode(
                correlatedResponses(inbound, outbound, 20479), memberNames));
    }

    public AllianceRankingCaptureResult decodePhases(Path labyrinthCapture, Path powerCapture) throws IOException {
        List<byte[]> labyrinthFrames = readFrames(labyrinthCapture);
        List<byte[]> powerFrames = readFrames(powerCapture);
        List<AllianceRankingEntryData> power = payloadDecoder.decode(powerFrames);

        Map<Long, String> memberNames = new LinkedHashMap<>();
        for (AllianceRankingEntryData entry : payloadDecoder.decode(labyrinthFrames)) {
            memberNames.put(entry.playerId(), entry.playerName());
        }
        for (AllianceRankingEntryData entry : power) {
            memberNames.put(entry.playerId(), entry.playerName());
        }
        List<LabyrinthRankingEntryData> labyrinth = labyrinthDecoder.decode(labyrinthFrames, memberNames);
        return new AllianceRankingCaptureResult(power, labyrinth);
    }

    private List<byte[]> readFrames(Path capture) throws IOException {
        return readFrames(capture, true);
    }

    private List<byte[]> readFrames(Path capture, boolean inbound) throws IOException {
        List<byte[]> frames = new ArrayList<>();
        List<byte[]> streams = inbound
                ? streamReader.readInboundStreams(capture, GAME_PORT)
                : streamReader.readOutboundStreams(capture, GAME_PORT);
        for (byte[] stream : streams) {
            LengthPrefixedFrameDecoder frameDecoder = new LengthPrefixedFrameDecoder(MAXIMUM_FRAME_LENGTH);
            try {
                frames.addAll(frameDecoder.accept(stream));
                frameDecoder.requireComplete();
            } catch (IllegalArgumentException | IllegalStateException incompleteStream) {
                // Captures can start after an existing connection has already transmitted part of a frame.
                frames.addAll(scanForFrames(stream));
            }
        }
        return List.copyOf(frames);
    }

    private List<byte[]> correlatedResponses(List<byte[]> inbound, List<byte[]> outbound,
                                             long protocolType) {
        java.util.Set<Long> sessions = outbound.stream()
                .map(headerDecoder::decode)
                .flatMap(java.util.Optional::stream)
                .filter(header -> header.protocolType() != null
                        && header.protocolType() == protocolType && header.session() != null)
                .map(SprotoRpcHeaderDecoder.RpcHeader::session)
                .collect(java.util.stream.Collectors.toSet());
        if (sessions.isEmpty()) {
            return List.of();
        }
        return inbound.stream()
                .filter(frame -> headerDecoder.decode(frame)
                        .map(header -> header.protocolType() == null
                                && header.session() != null && sessions.contains(header.session()))
                        .orElse(false))
                .toList();
    }

    private List<byte[]> scanForFrames(byte[] stream) {
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (stream.length - offset >= 2) {
            int length = ((stream[offset] & 0xff) << 8) | (stream[offset + 1] & 0xff);
            if (length > 0 && length <= MAXIMUM_FRAME_LENGTH && stream.length - offset - 2 >= length) {
                byte[] candidate = java.util.Arrays.copyOfRange(stream, offset + 2, offset + 2 + length);
                if (payloadDecoder.isValidFrame(candidate)) {
                    frames.add(candidate);
                    offset += length + 2;
                    continue;
                }
            }
            offset++;
        }
        return frames;
    }
}
