package io.github.pedropietro.nebulus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Nebulus events. A <em>global</em> listener ({@code "push"}, {@code "pull"}) fires for every
 * CID; a <em>filtered</em> listener ({@code "push:<cid>"}) fires once for that CID and is then
 * removed.
 */
final class Events {

    private static final Logger LOG = Logger.getLogger(Events.class.getName());

    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    void on(String event, Consumer<String> listener) {
        listeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    boolean off(String event, Consumer<String> listener) {
        List<Consumer<String>> list = listeners.get(event);
        return list != null && list.remove(listener);
    }

    void off(String event) {
        listeners.remove(event);
    }

    /** Fires each event globally, then its filtered form {@code event:cid} once. */
    void trigger(List<String> events, String cid) {
        for (String event : events) {
            List<Consumer<String>> global = listeners.get(event);
            if (global != null) {
                global.forEach(l -> call(l, event, cid));
            }
            List<Consumer<String>> filtered = listeners.remove(event + ":" + cid);
            if (filtered != null) {
                filtered.forEach(l -> call(l, event + ":" + cid, cid));
            }
        }
    }

    private static void call(Consumer<String> listener, String event, String cid) {
        try {
            listener.accept(cid);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Listener for '" + event + "' failed", e);
        }
    }
}
