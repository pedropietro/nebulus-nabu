package io.github.pedropietro.nebulus;

import io.github.pedropietro.nebulus.cid.Cid;
import io.github.pedropietro.nebulus.network.IpfsNetwork;
import io.github.pedropietro.nebulus.network.NetworkFactory;
import io.github.pedropietro.nebulus.network.nabu.NabuNetwork;
import io.github.pedropietro.nebulus.unixfs.BlockSink;
import io.github.pedropietro.nebulus.unixfs.Exporter;
import io.github.pedropietro.nebulus.unixfs.ImportedNode;
import io.github.pedropietro.nebulus.unixfs.Importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Nebulus — "virtual IPFS" — ported to Java on top of <a href="https://github.com/pedropietro/nabu">Nabu</a>.
 *
 * <p>Files get their IPFS CID locally, without a node and without publishing anything: the
 * {@code ipfs/} folder holds one symbolic link per CID pointing at the real file. Connecting to
 * the public network is optional and explicit: {@link #push} publishes a CID, {@link #pull}
 * fetches one.</p>
 *
 * <pre>{@code
 * try (Nebulus nebulus = new Nebulus()) {
 *     String cid = nebulus.add("hello world".getBytes());   // private, offline
 *     nebulus.on("push", c -> System.out.println("https://ipfs.io/ipfs/" + c));
 *     nebulus.push(cid).join();                            // now public
 * }
 * }</pre>
 *
 * <p>Writing and reading are synchronous; network operations return {@link CompletableFuture}s
 * and also fire the {@code push}/{@code pull} events of the original API.</p>
 */
public class Nebulus implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Nebulus.class.getName());

    /** Fired after {@link #push}; filtered form {@code "push:<cid>"}. Alias: {@code "upload"}. */
    public static final String PUSH = "push";
    /** Fired after {@link #pull}; filtered form {@code "pull:<cid>"}. Alias: {@code "download"}. */
    public static final String PULL = "pull";

    private final NebulusOptions options;
    private final Storage storage;
    private final Events events = new Events();
    private final Importer importer = new Importer();
    private final HttpClient http;
    private final ExecutorService executor;
    private final Object networkLock = new Object();
    private volatile IpfsNetwork network;

    /** Uses {@code .nebulus} in the working directory, with no size limit. */
    public Nebulus() {
        this(NebulusOptions.defaults());
    }

    public Nebulus(NebulusOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        try {
            this.storage = new Storage(options.path());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create Nebulus storage at " + options.path(), e);
        }
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        AtomicInteger threads = new AtomicInteger();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "nebulus-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** The storage folder. */
    public Path path() {
        return storage.root();
    }

    // ------------------------------------------------------------------ writing

    /** Adds a buffer: it is stored as {@code src/<sha256>} and linked as {@code ipfs/<cid>}. */
    public String add(byte[] buffer) throws IOException {
        return add(storage.storeBytes(Objects.requireNonNull(buffer, "buffer")));
    }

    /**
     * Adds a local file without copying it: {@code ipfs/<cid>} becomes a symbolic link to it,
     * so the file must stay where it is.
     */
    public String add(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toString(), null, "not a regular file");
        }
        ImportedNode node;
        try (InputStream in = Files.newInputStream(file)) {
            node = importer.importFile(in, BlockSink.HASH_ONLY);
        }
        String cid = node.cid().toString();
        storage.link(storage.resolve(cid), file);
        return cid;
    }

    /** Adds a file path, or downloads it first when it is an {@code http(s)} URL. */
    public String add(String pathOrUrl) throws IOException {
        if (isUrl(pathOrUrl)) {
            return download(pathOrUrl);
        }
        return add(Path.of(pathOrUrl));
    }

    /**
     * Downloads a web file into {@code src/} and adds it. Fails with
     * {@link FileTooLargeException} beyond {@link NebulusOptions#maxMegabytes()}.
     */
    public String download(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> res;
        try {
            res = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }
        try (InputStream body = res.body()) {
            if (res.statusCode() / 100 != 2) {
                throw new IOException("Download failed with HTTP " + res.statusCode() + ": " + url);
            }
            long max = options.maxMegabytes().map(mb -> mb * 1_000_000L).orElse(0L);
            OptionalLong length = res.headers().firstValueAsLong("content-length");
            if (max > 0 && length.isPresent() && length.getAsLong() > max) {
                throw new FileTooLargeException(length.getAsLong(), max);
            }
            return add(storage.storeStream(body, max));
        }
    }

    /**
     * Creates a flat folder from files already in Nebulus and returns its CID.
     *
     * @param mapping file name to CID, e.g. {@code Map.of("readme.md", cid1, "index.js", cid2)}
     */
    public String folder(Map<String, String> mapping) throws IOException {
        Map<String, ImportedNode> entries = new LinkedHashMap<>();
        Map<String, Path> targets = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            String name = validName(e.getKey());
            Path target = storage.resolve(e.getValue());
            if (!Files.isRegularFile(target)) {
                throw new NoSuchFileException(target.toString(), null, "unknown CID " + e.getValue());
            }
            try (InputStream in = Files.newInputStream(target)) {
                entries.put(name, importer.importFile(in, BlockSink.HASH_ONLY));
            }
            targets.put(name, target);
        }
        String root = importer.importDirectory(entries, BlockSink.HASH_ONLY).cid().toString();
        for (Map.Entry<String, Path> e : targets.entrySet()) {
            storage.link(storage.resolve(root + "/" + e.getKey()), e.getValue());
        }
        return root;
    }

    // ------------------------------------------------------------------ reading

    /** Reads the content of a CID (or {@code <folderCid>/<name>}). */
    public byte[] get(String cid) throws IOException {
        return Files.readAllBytes(file(cid));
    }

    /** Reads the content of a CID as text. */
    public String get(String cid, Charset charset) throws IOException {
        return new String(get(cid), charset);
    }

    /** Opens the content of a CID as a stream. */
    public InputStream stream(String cid) throws IOException {
        return Files.newInputStream(file(cid));
    }

    /** Lists the entry names of a folder CID. */
    public List<String> list(String cid) throws IOException {
        Path dir = storage.resolve(cid);
        if (!Files.isDirectory(dir)) {
            throw new IOException(cid + " is not a folder");
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    /** True when the CID is available locally. */
    public boolean has(String cid) {
        try {
            return Files.exists(storage.resolve(cid));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Path file(String cid) throws IOException {
        Path p = storage.resolve(cid);
        if (!Files.exists(p)) {
            throw new NoSuchFileException(p.toString(), null, "unknown CID " + cid);
        }
        if (Files.isDirectory(p)) {
            throw new IOException(cid + " is a folder; read its entries as " + cid + "/<name>");
        }
        return p;
    }

    // ------------------------------------------------------------------ events

    /**
     * Listens to {@code "push"} / {@code "pull"} for every CID, or to {@code "push:<cid>"} /
     * {@code "pull:<cid>"} once for a single CID. Listeners run on a Nebulus worker thread.
     */
    public Nebulus on(String event, Consumer<String> listener) {
        events.on(Objects.requireNonNull(event, "event"), Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** Removes a listener added with {@link #on}. */
    public boolean off(String event, Consumer<String> listener) {
        return events.off(event, listener);
    }

    // ------------------------------------------------------------------ IPFS network

    /** Starts the IPFS node (Nabu) and joins the network. */
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureConnected();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /** Stops the IPFS node. Local files stay available. */
    public CompletableFuture<Void> disconnect() {
        return CompletableFuture.runAsync(() -> {
            try {
                stopNetwork();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    public boolean isConnected() {
        return network != null;
    }

    /** The node's peer id once connected. */
    public Optional<String> peerId() {
        IpfsNetwork n = network;
        return n == null ? Optional.empty() : Optional.ofNullable(n.peerId());
    }

    /**
     * Publishes a local CID (file or folder) to the public IPFS network, connecting first if
     * needed. Unless {@link NebulusOptions#verifyOnGateway()} is off, it completes, and fires
     * {@code push}, once the gateway serves the content.
     */
    public CompletableFuture<String> push(String cid) {
        return withTimeout(CompletableFuture.supplyAsync(() -> {
            try {
                return doPush(cid);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor), options.pushTimeout());
    }

    /**
     * Fetches a CID (file or flat folder) from the IPFS network into Nebulus, connecting first if
     * needed, and fires {@code pull} when done.
     */
    public CompletableFuture<String> pull(String cid) {
        return withTimeout(CompletableFuture.supplyAsync(() -> {
            try {
                return doPull(cid);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor), options.pullTimeout());
    }

    private String doPush(String cid) throws Exception {
        long deadline = options.pushTimeout().isZero() ? 0 : System.nanoTime() + options.pushTimeout().toNanos();
        IpfsNetwork net = ensureConnected();
        Cid root = Cid.decode(cid);
        Path local = storage.resolve(root.toString());
        if (!Files.exists(local)) {
            throw new NoSuchFileException(local.toString(), null, "unknown CID " + cid);
        }
        BlockSink sink = net::put;
        List<String> paths = new ArrayList<>();
        ImportedNode node;
        if (Files.isDirectory(local)) {
            Map<String, ImportedNode> entries = new LinkedHashMap<>();
            for (String name : list(root.toString())) {
                Path child = local.resolve(name);
                if (!Files.isRegularFile(child)) {
                    throw new IOException("Nested folders are not supported: " + cid + "/" + name);
                }
                try (InputStream in = Files.newInputStream(child)) {
                    entries.put(name, importer.importFile(in, sink));
                }
                paths.add(root + "/" + name);
            }
            node = importer.importDirectory(entries, sink);
        } else {
            try (InputStream in = Files.newInputStream(local)) {
                node = importer.importFile(in, sink);
            }
            paths.add(root.toString());
        }
        if (!node.cid().equals(root) && !net.has(root)) {
            throw new IOException("Local content of " + cid + " hashes to " + node.cid());
        }
        LOG.info("Providing " + root);
        net.provide(root);
        if (options.verifyOnGateway()) {
            GatewayChecker checker = new GatewayChecker(http, options.gateway(),
                    options.gatewayRequestTimeout(), options.gatewayPollInterval());
            for (String p : paths) {
                checker.await(p, deadline);
            }
        }
        events.trigger(List.of("upload", PUSH), cid);
        return cid;
    }

    private String doPull(String cid) throws IOException {
        IpfsNetwork net = ensureConnected();
        Cid root = Cid.decode(cid);
        Exporter exporter = new Exporter(net.blocks(root));
        Exporter.Entry entry = exporter.resolve(root);
        if (entry.isDirectory()) {
            for (Map.Entry<String, Cid> child : entry.children().entrySet()) {
                String name = validName(child.getKey());
                Exporter.Entry file = exporter.resolve(child.getValue());
                if (file.isDirectory()) {
                    throw new IOException("Nested folders are not supported: " + cid + "/" + name);
                }
                Path childLink = savePulled(exporter, file);
                storage.link(storage.resolve(root + "/" + name), childLink);
            }
            Files.createDirectories(storage.resolve(root.toString()));
        } else {
            savePulled(exporter, entry);
        }
        events.trigger(List.of("download", PULL), cid);
        return cid;
    }

    /** Stores a pulled file in {@code src/} and links it under its CID; returns the link. */
    private Path savePulled(Exporter exporter, Exporter.Entry file) throws IOException {
        Path content = storage.storeWith(out -> exporter.exportFile(file, out));
        Path link = storage.resolve(file.cid().toString());
        storage.link(link, content);
        // Content imported by other tools (CIDv0, other chunking) gets a second link under the
        // CID Nebulus computes for it, as the JavaScript version does.
        add(content);
        return link;
    }

    private IpfsNetwork ensureConnected() throws IOException {
        IpfsNetwork n = network;
        if (n != null) {
            return n;
        }
        synchronized (networkLock) {
            if (network == null) {
                NetworkFactory factory = options.network().orElse(NabuNetwork::new);
                IpfsNetwork created = factory.create(storage.repoDir(), options);
                created.start();
                network = created;
                LOG.info("IPFS node started" + (created.peerId() == null ? "" : ": " + created.peerId()));
            }
            return network;
        }
    }

    private void stopNetwork() throws IOException {
        synchronized (networkLock) {
            IpfsNetwork n = network;
            network = null;
            if (n != null) {
                n.stop();
                LOG.info("IPFS node stopped");
            }
        }
    }

    /** Disconnects and releases worker threads. */
    @Override
    public void close() {
        try {
            stopNetwork();
        } catch (IOException e) {
            LOG.warning("Error while stopping IPFS: " + e.getMessage());
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> f, Duration timeout) {
        return timeout.isZero() ? f : f.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static boolean isUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static String validName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid file name in folder: '" + name
                    + "' (nested folders are not supported)");
        }
        return name;
    }
}
