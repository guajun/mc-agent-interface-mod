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
