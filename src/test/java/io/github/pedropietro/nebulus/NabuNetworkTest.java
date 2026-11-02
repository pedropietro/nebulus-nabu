package io.github.pedropietro.nebulus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Two real Nabu nodes on localhost: Alice pushes, Bob (bootstrapped to Alice) pulls over Bitswap.
 * No public network is involved.
 */
public class NabuNetworkTest {

    private Path storeA;
    private Path storeB;
    private Nebulus alice;
    private Nebulus bob;
    private int portA;

    @Before
    public void setUp() throws Exception {
        storeA = Files.createTempDirectory("nabu-alice");
        storeB = Files.createTempDirectory("nabu-bob");
        portA = freePort();
        alice = new Nebulus(NebulusOptions.builder()
                .path(storeA)
                .swarmAddresses(List.of("/ip4/127.0.0.1/tcp/" + portA))
                .bootstrapAddresses(List.of())
                .verifyOnGateway(false)
                .build());
        alice.connect().get(60, TimeUnit.SECONDS);
        String aliceAddr = "/ip4/127.0.0.1/tcp/" + portA + "/p2p/" + alice.peerId().orElseThrow();
        bob = new Nebulus(NebulusOptions.builder()
                .path(storeB)
                .swarmAddresses(List.of("/ip4/127.0.0.1/tcp/" + freePort()))
                .bootstrapAddresses(List.of(aliceAddr))
                .verifyOnGateway(false)
                .pullTimeout(Duration.ofSeconds(90))
                .build());
        bob.connect().get(60, TimeUnit.SECONDS);
    }

    @After
    public void tearDown() {
        if (alice != null) {
            alice.close();
        }
        if (bob != null) {
            bob.close();
        }
        TestData.deleteRecursively(storeA);
        TestData.deleteRecursively(storeB);
    }

    @Test
    public void pushedFileCanBePulledByAnotherNode() throws Exception {
        byte[] content = new byte[700_000]; // 3 chunks + a parent node
        new Random(7).nextBytes(content);
        String cid = alice.add(content);
        assertEquals(cid, alice.push(cid).get(150, TimeUnit.SECONDS));

        CompletableFuture<String> event = new CompletableFuture<>();
        bob.on("pull:" + cid, event::complete);
        assertEquals(cid, bob.pull(cid).get(120, TimeUnit.SECONDS));
        assertEquals(cid, event.get(5, TimeUnit.SECONDS));
        assertArrayEquals(content, bob.get(cid));
    }

    @Test
    public void pushedFolderCanBePulledByAnotherNode() throws Exception {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String name : new String[]{"aperank.png", "readme.md", "index.js", "package.json"}) {
            mapping.put(name, alice.add(TestData.fixture("aperank/" + name)));
        }
        String cid = alice.folder(mapping);
        assertEquals(TestData.APERANK, cid);
        alice.push(cid).get(150, TimeUnit.SECONDS);

        bob.pull(cid).get(120, TimeUnit.SECONDS);
        assertEquals(List.of("aperank.png", "index.js", "package.json", "readme.md"), bob.list(cid));
        assertArrayEquals(Files.readAllBytes(TestData.fixture("aperank/readme.md")), bob.get(cid + "/readme.md"));
    }

    @Test
    public void identityIsStableAcrossRestarts() throws Exception {
        String before = alice.peerId().orElseThrow();
        alice.disconnect().get(30, TimeUnit.SECONDS);
        alice.connect().get(60, TimeUnit.SECONDS);
        assertEquals(before, alice.peerId().orElseThrow());
        assertTrue(Files.exists(storeA.resolve("repo").resolve("identity")));
        assertTrue(Files.isDirectory(storeA.resolve("repo").resolve("blocks")));
    }

    @Test
    public void pushStoresBlocksInTheNabuBlockstore() throws Exception {
        String cid = alice.add("stored in nabu".getBytes(StandardCharsets.UTF_8));
        alice.push(cid).get(150, TimeUnit.SECONDS);
        try (var blocks = Files.walk(storeA.resolve("repo").resolve("blocks"))) {
            assertTrue(blocks.anyMatch(p -> p.toString().endsWith(".data")));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
