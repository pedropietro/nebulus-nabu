package io.github.pedropietro.nebulus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Push/pull and the event API, against an in-memory network and a fake gateway, so the logic
 * is tested without the public IPFS network. Port of the "ipfs" tests of Nebulus.
 */
public class PushPullTest {

    private InMemoryNetwork network;
    private Path storeA;
    private Path storeB;
    private Nebulus alice;
    private Nebulus bob;

    @Before
    public void setUp() throws IOException {
        network = new InMemoryNetwork();
        storeA = Files.createTempDirectory("nebulus-alice");
        storeB = Files.createTempDirectory("nebulus-bob");
        alice = new Nebulus(options(storeA).build());
        bob = new Nebulus(options(storeB).build());
    }

    private NebulusOptions.Builder options(Path store) throws IOException {
        return NebulusOptions.builder()
                .path(store)
                .network(network.factory())
                .gateway(network.gateway())
                .gatewayPollInterval(Duration.ofMillis(50))
                .pushTimeout(Duration.ofSeconds(20))
                .pullTimeout(Duration.ofSeconds(20));
    }

    @After
    public void tearDown() {
        alice.close();
        bob.close();
        network.close();
        TestData.deleteRecursively(storeA);
        TestData.deleteRecursively(storeB);
    }

    @Test
    public void connectAndDisconnect() throws Exception {
        assertFalse(alice.isConnected());
        alice.connect().get(5, TimeUnit.SECONDS);
        assertTrue(alice.isConnected());
        assertEquals("in-memory", alice.peerId().orElseThrow());
        alice.disconnect().get(5, TimeUnit.SECONDS);
        assertFalse(alice.isConnected());
    }

    @Test
    public void pushesBuffersAndFiresGlobalEvent() throws Exception {
        String cid = alice.add("premium pineapple pomelo seltzer".getBytes(StandardCharsets.UTF_8));
        CompletableFuture<String> event = new CompletableFuture<>();
        alice.on("push", event::complete);
        assertEquals(cid, alice.push(cid).get(10, TimeUnit.SECONDS));
        assertEquals(cid, event.get(5, TimeUnit.SECONDS));
        assertTrue(alice.isConnected()); // push connects on demand, like Nebulus' upload
    }

    @Test
    public void filteredEventsFireOnceForTheirCid() throws Exception {
        String first = alice.add("first".getBytes(StandardCharsets.UTF_8));
        String second = alice.add("second".getBytes(StandardCharsets.UTF_8));
        List<String> seen = new CopyOnWriteArrayList<>();
        alice.on("push:" + second, seen::add);
        alice.push(first).get(10, TimeUnit.SECONDS);
        assertTrue(seen.isEmpty());
        alice.push(second).get(10, TimeUnit.SECONDS);
        alice.push(second).get(10, TimeUnit.SECONDS);
        assertEquals(List.of(second), seen);
    }

    @Test
    public void uploadAndDownloadAliases() throws Exception {
        String cid = alice.add("alias".getBytes(StandardCharsets.UTF_8));
        CompletableFuture<String> upload = new CompletableFuture<>();
        CompletableFuture<String> download = new CompletableFuture<>();
        alice.on("upload", upload::complete);
        bob.on("download", download::complete);
        alice.push(cid).get(10, TimeUnit.SECONDS);
        bob.pull(cid).get(10, TimeUnit.SECONDS);
        assertEquals(cid, upload.get(1, TimeUnit.SECONDS));
        assertEquals(cid, download.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void pullsFilesPushedByAnotherNode() throws Exception {
        byte[] content = TestData.pattern(1_000_000); // multi-block DAG
        String cid = alice.add(content);
        alice.push(cid).get(10, TimeUnit.SECONDS);

        CompletableFuture<String> pulled = new CompletableFuture<>();
        bob.on("pull:" + cid, pulled::complete);
        bob.pull(cid).get(10, TimeUnit.SECONDS);
        assertEquals(cid, pulled.get(1, TimeUnit.SECONDS));
        assertArrayEquals(content, bob.get(cid));
        assertTrue(Files.isSymbolicLink(storeB.resolve("ipfs").resolve(cid)));
    }

    @Test
    public void replicatesFolders() throws Exception {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String name : new String[]{"aperank.png", "readme.md", "index.js", "package.json"}) {
            mapping.put(name, alice.add(TestData.fixture("aperank/" + name)));
        }
        String cid = alice.folder(mapping);
        assertEquals(TestData.APERANK, cid);
        assertEquals(cid, alice.push(cid).get(10, TimeUnit.SECONDS));

        bob.pull(cid).get(10, TimeUnit.SECONDS);
        assertTrue(Files.isDirectory(storeB.resolve("ipfs").resolve(cid)));
        assertEquals(List.of("aperank.png", "index.js", "package.json", "readme.md"), bob.list(cid));
        assertArrayEquals(Files.readAllBytes(TestData.fixture("aperank/aperank.png")), bob.get(cid + "/aperank.png"));
        assertTrue(bob.has(mapping.get("readme.md")));
    }

    @Test
    public void pushWaitsForTheGateway() throws Exception {
        String cid = alice.add("slow".getBytes(StandardCharsets.UTF_8));
        Nebulus impatient = new Nebulus(options(storeA)
                .network((repo, o) -> network.new Node() {
                    @Override
                    public void provide(io.github.pedropietro.nebulus.cid.Cid c) {
                        // never announced: the gateway will not find it
                    }
                })
                .pushTimeout(Duration.ofMillis(500))
                .build());
        try {
            impatient.push(cid).get(10, TimeUnit.SECONDS);
            fail("push completed although the gateway never served the content");
        } catch (ExecutionException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof TimeoutException);
        } finally {
            impatient.close();
        }
    }

    @Test
    public void pushWithoutGatewayCheck() throws Exception {
        Nebulus quick = new Nebulus(options(storeA).verifyOnGateway(false)
                .gateway("http://127.0.0.1:1").build());
        try {
            String cid = quick.add("no gateway".getBytes(StandardCharsets.UTF_8));
            assertEquals(cid, quick.push(cid).get(10, TimeUnit.SECONDS));
            assertTrue(network.provided.contains(io.github.pedropietro.nebulus.cid.Cid.decode(cid)));
        } finally {
            quick.close();
        }
    }

    @Test
    public void pullOfUnknownContentFails() throws Exception {
        try {
            bob.pull(TestData.HELLO_WORLD).get(10, TimeUnit.SECONDS);
            fail("pulled content nobody has");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    @Test
    public void pushOfUnknownCidFails() throws Exception {
        try {
            alice.push(TestData.HELLO_WORLD).get(10, TimeUnit.SECONDS);
            fail("pushed a CID that is not in Nebulus");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
    }
}
