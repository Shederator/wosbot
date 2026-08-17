package dev.frostguard.engine.ranking.protocol;

import java.io.ByteArrayOutputStream;

/** Decodes the zero-packing format used by sproto messages. */
public final class SprotoPackedDecoder {

    private static final int WORD_BYTES = 8;

    public byte[] unpack(byte[] packed) {
        if (packed == null) {
            throw new IllegalArgumentException("packed message must not be null");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < packed.length) {
            int tag = packed[offset++] & 0xff;
            if (tag == 0xff) {
                if (offset >= packed.length) {
                    throw malformed("missing raw-run length", offset - 1);
                }
                int byteCount = ((packed[offset++] & 0xff) + 1) * WORD_BYTES;
                if (packed.length - offset < byteCount) {
                    throw malformed("truncated raw run", offset - 2);
                }
                output.write(packed, offset, byteCount);
                offset += byteCount;
                continue;
            }

            for (int bit = 0; bit < WORD_BYTES; bit++) {
                if ((tag & (1 << bit)) == 0) {
                    output.write(0);
                } else {
                    if (offset >= packed.length) {
                        throw malformed("truncated packed word", offset - 1);
                    }
                    output.write(packed[offset++]);
                }
            }
        }
        return output.toByteArray();
    }

    private IllegalArgumentException malformed(String reason, int offset) {
        return new IllegalArgumentException(reason + " at packed offset " + offset);
    }
}
