package io.github.pedropietro.nebulus.network.nabu;

import io.github.pedropietro.nebulus.NebulusOptions;
import io.github.pedropietro.nebulus.cid.Cid;
import io.github.pedropietro.nebulus.network.IpfsNetwork;
import io.github.pedropietro.nebulus.unixfs.Block;
import io.github.pedropietro.nebulus.unixfs.BlockSource;
import io.ipfs.multiaddr.MultiAddress;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;
import org.peergos.BlockRequestAuthoriser;
import org.peergos.EmbeddedIpfs;
import org.peergos.HashedBlock;
import org.peergos.HostBuilder;
import org.peergos.PeerAddresses;
import org.peergos.RamAddressBook;
import org.peergos.Want;
import org.peergos.blockstore.FileBlockstore;
import org.peergos.config.Config;
import org.peergos.config.IdentitySection;
import org.peergos.protocol.dht.RamRecordStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * {@link IpfsNetwork} backed by an embedded <a href="https://github.com/pedropietro/nabu">Nabu</a>
 * node: Bitswap for block exchange, Kademlia DHT for provider records.
 *
 * <p>State lives in {@code <nebulus path>/repo}: blocks in {@code repo/blocks} (Nabu's
 * {@link FileBlockstore}) and the node identity in {@code repo/identity}, so the peer id is
 * stable across restarts.</p>
 */
public class NabuNetwork implements IpfsNetwork {

    private static final Logger LOG = Logger.getLogger(NabuNetwork.class.getName());
    private static final long PROVIDE_TIMEOUT_SECONDS = 120;

    private final Path repo;
    private final List<MultiAddress> swarm;
    private final List<MultiAddress> bootstrap;
    private EmbeddedIpfs ipfs;

    public NabuNetwork(Path repo, NebulusOptions options) {
        this.repo = repo;
        this.swarm = options.swarmAddresses().stream().map(MultiAddress::new).collect(Collectors.toList());
        this.bootstrap = options.bootstrapAddresses()
                .map(list -> list.stream().map(MultiAddress::new).collect(Collectors.toList()))
                .orElse(Config.defaultBootstrapNodes);
    }

    @Override
    public synchronized void start() throws IOException {
        if (ipfs != null) {
            return;
        }
        Files.createDirectories(repo);
        IdentitySection identity = loadOrCreateIdentity(repo.resolve("identity"));
        BlockRequestAuthoriser allowAll = (cid, peer, auth) -> CompletableFuture.completedFuture(true);
        EmbeddedIpfs node = EmbeddedIpfs.build(new RamRecordStore(),
                new FileBlockstore(repo),
                true, // provide every stored block in the DHT
                swarm,
                bootstrap,
                identity,
                allowAll,
                Optional.empty());
        try {
            node.start(false);
        } catch (RuntimeException e) {
            stopQuietly(node);
            throw new IOException("Could not start the Nabu node", e);
        }
        this.ipfs = node;
    }

    @Override
    public synchronized void stop() throws IOException {
        EmbeddedIpfs node = ipfs;
        ipfs = null;
        if (node != null) {
            try {
                node.stop().get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException("Could not stop the Nabu node", e);
            }
        }
    }

    @Override
    public void put(Block block) throws IOException {
        io.ipfs.cid.Cid.Codec codec = toCodec(block.cid().codec());
        io.ipfs.cid.Cid stored = node().blockstore.put(block.data(), codec).join();
        if (!Cid.fromBytes(stored.toBytes()).equals(block.cid())) {
            throw new IOException("Blockstore stored " + stored + " for " + block.cid());
        }
    }

    @Override
    public boolean has(Cid cid) {
        return node().blockstore.has(toNabu(cid)).join();
    }

    @Override
    public void provide(Cid cid) {
        EmbeddedIpfs node = node();
        try {
            node.dht.provideBlock(toNabu(cid), node.node, PeerAddresses.fromHost(node.node))
                    .get(PROVIDE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warning("DHT provide for " + cid + " still running after " + PROVIDE_TIMEOUT_SECONDS + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // The ProvidingBlockstore also announces every block in the background.
            LOG.warning("DHT provide for " + cid + " failed: " + e.getMessage());
        }
    }

    @Override
    public BlockSource blocks(Cid root) {
        return cids -> {
            List<Want> wants = cids.stream().map(c -> new Want(toNabu(c))).collect(Collectors.toList());
            List<HashedBlock> found;
            try {
                // An empty peer set lets Nabu ask connected peers, then DHT providers.
                found = node().getBlocks(wants, Set.of(), true);
            } catch (RuntimeException e) {
                throw new IOException("Could not fetch " + cids.size() + " block(s) under " + root, e);
            }
            Map<Cid, byte[]> result = new HashMap<>();
            for (HashedBlock b : found) {
                result.put(Cid.fromBytes(b.hash.toBytes()), b.block);
            }
            return result;
        };
    }

    @Override
    public String peerId() {
        EmbeddedIpfs node = ipfs;
        return node == null ? null : node.node.getPeerId().toBase58();
    }

    /** The underlying Nabu node, for advanced use (null when stopped). */
    public EmbeddedIpfs embedded() {
        return ipfs;
    }

    private EmbeddedIpfs node() {
        EmbeddedIpfs node = ipfs;
        if (node == null) {
            throw new IllegalStateException("IPFS node is not running; call connect() first");
        }
        return node;
    }

    static io.ipfs.cid.Cid toNabu(Cid cid) {
        return io.ipfs.cid.Cid.cast(cid.toBytes());
    }

    private static io.ipfs.cid.Cid.Codec toCodec(int codec) throws IOException {
        switch (codec) {
            case Cid.RAW:
                return io.ipfs.cid.Cid.Codec.Raw;
            case Cid.DAG_PB:
                return io.ipfs.cid.Cid.Codec.DagProtobuf;
            default:
                throw new IOException("Unsupported codec 0x" + Integer.toHexString(codec));
        }
    }

    /** Reads {@code repo/identity}, generating and saving a new key pair on first use. */
    static IdentitySection loadOrCreateIdentity(Path file) throws IOException {
        if (Files.exists(file)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
            return new IdentitySection(Base64.getDecoder().decode(p.getProperty("PrivKey")),
                    PeerId.fromBase58(p.getProperty("PeerID")));
        }
        HostBuilder builder = new HostBuilder(new RamAddressBook()).generateIdentity();
        PrivKey key = builder.getPrivateKey();
        IdentitySection identity = new IdentitySection(key.bytes(), builder.getPeerId());
        Properties p = new Properties();
        p.setProperty("PeerID", identity.peerId.toBase58());
        p.setProperty("PrivKey", Base64.getEncoder().encodeToString(identity.privKeyProtobuf));
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "Nebulus IPFS node identity - keep private");
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // non-POSIX file system
        }
        return identity;
    }

    private static void stopQuietly(EmbeddedIpfs node) {
        try {
            node.stop().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // best effort
        }
    }
}
