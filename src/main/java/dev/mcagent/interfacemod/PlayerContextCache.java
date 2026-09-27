package dev.mcagent.interfacemod;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, time-limited store for chat-time context bundles.
 *
 * Both limits are explicit: at most {@code capacity} bundles are kept, and a
 * bundle stops being fetchable once it is older than {@code ttlMillis}. When a
 * new bundle would exceed the capacity the oldest inserted one is evicted
 * first (insertion order matches capture order), so eviction is deterministic
 * rather than hash-order dependent.
 *
 * Expiry is checked per lookup and expired entries are dropped from the map at
 * that point, so an expired id can never be answered with another player's
 * bundle - the lookup is always by exact opaque id.
 */
public final class PlayerContextCache {
    public enum Status {
        OK,
        EXPIRED,
        NOT_FOUND
    }

    /** What one lookup found. Only {@link Status#OK} carries a bundle payload. */
    public static final class Lookup {
        public final Status status;
        public final PlayerContext context;
        /** Capture time of the entry; -1 when the id is unknown. */
        public final long capturedAtMillis;
        /** Age at lookup time; -1 when the id is unknown. */
        public final long ageMillis;

        private Lookup(Status status, PlayerContext context, long capturedAtMillis, long ageMillis) {
            this.status = status;
            this.context = context;
            this.capturedAtMillis = capturedAtMillis;
            this.ageMillis = ageMillis;
        }

        public boolean ok() {
            return status == Status.OK;
        }
    }

    private final int capacity;
    private final long ttlMillis;
    private final LinkedHashMap<String, PlayerContext> entries = new LinkedHashMap<>();
    private long hits;
    private long misses;
    private long expired;
    private long evicted;

    public PlayerContextCache(int capacity, long ttlMillis) {
        this.capacity = Math.max(1, capacity);
        this.ttlMillis = Math.max(1L, ttlMillis);
    }

    public synchronized void put(PlayerContext context, long nowMillis) {
        purgeExpired(nowMillis);
        while (entries.size() >= capacity) {
            Iterator<Map.Entry<String, PlayerContext>> iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
            evicted++;
        }
        entries.put(context.contextId, context);
    }

    public synchronized Lookup get(String contextId, long nowMillis) {
        PlayerContext context = entries.get(contextId);
        if (context == null) {
            misses++;
            return new Lookup(Status.NOT_FOUND, null, -1L, -1L);
        }
        long age = nowMillis - context.capturedAtMillis;
        if (age > ttlMillis) {
            entries.remove(contextId);
            expired++;
            return new Lookup(Status.EXPIRED, null, context.capturedAtMillis, age);
        }
        hits++;
        return new Lookup(Status.OK, context, context.capturedAtMillis, age);
    }

    private void purgeExpired(long nowMillis) {
        Iterator<Map.Entry<String, PlayerContext>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            PlayerContext context = iterator.next().getValue();
            if (nowMillis - context.capturedAtMillis > ttlMillis) {
                iterator.remove();
                expired++;
            }
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public int capacity() {
        return capacity;
    }

    public long ttlMillis() {
        return ttlMillis;
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long misses() {
        return misses;
    }

    public synchronized long expired() {
        return expired;
    }

    public synchronized long evicted() {
        return evicted;
    }
}
