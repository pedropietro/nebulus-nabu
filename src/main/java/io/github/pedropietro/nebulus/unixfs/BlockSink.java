package io.github.pedropietro.nebulus.unixfs;

import java.io.IOException;

/** Receives every block produced by the {@link Importer}. */
@FunctionalInterface
public interface BlockSink {

    /** A sink that discards blocks, for computing CIDs only (Nebulus' {@code onlyHash}). */
    BlockSink HASH_ONLY = block -> { };

    void put(Block block) throws IOException;
}
