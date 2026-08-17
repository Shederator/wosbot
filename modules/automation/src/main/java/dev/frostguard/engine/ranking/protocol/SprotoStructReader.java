package dev.frostguard.engine.ranking.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Schema-independent reader for sproto struct field boundaries. */
final class SprotoStructReader {

    SprotoStruct read(byte[] source, int offset, int length) {
        requireAvailable(source, offset, length, 2, "struct header");
        int fieldSlots = uint16(source, offset);
        int headersLength = 2 + fieldSlots * 2;
        requireAvailable(source, offset, length, headersLength, "field headers");

        int dataOffset = offset + headersLength;
        int limit = offset + length;
        int tag = -1;
        List<SprotoField> fields = new ArrayList<>();
        for (int index = 0; index < fieldSlots; index++) {
            int encoded = uint16(source, offset + 2 + index * 2);
            tag++;
            if ((encoded & 1) != 0) {
                tag += encoded / 2;
                continue;
            }

            long inlineValue = encoded / 2L - 1L;
            if (inlineValue >= 0) {
                fields.add(SprotoField.inline(tag, inlineValue));
                continue;
            }

            if (limit - dataOffset < 4) {
                throw malformed("missing data-field length", dataOffset);
            }
            long unsignedLength = uint32(source, dataOffset);
            if (unsignedLength > Integer.MAX_VALUE) {
                throw malformed("data field is too large", dataOffset);
            }
            int dataLength = (int) unsignedLength;
            dataOffset += 4;
            if (limit - dataOffset < dataLength) {
                throw malformed("truncated data field", dataOffset);
            }
            fields.add(SprotoField.data(tag, Arrays.copyOfRange(source, dataOffset, dataOffset + dataLength)));
            dataOffset += dataLength;
        }
        return new SprotoStruct(List.copyOf(fields), dataOffset - offset);
    }

    private void requireAvailable(byte[] source, int offset, int length, int required, String part) {
        if (source == null || offset < 0 || length < 0 || offset > source.length - length || required > length) {
            throw malformed("truncated " + part, offset);
        }
    }

    static int uint16(byte[] source, int offset) {
        return (source[offset] & 0xff) | ((source[offset + 1] & 0xff) << 8);
    }

    static long uint32(byte[] source, int offset) {
        return (source[offset] & 0xffL)
                | ((source[offset + 1] & 0xffL) << 8)
                | ((source[offset + 2] & 0xffL) << 16)
                | ((source[offset + 3] & 0xffL) << 24);
    }

    private IllegalArgumentException malformed(String reason, int offset) {
        return new IllegalArgumentException(reason + " at unpacked offset " + offset);
    }

    record SprotoStruct(List<SprotoField> fields, int consumedBytes) {
        List<byte[]> dataFields() {
            return fields.stream().filter(SprotoField::hasData).map(SprotoField::data).toList();
        }
    }

    record SprotoField(int tag, long inlineValue, byte[] data) {
        static SprotoField inline(int tag, long value) {
            return new SprotoField(tag, value, null);
        }

        static SprotoField data(int tag, byte[] value) {
            return new SprotoField(tag, -1, value);
        }

        boolean hasData() {
            return data != null;
        }
    }
}
