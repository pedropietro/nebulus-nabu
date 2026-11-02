package io.github.pedropietro.nebulus;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Polls a public gateway until it serves a CID, which is how Nebulus decides that a push has
 * been replicated: once the gateway has fetched the content it keeps it for a while, so the
 * local node can stop seeding ("ephemeral IPFS").
 */
final class GatewayChecker {

    private static final Logger LOG = Logger.getLogger(GatewayChecker.class.getName());

    private final HttpClient http;
    private final URI gateway;
    private final Duration requestTimeout;
    private final Duration interval;

    GatewayChecker(HttpClient http, URI gateway, Duration requestTimeout, Duration interval) {
        this.http = http;
        this.gateway = gateway;
        this.requestTimeout = requestTimeout;
        this.interval = interval;
    }

    URI urlFor(String cidPath) {
        String base = gateway.toString().replaceAll("/+$", "");
        return URI.create(base + "/ipfs/" + cidPath);
    }

    /** Blocks until {@code <gateway>/ipfs/<cidPath>} answers 2xx, or until {@code deadlineNanos}. */
    void await(String cidPath, long deadlineNanos) throws IOException, TimeoutException {
        URI url = urlFor(cidPath);
        HttpRequest request = HttpRequest.newBuilder(url).timeout(requestTimeout).GET().build();
        while (true) {
            try {
                HttpResponse<Void> res = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (res.statusCode() / 100 == 2) {
                    LOG.fine(() -> "found on gateway: " + url);
                    return;
                }
                LOG.fine(() -> "not yet replicated (" + res.statusCode() + "): " + url);
            } catch (IOException e) {
                LOG.fine(() -> "not yet replicated (" + e.getClass().getSimpleName() + "): " + url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for " + url);
            }
            if (deadlineNanos != 0 && System.nanoTime() - deadlineNanos >= 0) {
                throw new TimeoutException("Not found on gateway in time: " + url);
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for " + url);
            }
        }
    }
}
