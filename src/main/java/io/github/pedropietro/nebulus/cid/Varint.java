package io.github.pedropietro.nebulus.cid;

import java.io.ByteArrayOutputStream;

/** Unsigned LEB128 varints, as used by multiformats and protobuf. */
public final class Varint {

    private Varint() {
    }

    public static void write(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    public static byte[] encode(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        write(out, value);
        return out.toByteArray();
    }

    /**
     * Reads a varint from {@code data} starting at {@code offset}.
     *
     * @return {value, offsetAfter}
     */
    public static long[] read(byte[] data, int offset) {
        long result = 0;
        int shift = 0;
        int pos = offset;
        while (true) {
            if (pos >= data.length) {
                throw new IllegalArgumentException("Truncated varint");
            }
            if (shift > 63) {
                throw new IllegalArgumentException("Varint too long");
            }
            int b = data[pos++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new long[]{result, pos};
            }
            shift += 7;
        }
    }
}
