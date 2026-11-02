package io.github.pedropietro.nebulus.unixfs;

import io.github.pedropietro.nebulus.cid.Varint;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Just enough protobuf (proto2 wire format) to read and write dag-pb and UnixFS messages. */
final class Protobuf {

    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int LENGTH_DELIMITED = 2;
    static final int FIXED32 = 5;

    private Protobuf() {
    }

    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer varint(int field, long value) {
            Varint.write(out, ((long) field << 3) | VARINT);
            Varint.write(out, value);
            return this;
        }

        Writer bytes(int field, byte[] value) {
            Varint.write(out, ((long) field << 3) | LENGTH_DELIMITED);
            Varint.write(out, value.length);
            out.writeBytes(value);
            return this;
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    /** Sequential reader over the fields of one message. */
    static final class Reader {
        private final byte[] data;
        private int pos;
        private int field;
        private int wireType;

        Reader(byte[] data) {
            this.data = data;
        }

        /** Advances to the next field; returns false at the end of the message. */
        boolean next() {
            if (pos >= data.length) {
                return false;
            }
            long[] key = Varint.read(data, pos);
            pos = (int) key[1];
            field = (int) (key[0] >>> 3);
            wireType = (int) (key[0] & 0x7);
            return true;
        }

        int field() {
            return field;
        }

        int wireType() {
            return wireType;
        }

        long readVarint() {
            long[] v = Varint.read(data, pos);
            pos = (int) v[1];
            return v[0];
        }

        byte[] readBytes() {
            long[] len = Varint.read(data, pos);
            int start = (int) len[1];
            long end = start + len[0];
            if (len[0] < 0 || end > data.length) {
                throw new IllegalArgumentException("Truncated length-delimited field");
            }
            pos = (int) end;
            return Arrays.copyOfRange(data, start, (int) end);
        }

        void skip() {
            switch (wireType) {
                case VARINT:
                    readVarint();
                    break;
                case FIXED64:
                    pos += 8;
                    break;
                case LENGTH_DELIMITED:
                    readBytes();
                    break;
                case FIXED32:
                    pos += 4;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported wire type " + wireType);
            }
            if (pos > data.length) {
                throw new IllegalArgumentException("Truncated field");
            }
        }
    }
}
