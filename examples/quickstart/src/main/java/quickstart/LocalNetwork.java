package quickstart;

import io.github.pedropietro.nebulus.Nebulus;
import io.github.pedropietro.nebulus.NebulusOptions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Quick Start, step 5: two Nabu nodes on this machine. Alice pushes a file, Bob pulls it over
 * Bitswap. Nothing reaches the public network: Alice has no bootstrap peers and Bob only knows
 * Alice.
 *
 * <pre>mvn -q compile exec:java -Dexec.mainClass=quickstart.LocalNetwork</pre>
 */
public class LocalNetwork {

    public static void main(String[] args) throws Exception {
        Logger.getGlobal().setLevel(Level.WARNING); // Nabu logs to the global logger

        Nebulus alice = new Nebulus(NebulusOptions.builder()
                .path("quickstart-alice")
                .swarmAddresses(List.of("/ip4/127.0.0.1/tcp/4101"))
                .bootstrapAddresses(List.of())   // stay off the public network
                .verifyOnGateway(false)          // no public gateway to wait for
                .build());
        alice.connect().join();
        String aliceAddress = "/ip4/127.0.0.1/tcp/4101/p2p/" + alice.peerId().orElseThrow();
        System.out.println("Alice is " + aliceAddress);

        Nebulus bob = new Nebulus(NebulusOptions.builder()
                .path("quickstart-bob")
                .swarmAddresses(List.of("/ip4/127.0.0.1/tcp/4102"))
                .bootstrapAddresses(List.of(aliceAddress))
                .verifyOnGateway(false)
                .build());

        try (alice; bob) {
            String cid = alice.add("a private hello from Alice".getBytes(StandardCharsets.UTF_8));
            alice.push(cid).join();
            System.out.println("Alice pushed " + cid);

            bob.on("pull", pulled -> System.out.println("Bob pulled  " + pulled));
            bob.pull(cid).join();
            System.out.println("Bob reads:   " + bob.get(cid, StandardCharsets.UTF_8));
        }
        System.exit(0); // some libp2p/netty threads outlive the nodes
    }
}
