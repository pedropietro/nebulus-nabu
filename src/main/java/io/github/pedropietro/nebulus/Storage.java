package io.github.pedropietro.nebulus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * The Nebulus file system layout:
 *
 * <pre>
 * &lt;path&gt;/src/&lt;sha256&gt;      content-addressed copies of buffers and downloads
 * &lt;path&gt;/ipfs/&lt;cid&gt;        symbolic link to the file holding that CID's content
 * &lt;path&gt;/ipfs/&lt;cid&gt;/&lt;name&gt; for folders: links to ../&lt;child cid&gt;
 * &lt;path&gt;/repo/            IPFS node state (blocks, identity)
 * </pre>
 */
final class Storage {

    private static final Logger LOG = Logger.getLogger(Storage.class.getName());

    private final Path root;
    private final Path src;
    private final Path ipfs;
    /** Cleared when links are disabled or the file system refuses them; then files are copied. */
    private final AtomicBoolean symlinks;

    Storage(Path root) throws IOException {
        this(root, true);
    }

    Storage(Path root, boolean useSymbolicLinks) throws IOException {
        this.root = root;
        this.src = root.resolve("src");
        this.ipfs = root.resolve("ipfs");
        this.symlinks = new AtomicBoolean(useSymbolicLinks);
        Files.createDirectories(src);
        Files.createDirectories(ipfs);
    }

    /** False once Nebulus copies files into {@code ipfs/} instead of linking them. */
    boolean usesSymbolicLinks() {
        return symlinks.get();
    }

    Path root() {
        return root;
    }

    Path srcDir() {
        return src;
    }

    Path ipfsDir() {
        return ipfs;
    }

    Path repoDir() {
        return root.resolve("repo");
    }

    /**
     * Resolves {@code <cid>}, {@code /ipfs/<cid>} or {@code <cid>/<name>} inside the ipfs folder,
     * refusing anything that would escape it.
     */
    Path resolve(String cidPath) {
        String p = cidPath.startsWith("/ipfs/") ? cidPath.substring("/ipfs/".length()) : cidPath;
        if (p.isEmpty()) {
            throw new IllegalArgumentException("Empty CID");
        }
        Path resolved = ipfs.resolve(p).normalize();
        if (!resolved.startsWith(ipfs.normalize()) || resolved.equals(ipfs.normalize())) {
            throw new IllegalArgumentException("Invalid CID path: " + cidPath);
        }
        return resolved;
    }

    /** Writes {@code content} to {@code src/<sha256>} (no-op if it is already there). */
    Path storeBytes(byte[] content) throws IOException {
        Path target = src.resolve(sha256Hex(content));
        if (!Files.exists(target)) {
            Path tmp = src.resolve(UUID.randomUUID().toString());
            Files.write(tmp, content);
            moveInto(tmp, target);
        }
        return target;
    }

    /** Copies a stream into {@code src/<sha256>}, aborting when more than {@code maxBytes} arrive. */
    Path storeStream(InputStream in, long maxBytes) throws IOException {
        return storeWith(out -> {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new FileTooLargeException(total, maxBytes);
                }
                out.write(buffer, 0, n);
            }
        });
    }

    /**
     * Lets {@code writer} fill a temporary file (named by a random UUID, as in Nebulus) and then
     * renames it to its sha256, so equal content is stored once.
     */
    Path storeWith(IOConsumer<OutputStream> writer) throws IOException {
        Path tmp = src.resolve(UUID.randomUUID().toString());
        MessageDigest digest = sha256();
        try (OutputStream file = Files.newOutputStream(tmp);
             DigestOutputStream out = new DigestOutputStream(file, digest)) {
            writer.accept(out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Path target = src.resolve(HexFormat.of().formatHex(digest.digest()));
        moveInto(tmp, target);
        return target;
    }

    private static void moveInto(Path tmp, Path target) throws IOException {
        if (Files.exists(target)) {
            Files.delete(tmp);
            return;
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Creates {@code linkPath} as a symbolic link to {@code target}: relative when both are on
     * the same file system root, absolute otherwise (e.g. {@code D:} and {@code C:} on Windows).
     * An existing, working link is left alone (CIDs are immutable). Where symbolic links are
     * disabled or not available (Windows without developer mode), the file is copied instead.
     */
    void link(Path linkPath, Path target) throws IOException {
        if (Files.exists(linkPath)) {
            return;
        }
        if (Files.isSymbolicLink(linkPath)) {
            Files.delete(linkPath); // dangling link: its target was removed
        }
        Files.createDirectories(linkPath.getParent());
        if (symlinks.get()) {
            Path linkTarget = linkTarget(linkPath.getParent(), target);
            LOG.fine(() -> "create symlink " + linkPath + " -> " + linkTarget);
            try {
                Files.createSymbolicLink(linkPath, linkTarget);
                return;
            } catch (FileAlreadyExistsException e) {
                return; // created concurrently: same CID, same content
            } catch (UnsupportedOperationException | FileSystemException e) {
                if (symlinks.getAndSet(false)) {
                    LOG.warning("Symbolic links unavailable (" + e.getMessage()
                            + "); Nebulus will copy files into " + ipfs + " instead");
                }
            }
        }
        copy(target, linkPath);
    }

    private static Path linkTarget(Path linkDir, Path target) {
        Path from = linkDir.toAbsolutePath().normalize();
        Path to = target.toAbsolutePath().normalize();
        if (from.getRoot() == null || !from.getRoot().equals(to.getRoot())) {
            return to; // different drives: a relative path cannot express this link
        }
        try {
            return from.relativize(to);
        } catch (IllegalArgumentException e) {
            return to;
        }
    }

    private static void copy(Path target, Path linkPath) throws IOException {
        Path tmp = linkPath.resolveSibling(linkPath.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(target, tmp);
            makeWritable(tmp);
            Files.move(tmp, linkPath);
        } catch (FileAlreadyExistsException e) {
            // copied concurrently: same CID, same content
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Copies belong to Nebulus, so they must stay manageable: Windows copies keep the source's
     * read-only attribute (Git object files have it), which would block deleting the store.
     */
    private static void makeWritable(Path file) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        if (dos != null && dos.readAttributes().isReadOnly()) {
            dos.setReadOnly(false);
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> perms = posix.readAttributes().permissions();
            if (perms.add(PosixFilePermission.OWNER_WRITE)) {
                posix.setPermissions(perms);
            }
        }
    }

    static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256().digest(data));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }
}
