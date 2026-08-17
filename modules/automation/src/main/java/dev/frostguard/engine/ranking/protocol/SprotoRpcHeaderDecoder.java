package dev.frostguard.engine.ranking.protocol;

import java.util.Optional;

/** Reads the protocol type and correlation session from a packed sproto RPC frame. */
public final class SprotoRpcHeaderDecoder {

    private final SprotoPackedDecoder packedDecoder = new SprotoPackedDecoder();
    private final SprotoStructReader structReader = new SprotoStructReader();

    public Optional<RpcHeader> decode(byte[] packedFrame) {
        try {
            byte[] unpacked = packedDecoder.unpack(packedFrame);
            SprotoStructReader.SprotoStruct header = structReader.read(unpacked, 0, unpacked.length);
            Long type = inline(header, 0);
            Long session = inline(header, 1);
            if (type == null && session == null) {
                return Optional.empty();
            }
            return Optional.of(new RpcHeader(type, session));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private Long inline(SprotoStructReader.SprotoStruct header, int tag) {
        return header.fields().stream()
                .filter(field -> field.tag() == tag && !field.hasData())
                .map(SprotoStructReader.SprotoField::inlineValue)
                .findFirst().orElse(null);
    }

    public record RpcHeader(Long protocolType, Long session) {
    }
}
