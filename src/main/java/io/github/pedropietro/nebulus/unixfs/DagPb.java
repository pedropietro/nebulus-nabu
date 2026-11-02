package io.github.pedropietro.nebulus.unixfs;

import io.github.pedropietro.nebulus.cid.Cid;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The dag-pb (MerkleDAG protobuf) codec, using the canonical encoding of {@code @ipld/dag-pb}:
 * links are written before data, and each link is written as Hash, Name, Tsize.
 */
public final class DagPb {

    private DagPb() {
    }

    /** A link to another block. {@code name} and {@code tsize} are optional. */
    public static final class Link {
        private final Cid hash;
        private final String name;
        private final Long tsize;

        public Link(Cid hash, String name, Long tsize) {
            this.hash = hash;
            this.name = name;
            this.tsize = tsize;
        }

        public Cid hash() {
            return hash;
        }

        public String name() {
            return name;
        }

        public Long tsize() {
            return tsize;
        }
    }

    /** A dag-pb node: optional data plus an ordered list of links. */
    public static final class Node {
        private final byte[] data;
        private final List<Link> links;

        public Node(byte[] data, List<Link> links) {
            this.data = data;
            this.links = Collections.unmodifiableList(new ArrayList<>(links));
        }

        public byte[] data() {
            return data;
        }

        public List<Link> links() {
            return links;
        }
    }

    public static byte[] encode(Node node) {
        Protobuf.Writer w = new Protobuf.Writer();
        for (Link link : node.links()) {
            Protobuf.Writer lw = new Protobuf.Writer();
            lw.bytes(1, link.hash().toBytes());
            if (link.name() != null) {
                lw.bytes(2, link.name().getBytes(StandardCharsets.UTF_8));
            }
            if (link.tsize() != null) {
                lw.varint(3, link.tsize());
            }
            w.bytes(2, lw.toByteArray());
        }
        if (node.data() != null) {
            w.bytes(1, node.data());
        }
        return w.toByteArray();
    }

    public static Node decode(byte[] block) {
        Protobuf.Reader r = new Protobuf.Reader(block);
        byte[] data = null;
        List<Link> links = new ArrayList<>();
        while (r.next()) {
            if (r.field() == 1 && r.wireType() == Protobuf.LENGTH_DELIMITED) {
                data = r.readBytes();
            } else if (r.field() == 2 && r.wireType() == Protobuf.LENGTH_DELIMITED) {
                links.add(decodeLink(r.readBytes()));
            } else {
                r.skip();
            }
        }
        return new Node(data, links);
    }

    private static Link decodeLink(byte[] bytes) {
        Protobuf.Reader r = new Protobuf.Reader(bytes);
        Cid hash = null;
        String name = null;
        Long tsize = null;
        while (r.next()) {
            if (r.field() == 1 && r.wireType() == Protobuf.LENGTH_DELIMITED) {
                hash = Cid.fromBytes(r.readBytes());
            } else if (r.field() == 2 && r.wireType() == Protobuf.LENGTH_DELIMITED) {
                name = new String(r.readBytes(), StandardCharsets.UTF_8);
            } else if (r.field() == 3 && r.wireType() == Protobuf.VARINT) {
                tsize = r.readVarint();
            } else {
                r.skip();
            }
        }
        if (hash == null) {
            throw new IllegalArgumentException("dag-pb link without Hash");
        }
        return new Link(hash, name, tsize);
    }

    /** Sorts links by name, comparing UTF-8 bytes, as {@code @ipld/dag-pb}'s prepare() does. */
    public static List<Link> sortByName(List<Link> links) {
        List<Link> sorted = new ArrayList<>(links);
        sorted.sort((a, b) -> Arrays.compareUnsigned(nameBytes(a), nameBytes(b)));
        return sorted;
    }

    private static byte[] nameBytes(Link link) {
        return link.name() == null ? new byte[0] : link.name().getBytes(StandardCharsets.UTF_8);
    }
}
