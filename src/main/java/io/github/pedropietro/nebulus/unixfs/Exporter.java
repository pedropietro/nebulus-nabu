package io.github.pedropietro.nebulus.unixfs;

import io.github.pedropietro.nebulus.cid.Cid;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads UnixFS DAGs back into bytes. Every block is checked against its CID before use, so data
 * from untrusted peers cannot be substituted.
 */
public final class Exporter {

    /** How many sibling blocks are requested from the {@link BlockSource} at once. */
    public static final int DEFAULT_BATCH = 32;

    private final BlockSource source;
    private final int batch;

    public Exporter(BlockSource source) {
        this(source, DEFAULT_BATCH);
    }

    public Exporter(BlockSource source, int batch) {
        this.source = source;
        this.batch = Math.max(1, batch);
    }

    /** What a CID points to. */
    public static final class Entry {
        private final Cid cid;
        private final byte[] block;
        private final boolean directory;
        private final Map<String, Cid> children;

        Entry(Cid cid, byte[] block, boolean directory, Map<String, Cid> children) {
            this.cid = cid;
            this.block = block;
            this.directory = directory;
            this.children = children;
        }

        public Cid cid() {
            return cid;
        }

        public boolean isDirectory() {
            return directory;
        }

        /** Directory entries in link order (name to CID); empty for files. */
        public Map<String, Cid> children() {
            return children;
        }
    }

    /** Fetches the root block and tells whether it is a file or a (flat) directory. */
    public Entry resolve(Cid cid) throws IOException {
        byte[] block = fetch(List.of(cid)).get(cid);
        if (cid.codec() == Cid.RAW) {
            return new Entry(cid, block, false, Map.of());
        }
        if (cid.codec() != Cid.DAG_PB) {
            throw new IOException("Unsupported codec 0x" + Integer.toHexString(cid.codec()) + " for " + cid);
        }
        DagPb.Node node = DagPb.decode(block);
        UnixFsData data = unixfs(node, cid);
        switch (data.type()) {
            case FILE:
            case RAW:
                return new Entry(cid, block, false, Map.of());
            case DIRECTORY:
                Map<String, Cid> children = new LinkedHashMap<>();
                for (DagPb.Link link : node.links()) {
                    String name = link.name() == null ? "" : link.name();
                    children.put(name, link.hash());
                }
                return new Entry(cid, block, true, children);
            case HAMT_SHARD:
                throw new IOException("Sharded (HAMT) directories are not supported: " + cid);
            default:
                throw new IOException("Unsupported UnixFS type " + data.type() + " for " + cid);
        }
    }

    /** Writes the content of the file {@code cid} to {@code out}. */
    public void exportFile(Cid cid, OutputStream out) throws IOException {
        exportFile(resolve(cid), out);
    }

    /** Writes the content of an already resolved file entry to {@code out}. */
    public void exportFile(Entry entry, OutputStream out) throws IOException {
        if (entry.isDirectory()) {
            throw new IOException(entry.cid() + " is a directory");
        }
        write(entry.cid(), entry.block, out);
    }

    private void write(Cid cid, byte[] block, OutputStream out) throws IOException {
        if (cid.codec() == Cid.RAW) {
            out.write(block);
            return;
        }
        if (cid.codec() != Cid.DAG_PB) {
            throw new IOException("Unsupported codec 0x" + Integer.toHexString(cid.codec()) + " for " + cid);
        }
        DagPb.Node node = DagPb.decode(block);
        UnixFsData data = unixfs(node, cid);
        if (data.type() != UnixFsData.Type.FILE && data.type() != UnixFsData.Type.RAW) {
            throw new IOException("Expected a file node but found " + data.type() + " at " + cid);
        }
        if (data.data() != null) {
            out.write(data.data());
        }
        List<DagPb.Link> links = node.links();
        for (int i = 0; i < links.size(); i += batch) {
            List<Cid> wanted = new ArrayList<>();
            for (DagPb.Link link : links.subList(i, Math.min(links.size(), i + batch))) {
                wanted.add(link.hash());
            }
            Map<Cid, byte[]> blocks = fetch(wanted);
            for (Cid child : wanted) {
                write(child, blocks.get(child), out);
            }
        }
    }

    private Map<Cid, byte[]> fetch(List<Cid> cids) throws IOException {
        Map<Cid, byte[]> blocks = source.getAll(cids);
        for (Cid c : cids) {
            byte[] b = blocks.get(c);
            if (b == null) {
                throw new IOException("Block not found: " + c);
            }
            if (!c.matches(b)) {
                throw new IOException("Block does not match its CID: " + c);
            }
        }
        return blocks;
    }

    private static UnixFsData unixfs(DagPb.Node node, Cid cid) throws IOException {
        if (node.data() == null) {
            throw new IOException("dag-pb node without UnixFS data: " + cid);
        }
        try {
            return UnixFsData.decode(node.data());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid UnixFS data in " + cid, e);
        }
    }
}
