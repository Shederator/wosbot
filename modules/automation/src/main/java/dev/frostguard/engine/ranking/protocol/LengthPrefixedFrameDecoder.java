package dev.frostguard.engine.ranking.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reassembles two-byte big-endian length-prefixed game protocol frames. */
public final class LengthPrefixedFrameDecoder {

    private static final int HEADER_BYTES = 2;

    private final int maximumFrameLength;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    public LengthPrefixedFrameDecoder(int maximumFrameLength) {
        if (maximumFrameLength < 1 || maximumFrameLength > 0xffff) {
            throw new IllegalArgumentException("maximumFrameLength must be between 1 and 65535");
        }
        this.maximumFrameLength = maximumFrameLength;
    }

    public List<byte[]> accept(byte[] chunk) {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk must not be null");
        }
        pending.writeBytes(chunk);

        byte[] bytes = pending.toByteArray();
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (bytes.length - offset >= HEADER_BYTES) {
            int length = ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
            if (length < 1 || length > maximumFrameLength) {
                throw new IllegalArgumentException("invalid game frame length: " + length);
            }
            if (bytes.length - offset - HEADER_BYTES < length) {
                break;
            }
            frames.add(Arrays.copyOfRange(bytes, offset + HEADER_BYTES, offset + HEADER_BYTES + length));
            offset += HEADER_BYTES + length;
        }

        if (offset > 0) {
            pending.reset();
            pending.writeBytes(Arrays.copyOfRange(bytes, offset, bytes.length));
        }
        return List.copyOf(frames);
    }

    public boolean hasPendingBytes() {
        return pending.size() > 0;
    }

    public void requireComplete() {
        if (hasPendingBytes()) {
            throw new IllegalStateException("truncated game frame: " + pending.size() + " byte(s) remain");
        }
    }
}
