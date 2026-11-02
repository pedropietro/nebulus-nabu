package io.github.pedropietro.nebulus.unixfs;

import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Expected CIDs were generated with {@code ipfs-unixfs-importer@8.0.0} (the importer of the
 * JavaScript Nebulus) using {@code {cidVersion: 1, rawLeaves: true}}.
 */
public class ImporterTest {

    private final Importer importer = new Importer();

    private String cid(int size) throws IOException {
        return importer.importBytes(pattern(size), BlockSink.HASH_ONLY).cid().toString();
    }

    @Test
    public void singleChunkFilesAreRawBlocks() throws IOException {
        assertEquals("bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku", cid(0));
        assertEquals("bafkreigkgwdvr5wspzwpiutssn4xpj2i7wedshnwphhnu7ohx4pqaxxipe", cid(1));
        assertEquals("bafkreifr5ibxuwrafdc7vjpltbyiijhrvr4jm4yvfbyrgqpsg3mmdhfl2e", cid(11));
        assertEquals("bafkreig7abh4ml4jk6iylhvnfwkcbg23bjtsedukqquj54xn6fwj25wr6u", cid(262_143));
        assertEquals("bafkreigtb2zed6k6pq34amzfdj4ovylbble3alojsysuiikydagtlammdm", cid(262_144));
        assertEquals("bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e",
                importer.importBytes("hello world".getBytes(StandardCharsets.UTF_8), BlockSink.HASH_ONLY).cid().toString());
    }

    @Test
    public void multiChunkFilesUseABalancedDag() throws IOException {
        assertEquals("bafybeif4z4ikxldcsjhv2l4sxmk57abnwjkwpa57gopqjvdhkyxddhmzf4", cid(262_145));
        assertEquals("bafybeiauqn2tgwkyydo5v62clfl5vtokgwet2o3qpoaaxihbnh3ehac3am", cid(1_048_576));
        assertEquals("bafybeifddwzth5l772digwbzux3n3dztpmokg7axhv4budrxz4jbixctxa", cid(3 * 262_144 + 17));
    }

    @Test
    public void largeFilesAddDagLevels() throws IOException {
        // exactly 174 leaves: one level; one more leaf: a second level
        assertEquals("bafybeicd42f2zcidotwsoitc7yom627eeycmsnncaydllcognowm7ok5yu", cid(174 * 262_144));
        assertEquals("bafybeicwdxbb76mvui4baybplblhw2574qsiivkhtm3vdukwto3g4hd7vm", cid(174 * 262_144 + 1));
        assertEquals("bafybeiaedwboyer3zupya74qmrdt7pnbnzjdfsllyoatv6at3chvumqqcu", cid(175 * 262_144 + 5));
    }

    @Test
    public void folderMatchesJavaScriptNebulus() throws Exception {
        Path dir = Paths.get(getClass().getResource("/fixture/aperank").toURI());
        Map<String, ImportedNode> entries = new LinkedHashMap<>();
        for (String name : new String[]{"aperank.png", "readme.md", "index.js", "package.json"}) {
            entries.put(name, importer.importBytes(Files.readAllBytes(dir.resolve(name)), BlockSink.HASH_ONLY));
        }
        assertEquals("bafybeicaivkonz2sofegu2thshbeod3723ye2i5c5ivsyhv7gyuyunyyoq",
                importer.importDirectory(entries, BlockSink.HASH_ONLY).cid().toString());
    }

    @Test
    public void folderWithMultiChunkAndEmptyFiles() throws IOException, URISyntaxException {
        Map<String, ImportedNode> entries = new LinkedHashMap<>();
        entries.put("b.bin", importer.importBytes(pattern(600_000), BlockSink.HASH_ONLY));
        entries.put("a.txt", importer.importBytes("hello world".getBytes(StandardCharsets.UTF_8), BlockSink.HASH_ONLY));
        entries.put("empty", importer.importBytes(new byte[0], BlockSink.HASH_ONLY));
        assertEquals("bafybeifrtr2ehvid5j5h72dysyqa4hn6mp5aunrjxumxsb4qycgam23rzm",
                importer.importDirectory(entries, BlockSink.HASH_ONLY).cid().toString());
    }

    @Test
    public void emitsEveryBlockOnce() throws IOException {
        Map<String, Block> blocks = new LinkedHashMap<>();
        byte[] content = new byte[3 * 262_144 + 17];
        new java.util.Random(42).nextBytes(content); // distinct chunks (pattern() repeats every 256 bytes)
        ImportedNode root = importer.importBytes(content, b -> blocks.put(b.cid().toString(), b));
        assertEquals(5, blocks.size()); // 4 raw leaves + 1 parent
        assertEquals(3 * 262_144 + 17, root.fileSize());
        long blockBytes = blocks.values().stream().mapToLong(b -> b.data().length).sum();
        assertEquals(blockBytes, root.dagSize());
    }

    static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        return b;
    }
}
