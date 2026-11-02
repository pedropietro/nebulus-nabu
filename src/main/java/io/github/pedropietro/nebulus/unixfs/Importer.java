package io.github.pedropietro.nebulus.unixfs;

import io.github.pedropietro.nebulus.cid.Cid;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UnixFS importer that reproduces {@code ipfs-unixfs-importer@8} with the options Nebulus uses
 * ({@code cidVersion: 1, rawLeaves: true}): fixed 256 KiB chunks, balanced DAG with up to 174
 * links per node, sha2-256. The CIDs are the same as
 * {@code ipfs add --cid-version=1 --raw-leaves}.
 */
public final class Importer {

    public static final int CHUNK_SIZE = 262_144;
    public static final int MAX_CHILDREN = 174;

    private final int chunkSize;
    private final int maxChildren;

    public Importer() {
        this(CHUNK_SIZE, MAX_CHILDREN);
    }

    public Importer(int chunkSize, int maxChildren) {
        if (chunkSize <= 0 || maxChildren < 2) {
            throw new IllegalArgumentException("chunkSize must be > 0 and maxChildren >= 2");
        }
        this.chunkSize = chunkSize;
        this.maxChildren = maxChildren;
    }

    /** Imports a file, sending every block to {@code sink}. The stream is read but not closed. */
    public ImportedNode importFile(InputStream in, BlockSink sink) throws IOException {
        List<ImportedNode> leaves = new ArrayList<>();
        while (true) {
            byte[] chunk = in.readNBytes(chunkSize);
            if (chunk.length == 0 && !leaves.isEmpty()) {
                break;
            }
            Cid cid = Cid.of(Cid.RAW, chunk);
            sink.put(new Block(cid, chunk));
            leaves.add(new ImportedNode(cid, chunk.length, chunk.length));
            if (chunk.length < chunkSize) {
                break;
            }
        }
        if (leaves.size() == 1) {
            // reduceSingleLeafToSelf: a one-chunk file is just its raw block
            return leaves.get(0);
        }
        return balanced(leaves, sink);
    }

    /** Convenience for hashing in-memory content. */
    public ImportedNode importBytes(byte[] content, BlockSink sink) throws IOException {
        return importFile(new java.io.ByteArrayInputStream(content), sink);
    }

    private ImportedNode balanced(List<ImportedNode> level, BlockSink sink) throws IOException {
        while (true) {
            List<ImportedNode> roots = new ArrayList<>();
            for (int i = 0; i < level.size(); i += maxChildren) {
                roots.add(reduce(level.subList(i, Math.min(level.size(), i + maxChildren)), sink));
            }
            if (roots.size() == 1) {
                return roots.get(0);
            }
            level = roots;
        }
    }

    private ImportedNode reduce(List<ImportedNode> children, BlockSink sink) throws IOException {
        List<DagPb.Link> links = new ArrayList<>();
        List<Long> blockSizes = new ArrayList<>();
        long fileSize = 0;
        long linkedSize = 0;
        for (ImportedNode child : children) {
            boolean raw = child.cid().codec() == Cid.RAW;
            long contentSize = raw ? child.dagSize() : child.fileSize();
            if (contentSize <= 0) {
                continue; // the JS importer drops empty children
            }
            blockSizes.add(contentSize);
            fileSize += contentSize;
            linkedSize += child.dagSize();
            links.add(new DagPb.Link(child.cid(), "", child.dagSize()));
        }
        byte[] encoded = DagPb.encode(new DagPb.Node(UnixFsData.file(fileSize, blockSizes).encode(), links));
        Cid cid = Cid.of(Cid.DAG_PB, encoded);
        sink.put(new Block(cid, encoded));
        return new ImportedNode(cid, encoded.length + linkedSize, fileSize);
    }

    /**
     * Builds a flat UnixFS directory (Nebulus {@code wrapWithDirectory}) from already imported
     * entries, sending the directory block to {@code sink}.
     */
    public ImportedNode importDirectory(Map<String, ImportedNode> entries, BlockSink sink) throws IOException {
        List<DagPb.Link> links = new ArrayList<>();
        long linkedSize = 0;
        long fileSize = 0;
        for (Map.Entry<String, ImportedNode> e : entries.entrySet()) {
            links.add(new DagPb.Link(e.getValue().cid(), e.getKey(), e.getValue().dagSize()));
            linkedSize += e.getValue().dagSize();
            fileSize += e.getValue().fileSize();
        }
        byte[] encoded = DagPb.encode(new DagPb.Node(UnixFsData.directory().encode(), DagPb.sortByName(links)));
        Cid cid = Cid.of(Cid.DAG_PB, encoded);
        sink.put(new Block(cid, encoded));
        return new ImportedNode(cid, encoded.length + linkedSize, fileSize);
    }
}
