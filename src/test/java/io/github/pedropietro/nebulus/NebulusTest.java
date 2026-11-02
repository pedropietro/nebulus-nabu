package io.github.pedropietro.nebulus;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The offline API: add, download, folder, get, stream. Port of the "writing" tests of Nebulus.
 * Runs twice: with symbolic links (copies where the OS refuses them) and with copies only.
 */
@RunWith(Parameterized.class)
public class NebulusTest {

    @Parameterized.Parameters(name = "symbolicLinks={0}")
    public static List<Object[]> modes() {
        return List.of(new Object[]{true}, new Object[]{false});
    }

    @Parameterized.Parameter
    public boolean symbolicLinks;

    private static HttpServer web;
    private static String base;
    private static final AtomicInteger counter = new AtomicInteger();

    private Path store;
    private Nebulus nebulus;

    @BeforeClass
    public static void startWebServer() throws IOException {
        web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        web.createContext("/hello", ex -> respond(ex, "hello world".getBytes(StandardCharsets.UTF_8), true));
        // different content on every request, like thisartworkdoesnotexist.com
        web.createContext("/random", ex -> respond(ex, ("art #" + counter.incrementAndGet()).getBytes(StandardCharsets.UTF_8), true));
        web.createContext("/big", ex -> respond(ex, new byte[3_000_000], true));
        web.createContext("/big-chunked", ex -> respond(ex, new byte[3_000_000], false));
        web.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        for (String name : new String[]{"readme.md", "index.js", "package.json", "aperank.png"}) {
            byte[] content = Files.readAllBytes(TestData.fixture("aperank/" + name));
            web.createContext("/aperank/" + name, ex -> respond(ex, content, true));
        }
        web.start();
        base = "http://127.0.0.1:" + web.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, byte[] body, boolean withLength) throws IOException {
        ex.sendResponseHeaders(200, withLength ? body.length : 0);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    @AfterClass
    public static void stopWebServer() {
        web.stop(0);
    }

    @Before
    public void setUp() throws IOException {
        store = Files.createTempDirectory("nebulus-test");
        nebulus = new Nebulus(NebulusOptions.builder().path(store).symbolicLinks(symbolicLinks).build());
    }

    @After
    public void tearDown() {
        nebulus.close();
        TestData.deleteRecursively(store);
    }

    @Test
    public void createsTheStorageLayout() {
        assertTrue(Files.isDirectory(store.resolve("src")));
        assertTrue(Files.isDirectory(store.resolve("ipfs")));
        assertEquals(store, nebulus.path());
    }

    @Test
    public void addsBuffers() throws IOException {
        String cid = nebulus.add("hello world".getBytes(StandardCharsets.UTF_8));
        assertEquals(TestData.HELLO_WORLD, cid);
        Path link = store.resolve("ipfs").resolve(cid);
        TestData.assertStoredEntry(nebulus, link);
        // the buffer is stored once, under its sha256
        Path stored = store.resolve("src").resolve("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
        assertTrue(Files.isRegularFile(stored));
        if (nebulus.usesSymbolicLinks()) {
            assertEquals(stored.normalize(), link.getParent().resolve(Files.readSymbolicLink(link)).normalize());
        }
        assertEquals("hello world", nebulus.get(cid, StandardCharsets.UTF_8));
        assertEquals(cid, nebulus.add("hello world".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void addsFilePathsAsRelativeLinks() throws IOException {
        Path file = store.resolve("hello.txt");
        Files.writeString(file, "hello world");
        String cid = nebulus.add(file);
        assertEquals(TestData.HELLO_WORLD, cid);
        assertEquals(cid, nebulus.add(file.toString()));
        Path link = store.resolve("ipfs").resolve(cid);
        TestData.assertStoredEntry(nebulus, link);
        if (nebulus.usesSymbolicLinks()) {
            assertFalse("same drive: link must be relative", Files.readSymbolicLink(link).isAbsolute());
        }
    }

    @Test
    public void relinksWhenTheOriginalFileWasRemoved() throws IOException {
        Path first = store.resolve("first.txt");
        Files.writeString(first, "hello world");
        nebulus.add(first);
        Files.delete(first);
        // a link breaks with its target; a copy keeps the content
        assertEquals(!nebulus.usesSymbolicLinks(), nebulus.has(TestData.HELLO_WORLD));
        Path second = store.resolve("second.txt");
        Files.writeString(second, "hello world");
        nebulus.add(second);
        assertEquals("hello world", nebulus.get(TestData.HELLO_WORLD, StandardCharsets.UTF_8));
    }

    @Test
    public void storedEntriesStayDeletableForReadOnlySources() throws IOException {
        Path file = store.resolve("readonly.bin");
        Files.writeString(file, "like a Git pack file");
        assertTrue(file.toFile().setReadOnly());
        try {
            String cid = nebulus.add(file);
            Path entry = store.resolve("ipfs").resolve(cid);
            if (!nebulus.usesSymbolicLinks()) {
                assertTrue("copies made by Nebulus must be writable", Files.isWritable(entry));
            }
            Files.delete(entry); // a link, or Nebulus' own writable copy
        } finally {
            file.toFile().setWritable(true);
        }
    }

    @Test
    public void downloadsWebFiles() throws IOException {
        assertEquals(TestData.HELLO_WORLD, nebulus.download(base + "/hello"));
        assertEquals(TestData.HELLO_WORLD, nebulus.add(base + "/hello"));
    }

    @Test
    public void downloadsDifferentContentFromTheSameUrlAsDifferentFiles() throws IOException {
        String cid0 = nebulus.download(base + "/random");
        String cid1 = nebulus.download(base + "/random");
        assertNotEquals(cid0, cid1);
    }

    @Test
    public void rejectsFilesLargerThanMax() throws IOException {
        Nebulus limited = new Nebulus(NebulusOptions.builder().path(store).maxMegabytes(1).build());
        try {
            for (String path : List.of("/big", "/big-chunked")) {
                try {
                    limited.download(base + path);
                    fail("accepted " + path);
                } catch (FileTooLargeException e) {
                    assertTrue(e.getMessage().startsWith("file size too large"));
                    assertEquals(1_000_000, e.maxBytes());
                }
            }
            assertEquals(TestData.HELLO_WORLD, limited.download(base + "/hello"));
            try (var files = Files.list(store.resolve("src"))) {
                assertEquals("no temp files left behind", 1, files.count());
            }
        } finally {
            limited.close();
        }
    }

    @Test(expected = IOException.class)
    public void failsOnHttpErrors() throws IOException {
        nebulus.download(base + "/missing");
    }

    @Test
    public void createsFolders() throws IOException {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("readme.md", nebulus.add(base + "/aperank/readme.md"));
        mapping.put("index.js", nebulus.add(base + "/aperank/index.js"));
        mapping.put("package.json", nebulus.add(base + "/aperank/package.json"));
        mapping.put("aperank.png", nebulus.add(base + "/aperank/aperank.png"));
        String cid = nebulus.folder(mapping);
        assertEquals(TestData.APERANK, cid);
        assertEquals(List.of("aperank.png", "index.js", "package.json", "readme.md"), nebulus.list(cid));
        assertArrayEquals(Files.readAllBytes(TestData.fixture("aperank/readme.md")), nebulus.get(cid + "/readme.md"));
        TestData.assertStoredEntry(nebulus, store.resolve("ipfs").resolve(cid).resolve("index.js"));
    }

    @Test
    public void createsFoldersFromLocalFiles() throws IOException {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String name : new String[]{"aperank.png", "readme.md", "index.js", "package.json"}) {
            mapping.put(name, nebulus.add(TestData.fixture("aperank/" + name)));
        }
        assertEquals(TestData.APERANK, nebulus.folder(mapping));
    }

    @Test
    public void rejectsNestedOrUnsafeFolderNames() throws IOException {
        String cid = nebulus.add("x".getBytes(StandardCharsets.UTF_8));
        for (String name : new String[]{"a/b", "..", ".", "", "..\\x"}) {
            try {
                nebulus.folder(Map.of(name, cid));
                fail("accepted '" + name + "'");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test(expected = NoSuchFileException.class)
    public void folderNeedsKnownCids() throws IOException {
        nebulus.folder(Map.of("a.txt", TestData.HELLO_WORLD));
    }

    @Test
    public void streamsContent() throws IOException {
        String cid = nebulus.add("hello world".getBytes(StandardCharsets.UTF_8));
        try (InputStream in = nebulus.stream(cid)) {
            assertEquals("hello world", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = nebulus.stream("/ipfs/" + cid)) {
            assertEquals(11, in.readAllBytes().length);
        }
    }

    @Test
    public void refusesPathsOutsideTheStore() throws IOException {
        Files.writeString(store.resolve("secret"), "s");
        for (String bad : new String[]{"../secret", "/ipfs/../secret", "x/../../secret"}) {
            try {
                nebulus.get(bad);
                fail("read " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test(expected = NoSuchFileException.class)
    public void getUnknownCid() throws IOException {
        nebulus.get(TestData.HELLO_WORLD);
    }
}
