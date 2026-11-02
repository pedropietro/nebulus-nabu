package io.github.pedropietro.nebulus;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

/**
 * Against the public IPFS network and ipfs.io, like the JavaScript test suite. Slow and
 * dependent on network reachability, so it only runs with {@code -Dnebulus.live=true}.
 */
public class LiveIpfsTest {

    private Path store;
    private Nebulus nebulus;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("set -Dnebulus.live=true to run", Boolean.getBoolean("nebulus.live"));
        store = Files.createTempDirectory("nebulus-live");
        nebulus = new Nebulus(NebulusOptions.builder()
                .path(store)
                .pushTimeout(Duration.ofMinutes(10))
                .pullTimeout(Duration.ofMinutes(5))
                .build());
        nebulus.connect().get(5, TimeUnit.MINUTES);
    }

    @After
    public void tearDown() {
        if (nebulus != null) {
            nebulus.close();
            TestData.deleteRecursively(store);
        }
    }

    @Test
    public void pullsHelloWorld() throws Exception {
        nebulus.pull(TestData.HELLO_WORLD).get(5, TimeUnit.MINUTES);
        assertEquals("hello world", nebulus.get(TestData.HELLO_WORLD, StandardCharsets.UTF_8));
    }

    @Test
    public void pushedContentReachesTheGateway() throws Exception {
        String cid = nebulus.add(("nebulus-nabu " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        assertEquals(cid, nebulus.push(cid).get(10, TimeUnit.MINUTES));
    }
}
