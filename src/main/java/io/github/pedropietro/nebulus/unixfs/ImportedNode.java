package io.github.pedropietro.nebulus.unixfs;

import io.github.pedropietro.nebulus.cid.Cid;

/**
 * Result of importing a file or directory.
 *
 * <p>{@code dagSize} is the cumulative size of the encoded DAG (the value parents store in
 * a link's {@code Tsize}); {@code fileSize} is the number of content bytes.</p>
 */
public final class ImportedNode {

    private final Cid cid;
    private final long dagSize;
    private final long fileSize;

    public ImportedNode(Cid cid, long dagSize, long fileSize) {
        this.cid = cid;
        this.dagSize = dagSize;
        this.fileSize = fileSize;
    }

    public Cid cid() {
        return cid;
    }

    public long dagSize() {
        return dagSize;
    }

    public long fileSize() {
        return fileSize;
    }

    @Override
    public String toString() {
        return cid.toString();
    }
}
