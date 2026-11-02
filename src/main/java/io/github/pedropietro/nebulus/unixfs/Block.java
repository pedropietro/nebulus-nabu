package io.github.pedropietro.nebulus.unixfs;

import io.github.pedropietro.nebulus.cid.Cid;

/** An encoded IPFS block and its CID. */
public final class Block {

    private final Cid cid;
    private final byte[] data;

    public Block(Cid cid, byte[] data) {
        this.cid = cid;
        this.data = data;
    }

    public Cid cid() {
        return cid;
    }

    public byte[] data() {
        return data;
    }
}
