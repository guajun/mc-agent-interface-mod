package dev.mcagent.interfacemod.control;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-side administration for the formal control transport: the only way to
 * issue and revoke daemon credentials. The secret is printed to the command
 * source exactly once and never stored; {@code list} shows no secret material.
 *
 * <p>Available on a dedicated server console and to permission-level 3
 * (admin) command sources. It intentionally does not exist over the control
 * protocol itself: a credential can never mint or revoke credentials.
 */
public final class ControlCommands {
    private ControlCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(build()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("mcagent")
                .then(Commands.literal("control")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("reload")
                                .requires(source -> admin(source))
                                .executes(context -> reload(context.getSource())))
                        .then(Commands.literal("token")
                                .requires(source -> admin(source))
                                .then(Commands.literal("list")
                                        .executes(context -> list(context.getSource())))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("label", StringArgumentType.word())
                                                .then(Commands.argument("permissions", StringArgumentType.word())
                                                        .executes(context -> add(context.getSource(),
                                                                StringArgumentType.getString(context, "label"),
                                                                StringArgumentType.getString(context, "permissions"),
                                                                0))
                                                        .then(Commands.argument("ttlSeconds",
                                                                        IntegerArgumentType.integer(60))
                                                                .executes(context -> add(context.getSource(),
                                                                        StringArgumentType.getString(context, "label"),
                                                                        StringArgumentType.getString(context, "permissions"),
                                                                        IntegerArgumentType.getInteger(context,
                                                                                "ttlSeconds")))))))
                                .then(Commands.literal("revoke")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(context -> revoke(context.getSource(),
                                                        StringArgumentType.getString(context, "id")))))));
    }

    private static boolean admin(CommandSourceStack source) {
        // Credential management is owner-only: a remote write credential is
        // capped at ADMIN level and can never reach this subtree, including
        // through `/execute` or `/function` chains (they inherit the source
        // permission set).
        return source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private static int status(CommandSourceStack source) {
        ControlServer server = ControlServer.current();
        if (server == null || !ControlServer.enabled()) {
            feedback(source, "mc-agent control transport is disabled; enable with -Dmcagent.control=true");
            return 0;
        }
        var state = ControlServer.state();
        feedback(source, "mc-agent control " + ControlServer.TRANSPORT
                + " protocol=" + ControlServer.CONTROL_PROTOCOL_VERSION);
        feedback(source, "instanceId=" + state.get("instanceId").getAsString()
                + "  runId=" + state.get("runId").getAsString());
        feedback(source, "tls=" + (state.get("tlsFingerprint").isJsonNull()
                ? "unavailable" : state.get("tlsFingerprint").getAsString()));
        feedback(source, "sessions=" + state.get("sessions").getAsInt()
                + "  tokens=" + state.get("tokens").getAsInt()
                + "  authFailures=" + state.get("authFailures").getAsLong()
                + "  eventSeq=" + state.get("eventSeq").getAsLong());
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        ControlServer server = ControlServer.current();
        if (server == null || !ControlServer.enabled()) {
            feedback(source, "mc-agent control transport is disabled");
            return 0;
        }
        try {
            List<String> revoked = server.reloadTokensAndCloseRevoked();
            feedback(source, "reloaded " + server.auth().size() + " credential(s); closed sessions for "
                    + revoked.size() + " revoked credential(s)");
            return revoked.size();
        } catch (IOException exception) {
            feedback(source, "reload failed: " + exception);
            return 0;
        }
    }

    private static int list(CommandSourceStack source) {
        ControlServer server = ControlServer.current();
        if (server == null) {
            feedback(source, "mc-agent control transport is not attached");
            return 0;
        }
        List<ControlAuth.Entry> entries = server.auth().entries();
        if (entries.isEmpty()) {
            feedback(source, "no control credentials; use /mcagent control token add <label> read|write");
            return 0;
        }
        long now = System.currentTimeMillis();
        for (ControlAuth.Entry entry : entries) {
            feedback(source, entry.id + "  label=" + (entry.label.isEmpty() ? "-" : entry.label)
                    + "  permissions=" + String.join("+", entry.permissions)
                    + "  created=" + entry.createdAtMillis
                    + (entry.expiresAtMillis == null ? "" : "  expires=" + entry.expiresAtMillis
                    + (now > entry.expiresAtMillis ? " (expired)" : ""))
                    + (entry.revoked ? "  REVOKED" : ""));
        }
        return entries.size();
    }

    private static int add(CommandSourceStack source, String label, String permissions, int ttlSeconds) {
        ControlServer server = ControlServer.current();
        if (server == null) {
            feedback(source, "mc-agent control transport is not attached");
            return 0;
        }
        try {
            Set<String> parsed = new LinkedHashSet<>();
            for (String piece : permissions.toLowerCase(Locale.ROOT).split("[+,]")) {
                if (!piece.isBlank()) {
                    parsed.add(ControlAuth.normalizePermission(piece));
                }
            }
            String secret = server.auth().issue(label, parsed, ttlSeconds <= 0 ? null : (long) ttlSeconds);
            feedback(source, "issued credential for '" + label + "' permissions=" + String.join("+", parsed)
                    + (ttlSeconds <= 0 ? "" : " ttl=" + ttlSeconds + "s"));
            // A trailing space keeps console/RCON consumers from gluing the
            // next feedback line onto the secret when parsing it.
            feedback(source, "SECRET (shown once): " + secret + " ");
            feedback(source, "give this to the daemon operator; the server stores only a hash");
            return 1;
        } catch (IllegalArgumentException | IOException exception) {
            feedback(source, "token add failed: " + exception);
            return 0;
        }
    }

    private static int revoke(CommandSourceStack source, String id) {
        ControlServer server = ControlServer.current();
        if (server == null) {
            feedback(source, "mc-agent control transport is not attached");
            return 0;
        }
        try {
            boolean removed = server.revokeAndClose(id);
            if (!removed) {
                feedback(source, "no credential with id " + id);
                return 0;
            }
            feedback(source, "revoked " + id + "; its live sessions are being closed");
            return 1;
        } catch (IOException exception) {
            feedback(source, "revoke failed: " + exception);
            return 0;
        }
    }

    private static void feedback(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
