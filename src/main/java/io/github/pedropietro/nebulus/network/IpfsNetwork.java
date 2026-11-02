package io.github.pedropietro.nebulus.network;

import io.github.pedropietro.nebulus.cid.Cid;
import io.github.pedropietro.nebulus.unixfs.Block;
import io.github.pedropietro.nebulus.unixfs.BlockSource;

import java.io.IOException;

/**
 * The part of IPFS that Nebulus keeps optional: a peer-to-peer node. The default implementation
 * is {@link io.github.pedropietro.nebulus.network.nabu.NabuNetwork}, backed by Nabu.
 */
public interface IpfsNetwork {

    /** Starts the node and joins the network. */
    void start() throws IOException;

    /** Stops the node. */
    void stop() throws IOException;

    /** Stores a block so peers can fetch it over Bitswap. */
    void put(Block block) throws IOException;

    /** Announces in the DHT that this node provides {@code cid}. */
    void provide(Cid cid) throws IOException;

    /** True when the node already stores the block {@code cid} (e.g. after a pull). */
    default boolean has(Cid cid) throws IOException {
        return false;
    }

    /** A source that fetches the blocks of the DAG rooted at {@code root} from the network. */
    BlockSource blocks(Cid root);

    /** This node's peer id, when known. */
    default String peerId() {
        return null;
    }
}
