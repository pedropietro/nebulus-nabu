package io.github.pedropietro.nebulus.cid;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * A minimal, immutable Content Identifier (CID) implementation.
 *
 * <p>Supports CIDv0 ({@code Qm...}, implicitly dag-pb + sha2-256) and CIDv1 with any codec and
 * multihash. New CIDs are always created as CIDv1 + sha2-256, rendered in base32, which is what
 * Nebulus (and {@code ipfs add --cid-version=1 --raw-leaves}) produces.</p>
 */
public final class Cid {

    /** Multicodec for raw binary blocks. */
    public static final int RAW = 0x55;
    /** Multicodec for MerkleDAG protobuf (UnixFS) nodes. */
    public static final int DAG_PB = 0x70;
    /** Multihash code for sha2-256. */
    public static final int SHA2_256 = 0x12;

    private final int version;
    private final int codec;
    private final byte[] multihash;

    private Cid(int version, int codec, byte[] multihash) {
        this.version = version;
        this.codec = codec;
        this.multihash = multihash;
    }

    /** CIDv1 of {@code block} with the given codec, hashed with sha2-256. */
    public static Cid of(int codec, byte[] block) {
        byte[] digest = sha256(block);
        byte[] mh = new byte[2 + digest.length];
        mh[0] = (byte) SHA2_256;
        mh[1] = (byte) digest.length;
        System.arraycopy(digest, 0, mh, 2, digest.length);
        return new Cid(1, codec, mh);
    }

    /** Parses a CID in its string form (CIDv0 base58, or CIDv1 multibase base32/base58btc). */
    public static Cid decode(String text) {
        Objects.requireNonNull(text, "cid");
        String s = text.trim();
        if (s.startsWith("/ipfs/")) {
            s = s.substring("/ipfs/".length());
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Empty CID");
        }
        if (s.length() == 46 && s.startsWith("Qm")) {
            return fromBytes(Base58.decode(s));
        }
        char prefix = s.charAt(0);
        String rest = s.substring(1);
        switch (prefix) {
            case 'b':
            case 'B':
                return fromBytes(Base32.decode(rest));
            case 'z':
                return fromBytes(Base58.decode(rest));
            default:
                throw new IllegalArgumentException("Unsupported CID encoding: " + text);
        }
    }

    /** Parses the binary form of a CID (as found inside dag-pb links). */
    public static Cid fromBytes(byte[] bytes) {
        if (bytes.length == 34 && (bytes[0] & 0xFF) == SHA2_256 && bytes[1] == 32) {
            return new Cid(0, DAG_PB, bytes.clone());
        }
        long[] version = Varint.read(bytes, 0);
        if (version[0] != 1) {
            throw new IllegalArgumentException("Unsupported CID version: " + version[0]);
        }
        long[] codec = Varint.read(bytes, (int) version[1]);
        int mhStart = (int) codec[1];
        byte[] mh = Arrays.copyOfRange(bytes, mhStart, bytes.length);
        validateMultihash(mh);
        return new Cid(1, (int) codec[0], mh);
    }

    /** Returns true when {@code text} parses as a CID. */
    public static boolean isValid(String text) {
        try {
            decode(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void validateMultihash(byte[] mh) {
        long[] code = Varint.read(mh, 0);
        long[] length = Varint.read(mh, (int) code[1]);
        if (length[1] + length[0] != mh.length) {
            throw new IllegalArgumentException("Invalid multihash length");
        }
    }

    public int version() {
        return version;
    }

    public int codec() {
        return codec;
    }

    /** The multihash function code (e.g. {@link #SHA2_256}). */
    public int hashFunction() {
        return (int) Varint.read(multihash, 0)[0];
    }

    /** The raw digest bytes, without the multihash prefix. */
    public byte[] digest() {
        long[] code = Varint.read(multihash, 0);
        long[] length = Varint.read(multihash, (int) code[1]);
        return Arrays.copyOfRange(multihash, (int) length[1], multihash.length);
    }

    public byte[] multihash() {
        return multihash.clone();
    }

    /** Checks that {@code block} hashes to this CID (only sha2-256 is verified). */
    public boolean matches(byte[] block) {
        if (hashFunction() != SHA2_256) {
            return true;
        }
        return MessageDigest.isEqual(digest(), sha256(block));
    }

    /** The binary form of this CID. */
    public byte[] toBytes() {
        if (version == 0) {
            return multihash.clone();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(multihash.length + 4);
        Varint.write(out, 1);
        Varint.write(out, codec);
        out.writeBytes(multihash);
        return out.toByteArray();
    }

    /** CIDv0 as base58 ({@code Qm...}), CIDv1 as multibase base32 ({@code b...}). */
    @Override
    public String toString() {
        if (version == 0) {
            return Base58.encode(multihash);
        }
        return "b" + Base32.encode(toBytes());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Cid)) {
            return false;
        }
        Cid other = (Cid) o;
        return version == other.version && codec == other.codec && Arrays.equals(multihash, other.multihash);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * version + codec) + Arrays.hashCode(multihash);
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
