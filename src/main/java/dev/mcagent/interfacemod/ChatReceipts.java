package dev.mcagent.interfacemod;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receipt-time captures waiting for their broadcast.
 *
 * A chat packet is captured when the network handler first sees it, but the
 * matching broadcast event fires only after the asynchronous chat filter has
 * finished. Between those two points the sender may move or turn, so the
 * capture is parked here under the packet's identity and consumed by the
 * broadcast listener - the bundle behind the returned id was frozen at
 * receipt, never at broadcast.
 *
 * Like the context cache, this store is bounded and expiring: a receipt whose
 * broadcast never arrives (invalid packet, cancelled message, mod broadcast)
 * is dropped after {@code ttlMillis} and the oldest entries are evicted first.
 * Lookups are by exact key, so a mismatched message can never consume another
 * player's receipt.
 */
public final class ChatReceipts {
    private final int capacity;
    private final long ttlMillis;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private long matched;
    private long expired;
    private long evicted;

    public ChatReceipts(int capacity, long ttlMillis) {
        this.capacity = Math.max(1, capacity);
        this.ttlMillis = Math.max(1L, ttlMillis);
    }

    /** Identity of one chat packet: sender UUID plus the packet's random salt. */
    public static String key(String senderUuid, long salt) {
        return senderUuid + ":" + salt;
    }

    public synchronized void put(String key, String contextId, long nowMillis) {
        purgeExpired(nowMillis);
        while (entries.size() >= capacity) {
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
            evicted++;
        }
        entries.put(key, new Entry(contextId, nowMillis));
    }

    /**
     * Consume the capture for one message, or null when there is none (a
     * broadcast that never passed through the network handler) or when it has
     * expired. One receipt is used at most once.
     */
    public synchronized String take(String key, long nowMillis) {
        Entry entry = entries.remove(key);
        if (entry == null) {
            return null;
        }
        if (nowMillis - entry.receivedAtMillis > ttlMillis) {
            expired++;
            return null;
        }
        matched++;
        return entry.contextId;
    }

    private void purgeExpired(long nowMillis) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (nowMillis - entry.receivedAtMillis > ttlMillis) {
                iterator.remove();
                expired++;
            }
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long matched() {
        return matched;
    }

    public synchronized long expired() {
        return expired;
    }

    public synchronized long evicted() {
        return evicted;
    }

    private static final class Entry {
        private final String contextId;
        private final long receivedAtMillis;

        private Entry(String contextId, long receivedAtMillis) {
            this.contextId = contextId;
            this.receivedAtMillis = receivedAtMillis;
        }
    }
}
