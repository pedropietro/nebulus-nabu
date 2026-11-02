package io.github.pedropietro.nebulus.unixfs;

import io.github.pedropietro.nebulus.cid.Varint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The UnixFS {@code Data} protobuf message stored inside dag-pb nodes. */
public final class UnixFsData {

    public enum Type {
        RAW(0), DIRECTORY(1), FILE(2), METADATA(3), SYMLINK(4), HAMT_SHARD(5);

        final int code;

        Type(int code) {
            this.code = code;
        }

        static Type of(long code) {
            for (Type t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            throw new IllegalArgumentException("Unknown UnixFS type " + code);
        }
    }

    private final Type type;
    private final byte[] data;
    private final Long fileSize;
    private final List<Long> blockSizes;

    public UnixFsData(Type type, byte[] data, Long fileSize, List<Long> blockSizes) {
        this.type = type;
        this.data = data;
        this.fileSize = fileSize;
        this.blockSizes = Collections.unmodifiableList(new ArrayList<>(blockSizes));
    }

    public static UnixFsData directory() {
        return new UnixFsData(Type.DIRECTORY, null, null, List.of());
    }

    public static UnixFsData file(long fileSize, List<Long> blockSizes) {
        return new UnixFsData(Type.FILE, null, fileSize, blockSizes);
    }

    public Type type() {
        return type;
    }

    /** Inline file bytes, or null. */
    public byte[] data() {
        return data;
    }

    public Long fileSize() {
        return fileSize;
    }

    public List<Long> blockSizes() {
        return blockSizes;
    }

    /**
     * Encodes in field order Type(1), Data(2), filesize(3), blocksizes(4, unpacked), matching
     * {@code ipfs-unixfs}. Empty data is omitted; filesize is omitted for directories.
     */
    public byte[] encode() {
        Protobuf.Writer w = new Protobuf.Writer();
        w.varint(1, type.code);
        if (data != null && data.length > 0) {
            w.bytes(2, data);
        }
        if (fileSize != null && type != Type.DIRECTORY && type != Type.HAMT_SHARD) {
            w.varint(3, fileSize);
        }
        for (long size : blockSizes) {
            w.varint(4, size);
        }
        return w.toByteArray();
    }

    public static UnixFsData decode(byte[] bytes) {
        Protobuf.Reader r = new Protobuf.Reader(bytes);
        Type type = null;
        byte[] data = null;
        Long fileSize = null;
        List<Long> blockSizes = new ArrayList<>();
        while (r.next()) {
            switch (r.field()) {
                case 1:
                    type = Type.of(r.readVarint());
                    break;
                case 2:
                    data = r.readBytes();
                    break;
                case 3:
                    fileSize = r.readVarint();
                    break;
                case 4:
                    if (r.wireType() == Protobuf.LENGTH_DELIMITED) {
                        // packed encoding: varints back to back
                        byte[] packed = r.readBytes();
                        int pos = 0;
                        while (pos < packed.length) {
                            long[] v = Varint.read(packed, pos);
                            blockSizes.add(v[0]);
                            pos = (int) v[1];
                        }
                    } else {
                        blockSizes.add(r.readVarint());
                    }
                    break;
                default:
                    r.skip();
            }
        }
        if (type == null) {
            throw new IllegalArgumentException("UnixFS data without Type");
        }
        return new UnixFsData(type, data, fileSize, blockSizes);
    }
}
