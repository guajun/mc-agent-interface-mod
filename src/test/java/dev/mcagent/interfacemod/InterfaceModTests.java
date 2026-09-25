package dev.mcagent.interfacemod;

import com.google.gson.JsonObject;

/**
 * Dependency-free unit tests for the chat context protocol and its cache.
 *
 * Run through {@code test.py}; they only touch the pure classes
 * ({@link PlayerContext}, {@link PlayerContextCache}, {@link ContextProtocol})
 * so no game process is needed. The Minecraft-facing capture path is exercised
 * by the lab integration tests.
 */
public final class InterfaceModTests {
    private static int checks;

    private InterfaceModTests() {
    }

    public static void main(String[] args) {
        cacheStoresAndCorrelates();
        cacheEvictsOldestFirst();
        cacheExpiresAndStaysGone();
        expiredAndUnknownNeverLeak();
        unavailableSenderStillEmitsEvent();
        twoPlayersCorrelateIndependently();
        replyShapesAreStructured();
        statsReportLimits();
        receiptFreezesTheTransform();
        receiptsAreSingleUseAndExact();
        receiptsExpire();
        receiptsEvictOldestFirst();
        System.out.println("all " + checks + " checks passed");
    }

    private static void cacheStoresAndCorrelates() {
        PlayerContextCache cache = new PlayerContextCache(4, 60_000L);
        PlayerContext alice = context(1L, "ctx-alice", 1_000L, "uuid-alice", "Alice");
        cache.put(alice, 1_000L);

        PlayerContextCache.Lookup lookup = cache.get("ctx-alice", 1_050L);
        check(lookup.status == PlayerContextCache.Status.OK, "fresh bundle is ok");
        check(lookup.context == alice, "the stored bundle comes back");
        check(lookup.ageMillis == 50L, "age is measured from capture");
        check("uuid-alice".equals(lookup.context.uuid), "uuid correlates through the opaque id");
        check(cache.size() == 1, "one entry stored");
    }

    private static void cacheEvictsOldestFirst() {
        PlayerContextCache cache = new PlayerContextCache(2, 60_000L);
        cache.put(context(1L, "ctx-a", 1_000L, "uuid-a", "A"), 1_010L);
        cache.put(context(2L, "ctx-b", 1_001L, "uuid-b", "B"), 1_010L);
        cache.put(context(3L, "ctx-c", 1_002L, "uuid-c", "C"), 1_010L);

        check(cache.size() == 2, "capacity is a hard limit");
        check(cache.get("ctx-a", 1_011L).status == PlayerContextCache.Status.NOT_FOUND, "oldest evicted");
        check(cache.get("ctx-b", 1_011L).ok(), "second kept");
        check(cache.get("ctx-c", 1_011L).ok(), "newest kept");
        check(cache.evicted() == 1L, "eviction is counted");
    }

    private static void cacheExpiresAndStaysGone() {
        PlayerContextCache cache = new PlayerContextCache(4, 1_000L);
        cache.put(context(1L, "ctx-a", 10_000L, "uuid-a", "A"), 10_000L);

        check(cache.get("ctx-a", 11_000L).status == PlayerContextCache.Status.OK,
                "an age equal to the ttl is still valid");
        PlayerContextCache.Lookup expired = cache.get("ctx-a", 11_001L);
        check(expired.status == PlayerContextCache.Status.EXPIRED, "older than the ttl expires");
        check(expired.context == null, "an expired lookup carries no payload");
        check(expired.capturedAtMillis == 10_000L, "expired lookup reports capture time");
        check(expired.ageMillis == 1_001L, "expired lookup reports age");
        check(cache.get("ctx-a", 11_002L).status == PlayerContextCache.Status.NOT_FOUND,
                "expired entry is dropped after it is seen");
        check(cache.expired() == 1L, "expiry is counted once");
    }

    private static void expiredAndUnknownNeverLeak() {
        PlayerContextCache cache = new PlayerContextCache(2, 100L);
        cache.put(context(1L, "ctx-alice", 1_000L, "uuid-alice", "Alice"), 1_000L);
        cache.put(context(2L, "ctx-bob", 1_000L, "uuid-bob", "Bob"), 1_000L);

        PlayerContextCache.Lookup unknown = cache.get("ctx-carol", 1_000L);
        check(unknown.status == PlayerContextCache.Status.NOT_FOUND, "unknown id is not found");
        check(unknown.context == null, "unknown id carries no bundle");

        PlayerContextCache.Lookup expiredAlice = cache.get("ctx-alice", 1_200L);
        check(expiredAlice.status == PlayerContextCache.Status.EXPIRED, "expired id reports expiry");
        check(expiredAlice.context == null, "expired id returns no data at all");

        PlayerContextCache.Lookup bob = cache.get("ctx-bob", 1_200L);
        check(bob.status == PlayerContextCache.Status.EXPIRED, "a neighbour id is never substituted");
        check(bob.context == null, "no neighbour payload either");
    }

