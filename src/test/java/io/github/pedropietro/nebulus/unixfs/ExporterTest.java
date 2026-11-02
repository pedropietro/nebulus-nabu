package io.github.pedropietro.nebulus.unixfs;

import io.github.pedropietro.nebulus.cid.Cid;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ExporterTest {

    private final Importer importer = new Importer();
    private final Map<Cid, byte[]> store = new HashMap<>();
    private final BlockSink sink = b -> store.put(b.cid(), b.data());
    private final BlockSource source = cids -> {
        Map<Cid, byte[]> out = new HashMap<>();
        for (Cid c : cids) {
            if (store.containsKey(c)) {
                out.put(c, store.get(c));
            }
        }
        return out;
    };

    @Test
    public void roundTripsFilesOfEverySize() throws IOException {
        for (int size : new int[]{0, 1, 262_144, 262_145, 1_000_000, 175 * 262_144 + 5}) {
            byte[] content = ImporterTest.pattern(size);
            Cid cid = importer.importBytes(content, sink).cid();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new Exporter(source).exportFile(cid, out);
            assertArrayEquals("size " + size, content, out.toByteArray());
        }
    }

    @Test
    public void resolvesFlatFolders() throws IOException {
        Map<String, ImportedNode> entries = new LinkedHashMap<>();
        entries.put("z.txt", importer.importBytes("last".getBytes(StandardCharsets.UTF_8), sink));
        entries.put("a.bin", importer.importBytes(ImporterTest.pattern(300_000), sink));
        Cid root = importer.importDirectory(entries, sink).cid();

        Exporter.Entry dir = new Exporter(source).resolve(root);
        assertTrue(dir.isDirectory());
        assertEquals(List.of("a.bin", "z.txt"), List.copyOf(dir.children().keySet()));
        assertEquals(entries.get("a.bin").cid(), dir.children().get("a.bin"));
        assertFalse(new Exporter(source).resolve(dir.children().get("z.txt")).isDirectory());
    }

    @Test
    public void rejectsBlocksThatDoNotMatchTheirCid() throws IOException {
        Cid cid = importer.importBytes("genuine".getBytes(StandardCharsets.UTF_8), sink).cid();
        store.put(cid, "forged".getBytes(StandardCharsets.UTF_8));
        try {
            new Exporter(source).exportFile(cid, new ByteArrayOutputStream());
            fail("forged block accepted");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("does not match"));
        }
    }

    @Test
    public void reportsMissingBlocks() {
        Cid missing = Cid.of(Cid.RAW, new byte[]{42});
        try {
            new Exporter(source).exportFile(missing, new ByteArrayOutputStream());
            fail("missing block not reported");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }
}
