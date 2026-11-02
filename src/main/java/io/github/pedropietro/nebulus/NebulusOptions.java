package io.github.pedropietro.nebulus;

import io.github.pedropietro.nebulus.network.NetworkFactory;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Options for {@link Nebulus}, mirroring the JavaScript constructor options
 * ({@code path}, {@code max}, {@code config}) plus the knobs a Java port needs.
 *
 * <pre>{@code
 * Nebulus nebulus = new Nebulus(NebulusOptions.builder()
 *         .path(Path.of("storage"))
 *         .maxMegabytes(100)
 *         .swarmAddresses(List.of("/ip4/0.0.0.0/tcp/4030"))
 *         .build());
 * }</pre>
 */
public final class NebulusOptions {

    /** Default storage folder, as in Nebulus. */
    public static final Path DEFAULT_PATH = Path.of(".nebulus");
    /** Default libp2p listen address (4002, like js-ipfs, to stay clear of a local Kubo on 4001). */
    public static final List<String> DEFAULT_SWARM = List.of("/ip4/0.0.0.0/tcp/4002");
    /** Gateway polled by {@code push} to confirm the content is reachable. */
    public static final URI DEFAULT_GATEWAY = URI.create("https://ipfs.io");

    private final Path path;
    private final Long maxMegabytes;
    private final boolean symbolicLinks;
    private final List<String> swarmAddresses;
    private final List<String> bootstrapAddresses;
    private final URI gateway;
    private final boolean verifyOnGateway;
    private final Duration gatewayRequestTimeout;
    private final Duration gatewayPollInterval;
    private final Duration pushTimeout;
    private final Duration pullTimeout;
    private final NetworkFactory network;

    private NebulusOptions(Builder b) {
        this.path = b.path;
        this.maxMegabytes = b.maxMegabytes;
        this.symbolicLinks = b.symbolicLinks;
        this.swarmAddresses = b.swarmAddresses;
        this.bootstrapAddresses = b.bootstrapAddresses;
        this.gateway = b.gateway;
        this.verifyOnGateway = b.verifyOnGateway;
        this.gatewayRequestTimeout = b.gatewayRequestTimeout;
        this.gatewayPollInterval = b.gatewayPollInterval;
        this.pushTimeout = b.pushTimeout;
        this.pullTimeout = b.pullTimeout;
        this.network = b.network;
    }

    public static NebulusOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Storage folder ({@code .nebulus} by default). */
    public Path path() {
        return path;
    }

    /** Maximum size accepted by {@code download}, in MB (10^6 bytes); empty means unlimited. */
    public Optional<Long> maxMegabytes() {
        return Optional.ofNullable(maxMegabytes);
    }

    /**
     * When true (default), {@code ipfs/<cid>} entries are symbolic links to the content; when
     * false, or where the file system refuses links (Windows without developer mode), they are
     * copies, which makes the storage folder self-contained.
     */
    public boolean symbolicLinks() {
        return symbolicLinks;
    }

    /** libp2p listen addresses for the IPFS node. */
    public List<String> swarmAddresses() {
        return swarmAddresses;
    }

    /** Bootstrap peers; empty means Nabu's default public bootstrap list. */
    public Optional<List<String>> bootstrapAddresses() {
        return Optional.ofNullable(bootstrapAddresses);
    }

    public URI gateway() {
        return gateway;
    }

    /** When true (default), {@code push} completes only once the gateway serves the content. */
    public boolean verifyOnGateway() {
        return verifyOnGateway;
    }

    public Duration gatewayRequestTimeout() {
        return gatewayRequestTimeout;
    }

    public Duration gatewayPollInterval() {
        return gatewayPollInterval;
    }

    /** Upper bound for a {@code push}; {@link Duration#ZERO} waits forever, like Nebulus. */
    public Duration pushTimeout() {
        return pushTimeout;
    }

    /** Upper bound for a {@code pull}; {@link Duration#ZERO} waits forever. */
    public Duration pullTimeout() {
        return pullTimeout;
    }

    /** Factory for the IPFS node; empty means {@link io.github.pedropietro.nebulus.network.nabu.NabuNetwork}. */
    public Optional<NetworkFactory> network() {
        return Optional.ofNullable(network);
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.path = path;
        b.maxMegabytes = maxMegabytes;
        b.symbolicLinks = symbolicLinks;
        b.swarmAddresses = swarmAddresses;
        b.bootstrapAddresses = bootstrapAddresses;
        b.gateway = gateway;
        b.verifyOnGateway = verifyOnGateway;
        b.gatewayRequestTimeout = gatewayRequestTimeout;
        b.gatewayPollInterval = gatewayPollInterval;
        b.pushTimeout = pushTimeout;
        b.pullTimeout = pullTimeout;
        b.network = network;
        return b;
    }

    public static final class Builder {
        private Path path = DEFAULT_PATH;
        private Long maxMegabytes;
        private boolean symbolicLinks = true;
        private List<String> swarmAddresses = DEFAULT_SWARM;
        private List<String> bootstrapAddresses;
        private URI gateway = DEFAULT_GATEWAY;
        private boolean verifyOnGateway = true;
        private Duration gatewayRequestTimeout = Duration.ofSeconds(10);
        private Duration gatewayPollInterval = Duration.ofSeconds(1);
        private Duration pushTimeout = Duration.ofMinutes(10);
        private Duration pullTimeout = Duration.ofMinutes(10);
        private NetworkFactory network;

        private Builder() {
        }

        public Builder path(Path path) {
            this.path = Objects.requireNonNull(path, "path");
            return this;
        }

        public Builder path(String path) {
            return path(Path.of(path));
        }

        public Builder maxMegabytes(long max) {
            if (max <= 0) {
                throw new IllegalArgumentException("max must be positive");
            }
            this.maxMegabytes = max;
            return this;
        }

        public Builder symbolicLinks(boolean useSymbolicLinks) {
            this.symbolicLinks = useSymbolicLinks;
            return this;
        }

        public Builder swarmAddresses(List<String> addresses) {
            this.swarmAddresses = List.copyOf(addresses);
            return this;
        }

        public Builder bootstrapAddresses(List<String> addresses) {
            this.bootstrapAddresses = List.copyOf(addresses);
            return this;
        }

        public Builder gateway(URI gateway) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
            return this;
        }

        public Builder gateway(String gateway) {
            return gateway(URI.create(gateway));
        }

        public Builder verifyOnGateway(boolean verify) {
            this.verifyOnGateway = verify;
            return this;
        }

        public Builder gatewayRequestTimeout(Duration timeout) {
            this.gatewayRequestTimeout = positive(timeout, "gatewayRequestTimeout");
            return this;
        }

        public Builder gatewayPollInterval(Duration interval) {
            this.gatewayPollInterval = positive(interval, "gatewayPollInterval");
            return this;
        }

        public Builder pushTimeout(Duration timeout) {
            this.pushTimeout = nonNegative(timeout, "pushTimeout");
            return this;
        }

        public Builder pullTimeout(Duration timeout) {
            this.pullTimeout = nonNegative(timeout, "pullTimeout");
            return this;
        }

        public Builder network(NetworkFactory factory) {
            this.network = factory;
            return this;
        }

        public NebulusOptions build() {
            return new NebulusOptions(this);
        }

        private static Duration positive(Duration d, String name) {
            if (d == null || d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return d;
        }

        private static Duration nonNegative(Duration d, String name) {
            if (d == null || d.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return d;
        }
    }
}
