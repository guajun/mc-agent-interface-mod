package dev.mcagent.interfacemod.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/**
 * The game-facing half of the control protocol: it turns one authenticated
 * control request into one game operation and reports the outcome.
 *
 * <p>Implemented by the server vantage ({@code ServerCore}) and by the tests'
 * stub. Nothing here knows about sockets, TLS or credentials, which is what
 * keeps the game operations on the game thread while the network code stays on
 * Netty's event loops.
 */
public interface ControlOps {
    /** Operations that change game state; they get a write sequence number. */
    Set<String> WRITE_OPERATIONS = Set.of("command", "chat", "mark", "snapshot");

    /** All operations the server vantage can answer over the control protocol. */
    Set<String> SERVER_OPERATIONS = Set.of(
            "ping", "capabilities",
            "state", "entities", "player", "context",
            "command", "mark", "wait", "snapshot", "snapshots",
            "request_status",
            "exclusive_acquire", "exclusive_renew", "exclusive_release", "exclusive_status");

    /** Read-only operations the client vantage could serve if it is exposed. */
    Set<String> CLIENT_ONLY_OPERATIONS = Set.of(
            "chat", "screen", "connect", "world", "lan", "record_start", "record_stop");

    /**
     * One request's reply channel. Exactly one of {@code ok}/{@code fail} is
     * called, at most once; {@code started} is called the moment the operation
     * really begins on the game thread, so a timeout can tell "never ran, safe
     * to retry" from "ran, result unknown".
     */
    interface Reply {
        void started();

        void ok(JsonElement result);

        void fail(String code, String message, boolean retryable, boolean resultUnknown);

        default void fail(String code, String message, boolean retryable, boolean resultUnknown,
                          JsonObject details) {
            fail(code, message, retryable, resultUnknown);
        }
    }

    /** Execute one operation. Called off the game thread; implementations schedule it. */
    void execute(String operation, JsonObject params, Reply reply);

    /** Capability tokens this vantage advertises. */
    JsonArray capabilities();

    /** Human-readable instance kind ("server" / "client"). */
    String instanceKind();
}