    private static void unavailableSenderStillEmitsEvent() {
        JsonObject event = ContextProtocol.chatEvent(7L, "hello", null, null);
        check("chat".equals(event.get("type").getAsString()), "event type is chat");
        check(event.get("seq").getAsLong() == 7L, "chat sequence is kept");
        check(!event.has("context_id"), "no id when there is no bundle to fetch");
        JsonObject context = event.getAsJsonObject("context");
        check(!context.get("available").getAsBoolean(), "context is marked unavailable");
        check("sender_unavailable".equals(context.get("reason").getAsString()), "reason names the problem");
        check(!event.has("sender"), "no sender name is invented");
    }

    private static void twoPlayersCorrelateIndependently() {
        PlayerContextCache cache = new PlayerContextCache(8, 60_000L);
        PlayerContext alice = context(1L, "ctx-alice", 5_000L, "uuid-alice", "Alice");
        PlayerContext bob = context(2L, "ctx-bob", 5_000L, "uuid-bob", "Bob");
        cache.put(alice, 5_000L);
        cache.put(bob, 5_000L);

        JsonObject aliceEvent = ContextProtocol.chatEvent(1L, "hi from Alice", "Alice", alice);
        JsonObject bobEvent = ContextProtocol.chatEvent(2L, "hi from Bob", "Bob", bob);
        String aliceId = aliceEvent.get("context_id").getAsString();
        String bobId = bobEvent.get("context_id").getAsString();
        check(!aliceId.equals(bobId), "close-together speakers get distinct ids");

        PlayerContextCache.Lookup aliceAgain = cache.get(aliceId, 5_100L);
        PlayerContextCache.Lookup bobAgain = cache.get(bobId, 5_100L);
        check(aliceAgain.ok() && "uuid-alice".equals(aliceAgain.context.uuid), "alice maps to alice");
        check(bobAgain.ok() && "uuid-bob".equals(bobAgain.context.uuid), "bob maps to bob");
        check("Alice".equals(aliceEvent.getAsJsonObject("context").get("name").getAsString()),
                "the event summary carries Alice");
        check("Bob".equals(bobEvent.getAsJsonObject("context").get("name").getAsString()),
                "the event summary carries Bob");
        check(cache.get("ctx-alice-copy", 5_100L).status == PlayerContextCache.Status.NOT_FOUND,
                "a guessed id does not resolve");
    }

    private static void replyShapesAreStructured() {
        PlayerContextCache cache = new PlayerContextCache(2, 50_000L);
        PlayerContext alice = context(1L, "ctx-alice", 1_000L, "uuid-alice", "Alice");
        cache.put(alice, 1_000L);

        JsonObject ok = ContextProtocol.contextReply("ctx-alice", cache.get("ctx-alice", 1_500L), cache);
        check("ok".equals(ok.get("status").getAsString()), "fetch status is ok");
        check(InterfaceConstants.CONTEXT_SCHEMA.equals(
                ok.getAsJsonObject("context").get("schema").getAsString()), "bundle carries the schema");
        check(ok.getAsJsonObject("cache").get("capacity").getAsInt() == 2, "cache limit is visible");
        check(ok.getAsJsonObject("cache").get("ttlMillis").getAsLong() == 50_000L, "cache ttl is visible");

        JsonObject missing = ContextProtocol.contextReply("ctx-nope", cache.get("ctx-nope", 1_500L), cache);
        check("not_found".equals(missing.get("status").getAsString()), "missing fetch is structured");
        check(!missing.has("context"), "missing fetch has no context payload");

        PlayerContextCache shortLived = new PlayerContextCache(1, 10L);
        shortLived.put(context(2L, "ctx-short", 1_000L, "uuid-short", "Short"), 1_000L);
        JsonObject expired = ContextProtocol.contextReply("ctx-short",
                shortLived.get("ctx-short", 2_000L), shortLived);
        check("expired".equals(expired.get("status").getAsString()), "expired fetch is structured");
        check(!expired.has("context"), "expired fetch has no context payload");
        check(expired.has("capturedAt"), "expired fetch reports when it was captured");
    }

    private static void statsReportLimits() {
        PlayerContextCache cache = new PlayerContextCache(3, 2_000L);
        cache.put(context(1L, "ctx-a", 1_000L, "uuid-a", "A"), 1_000L);
        cache.get("ctx-a", 1_100L);
        cache.get("ctx-x", 1_100L);

        JsonObject stats = ContextProtocol.statsReply(cache);
        JsonObject cacheObject = stats.getAsJsonObject("cache");
        check(cacheObject.get("capacity").getAsInt() == 3, "capacity reported");
        check(cacheObject.get("ttlMillis").getAsLong() == 2_000L, "ttl reported");
        check(cacheObject.get("size").getAsInt() == 1, "size reported");
        check(cacheObject.get("hits").getAsLong() == 1L, "hits reported");
        check(cacheObject.get("misses").getAsLong() == 1L, "misses reported");
    }

