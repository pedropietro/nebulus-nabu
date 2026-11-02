package io.github.pedropietro.nebulus.network;

import io.github.pedropietro.nebulus.NebulusOptions;

import java.nio.file.Path;

/** Creates the {@link IpfsNetwork} a Nebulus instance connects with. */
@FunctionalInterface
public interface NetworkFactory {

    /**
     * @param repo    directory reserved for the node's state ({@code <path>/repo})
     * @param options the Nebulus options (swarm and bootstrap addresses)
     */
    IpfsNetwork create(Path repo, NebulusOptions options);
}
