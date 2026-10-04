package dev.mcagent.interfacemod.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.function.Consumer;

/** Converts game-side JSON replies to the formal control result channel. */
public final class GameReplies {
    private GameReplies() {
    }

    public static void complete(ControlOps.Reply reply, JsonObject result) {
        if (result.has("type") && "error".equals(result.get("type").getAsString())) {
            reply.fail("game_error", result.has("message") ? result.get("message").getAsString()
                    : "the game reported an error", false, false);
            return;
        }
        reply.ok(result);
    }

    /** Legacy JSON-lines operations share exactly the same error mapping. */
    public static Consumer<String> line(ControlOps.Reply reply) {
        return answer -> {
            JsonObject parsed;
            try {
                parsed = JsonParser.parseString(answer).getAsJsonObject();
            } catch (RuntimeException exception) {
                reply.fail("internal", "unreadable answer from the game: " + exception, false, false);
                return;
            }
            complete(reply, parsed);
        };
    }
}
