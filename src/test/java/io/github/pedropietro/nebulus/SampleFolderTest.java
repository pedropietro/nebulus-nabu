package io.github.pedropietro.nebulus;

import io.github.pedropietro.nebulus.unixfs.BlockSink;
import io.github.pedropietro.nebulus.unixfs.Importer;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Opt-in check against a folder of real files:
 *
 * <pre>mvn test -Dtest=SampleFolderTest -Dnebulus.samples=E:\Temp</pre>
 *
 * <p>Every file is added offline and read back. Files up to {@code nebulus.samples.maxMb}
 * (default 200) go through {@link Nebulus#add(Path)}; larger ones are only hashed. The top-level
 * files form a flat folder. When a Kubo API answers at {@code nebulus.kubo} (default
 * {@code http://127.0.0.1:5001}), every CID is compared with
 * {@code ipfs add --only-hash --cid-version=1 --raw-leaves}, which neither stores nor publishes
 * anything. Nothing touches the IPFS network.</p>
 */
public class SampleFolderTest {

    private static final Pattern HASH = Pattern.compile("\"Name\":\"((?:[^\"\\\\]|\\\\.)*)\",\"Hash\":\"([a-z0-9]+)\"");

    private Path samples;
    private Path store;
    private long maxBytes;
    private HttpClient http;
    private Optional<URI> kubo;

    @Before
    public void setUp() throws IOException {
        String dir = System.getProperty("nebulus.samples");
        Assume.assumeTrue("set -Dnebulus.samples=<folder> to run", dir != null && !dir.isBlank());
        samples = Path.of(dir);
        Assume.assumeTrue(samples + " is not a folder", Files.isDirectory(samples));
        maxBytes = Long.getLong("nebulus.samples.maxMb", 200) * 1_000_000L;
        store = Files.createTempDirectory("nebulus-samples");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        kubo = findKubo(URI.create(System.getProperty("nebulus.kubo", "http://127.0.0.1:5001")));
    }

    @After
    public void tearDown() {
        if (store != null) {
            TestData.deleteRecursively(store);
        }
    }

    @Test
    public void addsEveryFileAndMatchesKubo() throws Exception {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(samples)) {
            files = walk.filter(Files::isRegularFile).filter(Files::isReadable).sorted().collect(Collectors.toList());
        }
        Assume.assumeFalse("no files in " + samples, files.isEmpty());

        List<String> problems = new ArrayList<>();
        Map<String, String> topLevel = new LinkedHashMap<>();
        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "%nNebulus vs %s, %d files in %s, store %s, %s%n",
                kubo.map(u -> "Kubo " + u).orElse("(no Kubo)"), files.size(), samples, store,
                System.getProperty("os.name")));
        report.append(String.format(Locale.ROOT, "%-62s %12s  %-8s %s%n", "CID", "bytes", "kubo", "file"));

        try (Nebulus nebulus = new Nebulus(NebulusOptions.builder().path(store).build())) {
            for (Path file : files) {
                long size = Files.size(file);
                String rel = samples.relativize(file).toString();
                String cid;
                String mode;
                if (size <= maxBytes) {
                    cid = nebulus.add(file);
                    mode = "added";
                    if (!sha256(file).equals(sha256(nebulus.stream(cid)))) {
                        problems.add("content differs after add: " + rel);
                    }
                    if (file.getParent().equals(samples) && validName(file.getFileName().toString())) {
                        topLevel.put(file.getFileName().toString(), cid);
                    }
                } else {
                    try (InputStream in = Files.newInputStream(file)) {
                        cid = new Importer().importFile(in, BlockSink.HASH_ONLY).cid().toString();
                    }
                    mode = "hashed";
                }
                String kuboCid = kubo.map(u -> kuboAdd(u, List.of(file), false).get("")).orElse(null);
                String verdict = kuboCid == null ? "-" : kuboCid.equals(cid) ? "match" : "DIFFERS";
                if ("DIFFERS".equals(verdict)) {
                    problems.add(rel + ": nebulus " + cid + " kubo " + kuboCid);
                }
                report.append(String.format(Locale.ROOT, "%-62s %12d  %-8s %s (%s)%n", cid, size, verdict, rel, mode));
            }

            if (!topLevel.isEmpty()) {
                String folder = nebulus.folder(topLevel);
                List<String> names = new ArrayList<>(topLevel.keySet());
                Collections.sort(names);
                if (!names.equals(nebulus.list(folder))) {
                    problems.add("folder listing differs: " + nebulus.list(folder));
                }
                String verdict = "-";
                if (kubo.isPresent()) {
                    List<Path> parts = names.stream().map(samples::resolve).collect(Collectors.toList());
                    String kuboFolder = kuboAdd(kubo.get(), parts, true).get("");
                    verdict = folder.equals(kuboFolder) ? "match" : "DIFFERS";
                    if (!folder.equals(kuboFolder)) {
                        problems.add("folder: nebulus " + folder + " kubo " + kuboFolder);
                    }
                }
                report.append(String.format(Locale.ROOT, "%-62s %12s  %-8s [folder of %d top-level files]%n",
                        folder, "", verdict, names.size()));
            }
            report.append("symbolic links in use: ").append(nebulus.usesSymbolicLinks()).append('\n');
        }
        System.out.println(report);
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    private Optional<URI> findKubo(URI api) {
        try {
            HttpRequest req = HttpRequest.newBuilder(api.resolve("/api/v0/version"))
                    .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 ? Optional.of(api) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** {@code ipfs add --only-hash --cid-version=1 --raw-leaves [-w]}; returns name to CID ("" = root). */
    private Map<String, String> kuboAdd(URI api, List<Path> files, boolean wrap) {
        String boundary = "nebulus" + UUID.randomUUID().toString().replace("-", "");
        String query = "only-hash=true&cid-version=1&raw-leaves=true&pin=false&progress=false"
                + (wrap ? "&wrap-with-directory=true" : "");
        HttpRequest req = HttpRequest.newBuilder(api.resolve("/api/v0/add?" + query))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> multipart(boundary, files)))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IOException("Kubo answered " + res.statusCode() + ": " + res.body());
            }
            Map<String, String> result = new LinkedHashMap<>();
            Matcher m = HASH.matcher(res.body());
            String last = null;
            while (m.find()) {
                result.put(m.group(1), m.group(2));
                last = m.group(2);
            }
            if (!wrap && last != null) {
                result.put("", last); // a single file: its own CID is the root
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A fresh multipart body each time it is requested, so the client can resend it. */
    private static InputStream multipart(String boundary, List<Path> files) {
        List<InputStream> parts = new ArrayList<>();
        for (Path f : files) {
            String name = URLEncoder.encode(f.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
            parts.add(new ByteArrayInputStream(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
            parts.add(new LazyFileStream(f));
            parts.add(new ByteArrayInputStream("\r\n".getBytes(StandardCharsets.UTF_8)));
        }
        parts.add(new ByteArrayInputStream(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
        return new SequenceInputStream(Collections.enumeration(parts));
    }

    private static boolean validName(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.contains("\\");
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return sha256(in);
        }
    }

    private static String sha256(InputStream stream) throws IOException {
        try (DigestInputStream in = new DigestInputStream(stream, MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Opens the file only when the upload reaches it. */
    private static final class LazyFileStream extends InputStream {
        private final Path file;
        private InputStream in;

        LazyFileStream(Path file) {
            this.file = file;
        }

        private InputStream in() throws IOException {
            if (in == null) {
                in = Files.newInputStream(file);
            }
            return in;
        }

        @Override
        public int read() throws IOException {
            return in().read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return in().read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (in != null) {
                in.close();
            }
        }
    }
}
