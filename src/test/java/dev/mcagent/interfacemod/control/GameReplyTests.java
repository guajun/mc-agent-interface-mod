package dev.mcagent.interfacemod.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Outcome-channel regressions for the game replies, without a running world. */
public final class GameReplyTests {
    public void run() {
        for (String message : new String[] {
                "a radius needs at least one player; use ENTITIES for everything",
                "cannot list snapshots: java.io.IOException: denied"}) {
            JsonObject error = new JsonObject();
            error.addProperty("type", "error");
            error.addProperty("message", message);
            for (boolean legacyLine : new boolean[] {false, true}) {
                RecordingReply reply = new RecordingReply();
                if (legacyLine) {
                    GameReplies.line(reply).accept(error.toString());
                } else {
                    GameReplies.complete(reply, error);
                }
                require(reply.okCount == 0 && reply.failCount == 1, "error must use fail exactly once");
                require("game_error".equals(reply.code) && message.equals(reply.message),
                        "game error code and original message must survive");
            }
        }
        for (String answer : new String[] {
                "{\"type\":\"entities\",\"radius\":0,\"entities\":[]}",
                "{\"type\":\"entities\",\"radius\":10,\"entities\":[{\"uuid\":\"fixture\"}]}",
                "{\"type\":\"snapshots\",\"snapshots\":[]}",
                "{\"type\":\"player\",\"found\":false,\"error\":\"unknown player\"}",
                "{\"type\":\"context\",\"found\":false,\"error\":\"expired\"}"}) {
            RecordingReply reply = new RecordingReply();
            JsonObject result = JsonParser.parseString(answer).getAsJsonObject();
            GameReplies.complete(reply, result);
            require(reply.okCount == 1 && reply.failCount == 0 && result.equals(reply.result),
                    "successful and found:false query payloads must stay intact");
        }
        RecordingReply unreadable = new RecordingReply();
        GameReplies.line(unreadable).accept("not JSON");
        require(unreadable.okCount == 0 && unreadable.failCount == 1
                && "internal".equals(unreadable.code), "unreadable legacy answer must fail");
        System.out.println("game reply regressions passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingReply implements ControlOps.Reply {
        int okCount;
        int failCount;
        String code;
        String message;
        JsonElement result;

        public boolean started() { return true; }
        public void ok(JsonElement result) { okCount++; this.result = result; }
        public void fail(String code, String message, boolean retryable, boolean resultUnknown) {
            failCount++;
            this.code = code;
            this.message = message;
            require(!retryable && !resultUnknown, "known game errors must not suggest a retry or unknown result");
        }
    }
}
