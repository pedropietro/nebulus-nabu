package io.github.pedropietro.nebulus;

import java.io.IOException;
import java.util.Locale;

/** Thrown by {@link Nebulus#download} when a file exceeds {@link NebulusOptions#maxMegabytes()}. */
public class FileTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final long size;
    private final long maxBytes;

    public FileTooLargeException(long size, long maxBytes) {
        super(String.format(Locale.ROOT, "file size too large: %.2fMB. max allowed size: %sMB",
                size / 1_000_000.0, maxBytes / 1_000_000));
        this.size = size;
        this.maxBytes = maxBytes;
    }

    /** Size in bytes (declared by Content-Length, or read so far). */
    public long size() {
        return size;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
