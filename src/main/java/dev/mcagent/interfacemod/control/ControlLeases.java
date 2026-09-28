package dev.mcagent.interfacemod.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal exclusive-operation coordination for the multi-daemon contract from
 * mc-agent#6, without a general scheduler.
 *
 * <p>Ordinary writes (commands, marks) stay concurrent per session and are
 * serialised by the game thread; this class only exists for operations that
 * would need exclusive ownership. No composite operation (freeze, fork,
 * restore) is implemented yet, so the lease API is the mechanism, not a claim
 * that those operations exist. A lease is owned by the token that acquired it
 * (so a reconnecting daemon with the same credential can renew or release it)
 * and survives a socket disconnect until its TTL expires - the server never
 * releases a lease just because a client vanished, which is what #6 requires
 * for any future frozen world.
 *
 * <p>Game restarts drop all leases: there is no cross-run persistence, and the
 * protocol reports the new run id so a client can tell that no lease is held.
 */
public final class ControlLeases {
    /** One live lease. */
    public static final class Lease {
        public final String key;
        public final String tokenId;
        public final String sessionId;
        public final String label;
        public final long acquiredAtMillis;
        public long expiresAtMillis;
        public long renewedAtMillis;
        public int renewals;

        Lease(String key, String tokenId, String sessionId, String label,
              long acquiredAtMillis, long expiresAtMillis) {
            this.key = key;
            this.tokenId = tokenId;
            this.sessionId = sessionId;
            this.label = label;
            this.acquiredAtMillis = acquiredAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.renewedAtMillis = acquiredAtMillis;
        }

        public boolean expired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }

        public JsonObject toJson(long nowMillis) {
            JsonObject object = new JsonObject();
            object.addProperty("key", key);
            object.addProperty("holderTokenId", tokenId);
            object.addProperty("holderSessionId", sessionId);
            object.addProperty("holderLabel", label);
            object.addProperty("acquiredAtMillis", acquiredAtMillis);
            object.addProperty("expiresAtMillis", expiresAtMillis);
            object.addProperty("renewedAtMillis", renewedAtMillis);
            object.addProperty("renewals", renewals);
            object.addProperty("expiresInMillis", Math.max(0L, expiresAtMillis - nowMillis));
            return object;
        }
    }

    /** Result of one acquire attempt. */
    public static final class Acquire {
        public final boolean acquired;
        public final Lease lease;
        public final Lease holder;

        Acquire(boolean acquired, Lease lease, Lease holder) {
            this.acquired = acquired;
            this.lease = lease;
            this.holder = holder;
        }
    }

    private final Map<String, Lease> leases = new LinkedHashMap<>();

    private static String normalizeKey(String key) {
        String value = key == null ? "" : key.trim();
        if (value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("exclusive key must be 1..128 characters");
        }
        return value;
    }

    /** Acquire or renew: the same token may take its own lease again. */
    public synchronized Acquire acquire(String key, String tokenId, String sessionId, String label,
                                        long ttlMillis, long nowMillis) {
        String normalized = normalizeKey(key);
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("exclusive ttl must be positive");
        }
        Lease existing = leases.get(normalized);
        if (existing != null && existing.expired(nowMillis)) {
            leases.remove(normalized);
            existing = null;
        }
        if (existing != null) {
            if (!existing.tokenId.equals(tokenId)) {
                return new Acquire(false, null, existing);
            }
            existing.expiresAtMillis = nowMillis + ttlMillis;
            existing.renewedAtMillis = nowMillis;
            existing.renewals++;
            return new Acquire(true, existing, null);
        }
        Lease lease = new Lease(normalized, tokenId, sessionId, label == null ? "" : label,
                nowMillis, nowMillis + ttlMillis);
        leases.put(normalized, lease);
        return new Acquire(true, lease, null);
    }

    public enum RenewStatus {
        OK,
        NOT_FOUND,
        NOT_HOLDER
    }

    public synchronized Lease renew(String key, String tokenId, long ttlMillis, long nowMillis) {
        String normalized = normalizeKey(key);
        Lease lease = leases.get(normalized);
        if (lease == null || lease.expired(nowMillis)) {
            leases.remove(normalized);
            return null;
        }
        if (!lease.tokenId.equals(tokenId)) {
            return null;
        }
        lease.expiresAtMillis = nowMillis + Math.max(1L, ttlMillis);
        lease.renewedAtMillis = nowMillis;
        lease.renewals++;
        return lease;
    }

    /** Returns the holder when the key exists and belongs to another token. */
    public synchronized Lease holderIfForeign(String key, String tokenId, long nowMillis) {
        Lease lease = leases.get(normalizeKey(key));
        if (lease == null) {
            return null;
        }
        if (lease.expired(nowMillis)) {
            leases.remove(lease.key);
            return null;
        }
        return lease.tokenId.equals(tokenId) ? null : lease;
    }

    /** Look up a lease without changing it. */
    public synchronized Lookup lookup(String key, String tokenId, long nowMillis) {
        String normalized = normalizeKey(key);
        Lease lease = leases.get(normalized);
        if (lease != null && lease.expired(nowMillis)) {
            leases.remove(normalized);
            lease = null;
        }
        return new Lookup(lease, lease != null && lease.tokenId.equals(tokenId));
    }

    /** Result of a read-only lease lookup. */
    public static final class Lookup {
        public final Lease lease;
        public final boolean mine;

        Lookup(Lease lease, boolean mine) {
            this.lease = lease;
            this.mine = mine;
        }
    }

    public enum ReleaseStatus {
        RELEASED,
        NOT_FOUND,
        NOT_HOLDER
    }

    public synchronized ReleaseStatus release(String key, String tokenId, long nowMillis) {
        String normalized = normalizeKey(key);
        Lease lease = leases.get(normalized);
        if (lease == null || lease.expired(nowMillis)) {
            leases.remove(normalized);
            return ReleaseStatus.NOT_FOUND;
        }
        if (!lease.tokenId.equals(tokenId)) {
            return ReleaseStatus.NOT_HOLDER;
        }
        leases.remove(normalized);
        return ReleaseStatus.RELEASED;
    }

    /** Release every lease owned by a revoked token. */
    public synchronized int releaseToken(String tokenId) {
        int released = 0;
        List<String> keys = new ArrayList<>();
        for (Lease lease : leases.values()) {
            if (lease.tokenId.equals(tokenId)) {
                keys.add(lease.key);
            }
        }
        for (String key : keys) {
            leases.remove(key);
            released++;
        }
        return released;
    }

    public synchronized List<Lease> list(long nowMillis) {
        List<String> expired = new ArrayList<>();
        for (Lease lease : leases.values()) {
            if (lease.expired(nowMillis)) {
                expired.add(lease.key);
            }
        }
        for (String key : expired) {
            leases.remove(key);
        }
        List<Lease> result = new ArrayList<>(leases.values());
        result.sort(Comparator.comparing(lease -> lease.key));
        return result;
    }

    public synchronized int size(long nowMillis) {
        return list(nowMillis).size();
    }

    public synchronized JsonArray toJson(long nowMillis) {
        JsonArray array = new JsonArray();
        for (Lease lease : list(nowMillis)) {
            array.add(lease.toJson(nowMillis));
        }
        return array;
    }
}