    /**
     * Regression for the receipt-vs-broadcast timing: a slow chat filter can
     * delay the broadcast while the player moves, but the bundle fetched for
     * the event must still describe the player at packet receipt.
     */
    private static void receiptFreezesTheTransform() {
        PlayerContextCache cache = new PlayerContextCache(4, 60_000L);
        ChatReceipts receipts = new ChatReceipts(4, 60_000L);
        String key = ChatReceipts.key("uuid-alice", 4242L);

        // receipt at t=1000, while Alice stands at x=1
        PlayerContext atReceipt = contextAt(1L, "ctx-alice", 1_000L, "uuid-alice", "Alice", 1.0D);
        receipts.put(key, atReceipt, 1_000L);

        // the filter is slow; the broadcast arrives at t=9000 and Alice has run to x=50
        PlayerContext atBroadcast = contextAt(2L, "ctx-late", 9_000L, "uuid-alice", "Alice", 50.0D);
        PlayerContext published = receipts.take(key, 9_000L);
        check(published == atReceipt, "the receipt capture is the one correlated");
        check(published.x == 1.0D, "the bundle describes receipt time, not broadcast time");
        check(published.x != atBroadcast.x, "the later transform is never substituted");

        // onChatMessage publishes the consumed receipt under the same id
        cache.put(published, 9_000L);
        PlayerContextCache.Lookup lookup = cache.get(published.contextId, 9_000L);
        check(lookup.ok(), "the receipt bundle is fetchable after the broadcast");
        check(lookup.context.x == 1.0D, "the published bundle keeps the receipt-time transform");
    }

    private static void receiptsAreSingleUseAndExact() {
        ChatReceipts receipts = new ChatReceipts(4, 60_000L);
        String key = ChatReceipts.key("uuid-alice", 7L);
        receipts.put(key, context(1L, "ctx-alice", 1_000L, "uuid-alice", "Alice"), 1_000L);

        PlayerContext taken = receipts.take(key, 1_100L);
        check(taken != null && "ctx-alice".equals(taken.contextId), "the receipt is consumed once");
        check(receipts.take(key, 1_200L) == null, "a receipt is used at most once");
        check(receipts.take(ChatReceipts.key("uuid-bob", 7L), 1_200L) == null,
                "another player's key never matches");
        check(receipts.take(ChatReceipts.key("uuid-alice", 8L), 1_200L) == null,
                "another message's salt never matches");
        check(receipts.matched() == 1L, "matches are counted");
    }

    private static void receiptsExpire() {
        ChatReceipts receipts = new ChatReceipts(4, 1_000L);
        String key = ChatReceipts.key("uuid-alice", 7L);
        receipts.put(key, context(1L, "ctx-alice", 10_000L, "uuid-alice", "Alice"), 10_000L);

        check(receipts.take(key, 11_000L) != null, "an age equal to the ttl still matches");
        receipts.put(key, context(2L, "ctx-alice", 20_000L, "uuid-alice", "Alice"), 20_000L);
        check(receipts.take(key, 21_001L) == null, "a receipt older than the ttl is dropped");
        check(receipts.expired() == 1L, "expiry is counted");
        receipts.put(key, context(3L, "ctx-bob", 30_000L, "uuid-bob", "Bob"), 30_000L);
        PlayerContext fresh = receipts.take(key, 30_500L);
        check(fresh != null && "ctx-bob".equals(fresh.contextId), "a fresh receipt after expiry matches");
    }

    private static void receiptsEvictOldestFirst() {
        ChatReceipts receipts = new ChatReceipts(2, 60_000L);
        receipts.put(ChatReceipts.key("uuid-a", 1L), context(1L, "ctx-a", 1_000L, "uuid-a", "A"), 1_000L);
        receipts.put(ChatReceipts.key("uuid-b", 2L), context(2L, "ctx-b", 1_000L, "uuid-b", "B"), 1_000L);
        receipts.put(ChatReceipts.key("uuid-c", 3L), context(3L, "ctx-c", 1_000L, "uuid-c", "C"), 1_000L);

        check(receipts.size() == 2, "receipt capacity is a hard limit");
        check(receipts.take(ChatReceipts.key("uuid-a", 1L), 1_100L) == null, "oldest receipt evicted");
        PlayerContext b = receipts.take(ChatReceipts.key("uuid-b", 2L), 1_100L);
        check(b != null && "ctx-b".equals(b.contextId), "second kept");
        PlayerContext c = receipts.take(ChatReceipts.key("uuid-c", 3L), 1_100L);
        check(c != null && "ctx-c".equals(c.contextId), "newest kept");
        check(receipts.evicted() == 1L, "eviction is counted");
    }

    private static PlayerContext context(long seq, String id, long capturedAt, String uuid, String name) {
        return contextAt(seq, id, capturedAt, uuid, name, 1.5D);
    }

    private static PlayerContext contextAt(long seq, String id, long capturedAt, String uuid, String name,
                                           double x) {
        return new PlayerContext(seq, id, capturedAt, 42, InterfaceConstants.CONTEXT_SCHEMA, uuid, name,
                "minecraft:overworld", x, 64.0D, -2.5D, 90.0F, -10.0F,
                ViewTarget.block("minecraft:stone", new int[] {10, 63, 0}, "north", 2.5D,
                        new double[] {10.5, 63.5, -0.25}));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("check failed: " + message);
        }
    }
}
