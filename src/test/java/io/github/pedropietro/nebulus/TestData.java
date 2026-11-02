package io.github.pedropietro.nebulus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

final class TestData {

    static final String HELLO_WORLD = "bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e";
    /** The aperank fixture folder, as computed by the JavaScript Nebulus. */
    static final String APERANK = "bafybeicaivkonz2sofegu2thshbeod3723ye2i5c5ivsyhv7gyuyunyyoq";

    private TestData() {
    }

    /** Deterministic content shared with the JavaScript vector generator. */
    static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        return b;
    }

    static Path fixture(String name) {
        try {
            return Paths.get(TestData.class.getResource("/fixture/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An ipfs/ entry is a symbolic link, or a plain copy when the instance copies files. */
    static void assertStoredEntry(Nebulus nebulus, Path entry) {
        if (nebulus.usesSymbolicLinks()) {
            org.junit.Assert.assertTrue(entry + " should be a symbolic link", Files.isSymbolicLink(entry));
        } else {
            org.junit.Assert.assertTrue(entry + " should be a copied file",
                    Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        }
    }

    static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
