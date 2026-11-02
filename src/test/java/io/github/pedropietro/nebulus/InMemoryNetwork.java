package io.github.pedropietro.nebulus;

import com.sun.net.httpserver.HttpServer;
import io.github.pedropietro.nebulus.cid.Cid;
import io.github.pedropietro.nebulus.network.IpfsNetwork;
import io.github.pedropietro.nebulus.network.NetworkFactory;
import io.github.pedropietro.nebulus.unixfs.Block;
import io.github.pedropietro.nebulus.unixfs.BlockSource;
import io.github.pedropietro.nebulus.unixfs.Exporter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A shared, in-process "IPFS network" for tests: blocks put by one Nebulus can be fetched by
 * another once provided. {@link #gateway()} serves provided content like ipfs.io would.
 */
final class InMemoryNetwork implements AutoCloseable {

    final Map<Cid, byte[]> blocks = new ConcurrentHashMap<>();
    final Set<Cid> provided = ConcurrentHashMap.newKeySet();
    private HttpServer server;

    NetworkFactory factory() {
        return (repo, options) -> new Node();
    }

    /** A fake gateway: 200 for provided CIDs (and paths below them), 504 otherwise. */
    synchronized URI gateway() throws IOException {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/ipfs/", ex -> {
                String[] parts = ex.getRequestURI().getPath().substring("/ipfs/".length()).split("/", 2);
                int status = 504;
                byte[] body = new byte[0];
                try {
                    Cid root = Cid.decode(parts[0]);
                    if (provided.contains(root)) {
                        Exporter exporter = new Exporter(source());
                        Exporter.Entry entry = exporter.resolve(root);
                        Cid target = parts.length > 1 ? entry.children().get(parts[1]) : root;
                        if (target != null) {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            Exporter.Entry t = exporter.resolve(target);
                            if (!t.isDirectory()) {
                                exporter.exportFile(t, out);
                            }
                            body = out.toByteArray();
                            status = 200;
                        }
                    }
                } catch (RuntimeException | IOException e) {
                    status = 500;
                }
                ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
        }
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    BlockSource source() {
        return cids -> {
            Map<Cid, byte[]> found = new HashMap<>();
            for (Cid c : cids) {
                byte[] b = blocks.get(c);
                if (b != null) {
                    found.put(c, b);
                }
            }
            return found;
        };
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    class Node implements IpfsNetwork {
        volatile boolean running;

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public void put(Block block) {
            check();
            blocks.put(block.cid(), block.data());
        }

        @Override
        public boolean has(Cid cid) {
            return blocks.containsKey(cid);
        }

        @Override
        public void provide(Cid cid) {
            check();
            provided.add(cid);
        }

        @Override
        public BlockSource blocks(Cid root) {
            return cids -> {
                check();
                return source().getAll(List.copyOf(cids));
            };
        }

        @Override
        public String peerId() {
            return "in-memory";
        }

        private void check() {
            if (!running) {
                throw new IllegalStateException("not running");
            }
        }
    }
}
