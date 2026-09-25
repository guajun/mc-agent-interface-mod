package dev.mcagent.interfacemod;

import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * The JSON shapes of the chat context protocol: the chat event and the
 * {@code CONTEXT} fetch reply.
 *
 * Kept free of game classes so the shapes and the cache can be tested without
 * a running server; {@link ServerCore} supplies the captured values.
 */
public final class ContextProtocol {
    private ContextProtocol() {
    }

    /**
     * A chat event with its correlation id and compact sender summary.
     *
     * When {@code context} is null the sender could not be captured (for
     * example a message with no resolvable player); the event still goes out,
     * marked unavailable, and carries no id to fetch.
     */
    public static JsonObject chatEvent(long seq, String text, String senderName, PlayerContext context) {
        JsonObject object = base("chat");
        object.addProperty("seq", seq);
        object.addProperty("text", text == null ? "" : text);
        if (senderName != null && !senderName.isEmpty()) {
            object.addProperty("sender", senderName);
        }
        if (context == null) {
            JsonObject unavailable = new JsonObject();
            unavailable.addProperty("available", false);
            unavailable.addProperty("reason", "sender_unavailable");
            object.add("context", unavailable);
        } else {
            object.addProperty("context_id", context.contextId);
            object.add("context", context.summaryJson());
        }
        return object;
    }

    /**
     * The publication decision for a broadcast message: a fresh receipt is
     * published as receipt-time, an expired one becomes a structured expiry,
     * and a missing one may only carry an explicitly broadcast-time fallback.
     */
    public static JsonObject chatEvent(long seq, String text, String senderName, ChatReceipts.Lookup lookup,
                                       PlayerContext fallback) {
        if (lookup.status == ChatReceipts.Status.EXPIRED) {
            return chatEventExpired(seq, text, senderName, lookup.receivedAtMillis, lookup.ageMillis);
        }
        PlayerContext context = lookup.status == ChatReceipts.Status.OK ? lookup.context : fallback;
        return chatEvent(seq, text, senderName, context);
    }

    /**
     * A chat event whose receipt-time bundle is gone. It carries no context id
     * and no live substitute: the caller can see that the frozen context was
     * lost and when the packet arrived.
     */
    public static JsonObject chatEventExpired(long seq, String text, String senderName, long receivedAtMillis,
                                              long ageMillis) {
        JsonObject object = base("chat");
        object.addProperty("seq", seq);
        object.addProperty("text", text == null ? "" : text);
        if (senderName != null && !senderName.isEmpty()) {
            object.addProperty("sender", senderName);
        }
        JsonObject unavailable = new JsonObject();
        unavailable.addProperty("available", false);
        unavailable.addProperty("reason", "receipt_expired");
        unavailable.addProperty("receivedAt", receivedAtMillis);
        unavailable.addProperty("ageMillis", ageMillis);
        object.add("context", unavailable);
        return object;
    }

    public static JsonObject contextReply(String contextId, PlayerContextCache.Lookup lookup,
                                          PlayerContextCache cache) {
        JsonObject object = base("context");
        object.addProperty("context_id", contextId);
        object.addProperty("status", lookup.status.name().toLowerCase(Locale.ROOT));
        if (lookup.status == PlayerContextCache.Status.OK) {
            object.addProperty("ageMillis", lookup.ageMillis);
            object.add("context", lookup.context.toJson());
        } else if (lookup.status == PlayerContextCache.Status.EXPIRED) {
            object.addProperty("capturedAt", lookup.capturedAtMillis);
            object.addProperty("ageMillis", lookup.ageMillis);
        }
        object.add("cache", cacheJson(cache));
        return object;
    }

    public static JsonObject statsReply(PlayerContextCache cache) {
        JsonObject object = base("context_stats");
        object.addProperty("status", "ok");
        JsonObject cacheObject = cacheJson(cache);
        cacheObject.addProperty("hits", cache.hits());
        cacheObject.addProperty("misses", cache.misses());
        cacheObject.addProperty("expired", cache.expired());
        cacheObject.addProperty("evicted", cache.evicted());
        object.add("cache", cacheObject);
        return object;
    }

    private static JsonObject cacheJson(PlayerContextCache cache) {
        JsonObject object = new JsonObject();
        object.addProperty("capacity", cache.capacity());
        object.addProperty("ttlMillis", cache.ttlMillis());
        object.addProperty("size", cache.size());
        return object;
    }

    private static JsonObject base(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("millis", System.currentTimeMillis());
        return object;
    }
}
