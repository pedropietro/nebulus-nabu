package io.github.pedropietro.nebulus.unixfs;

import io.github.pedropietro.nebulus.cid.Cid;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Fetches blocks by CID (locally or from the network). */
@FunctionalInterface
public interface BlockSource {

    /**
     * Returns the bytes of every requested block. Implementations may fetch them in parallel and
     * must throw if any block cannot be retrieved.
     */
    Map<Cid, byte[]> getAll(List<Cid> cids) throws IOException;
}
