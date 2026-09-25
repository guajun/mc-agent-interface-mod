package dev.mcagent.interfacemod;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receipt-time captures waiting for their broadcast.
 *
 * A chat message is captured on the server thread before the asynchronous
 * filter, but the matching broadcast event fires only after filtering has
 * finished. Between those two points the sender may move or turn, so the
 * frozen bundle is parked here under the message identity and consumed by the
 * broadcast listener - the published bundle describes receipt time, never
 * broadcast time.
 *
 * Like the context cache, this store is bounded and expiring: a receipt whose
 * broadcast never arrives (cancelled message, mod broadcast) is dropped after
 * {@code ttlMillis} and the oldest entries are evicted first. A {@link #take}
 * distinguishes a usable receipt from one that expired, so callers can report
 * the loss instead of substituting later live state. Only consumed receipts
 * are published to the context cache, so junk packets cannot evict live
 * bundles. Lookups are by exact key, so a mismatched message can never consume
 * another player's receipt.
 */
public final class ChatReceipts {
    public enum Status {
        /** A fresh, frozen bundle for the message. */
        OK,
        /** The receipt existed but was older than the ttl. */
        EXPIRED,
        /** No receipt was parked for this message. */
        NOT_FOUND
    }

    /** What one consumption found. Only {@link Status#OK} carries a bundle. */
    public static final class Lookup {
        public final Status status;
        public final PlayerContext context;
        /** When the receipt arrived; -1 when there was none. */
        public final long receivedAtMillis;
        /** Age at consumption; -1 when there was none. */
        public final long ageMillis;

        private Lookup(Status status, PlayerContext context, long receivedAtMillis, long ageMillis) {
            this.status = status;
            this.context = context;
            this.receivedAtMillis = receivedAtMillis;
            this.ageMillis = ageMillis;
        }

        public boolean ok() {
            return status == Status.OK;
        }
    }

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

    public synchronized void put(String key, PlayerContext context, long nowMillis) {
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
        entries.put(key, new Entry(context, nowMillis));
    }

    /**
     * Consume the frozen capture for one message. Missing and expired receipts
     * are distinct so the caller can label or refuse a broadcast-time fallback
     * rather than present it as receipt-time state.
     */
    public synchronized Lookup take(String key, long nowMillis) {
        Entry entry = entries.remove(key);
        if (entry == null) {
            return new Lookup(Status.NOT_FOUND, null, -1L, -1L);
        }
        long age = nowMillis - entry.receivedAtMillis;
        if (age > ttlMillis) {
            expired++;
            return new Lookup(Status.EXPIRED, null, entry.receivedAtMillis, age);
        }
        matched++;
        return new Lookup(Status.OK, entry.context, entry.receivedAtMillis, age);
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
        private final PlayerContext context;
        private final long receivedAtMillis;

        private Entry(PlayerContext context, long receivedAtMillis) {
            this.context = context;
            this.receivedAtMillis = receivedAtMillis;
        }
    }
}
