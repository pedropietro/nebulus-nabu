package quickstart;

import io.github.pedropietro.nebulus.Nebulus;
import io.github.pedropietro.nebulus.NebulusOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Quick Start, step 6: the public IPFS network. Pulls a well-known CID, then publishes new
 * content and waits until https://ipfs.io serves it. Needs internet access and can take minutes.
 *
 * <pre>mvn -q compile exec:java -Dexec.mainClass=quickstart.PublicNetwork</pre>
 */
public class PublicNetwork {

    public static void main(String[] args) throws Exception {
        Logger.getGlobal().setLevel(Level.WARNING);

        try (Nebulus nebulus = new Nebulus(NebulusOptions.builder()
                .path("quickstart-public")
                .pushTimeout(Duration.ofMinutes(15))
                .build())) {

            nebulus.connect().join();
            System.out.println("Connected as " + nebulus.peerId().orElseThrow());

            // Fetch content someone else published
            String helloWorld = "bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e";
            nebulus.pull(helloWorld).join();
            System.out.println("Pulled: " + nebulus.get(helloWorld, StandardCharsets.UTF_8));

            // Publish something new; random so the gateway cannot already have it
            String cid = nebulus.add(("hello from nebulus-nabu " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
            nebulus.on("push:" + cid, pushed -> System.out.println("Live at https://ipfs.io/ipfs/" + pushed));
            System.out.println("Pushing " + cid + " ...");
            nebulus.push(cid).join();
        }
        System.exit(0); // some libp2p/netty threads outlive the node
    }
}
