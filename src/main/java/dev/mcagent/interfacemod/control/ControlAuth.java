package dev.mcagent.interfacemod.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access credentials for the formal same-port control protocol (issue #8).
 *
 * <p>The file is a small JSON document next to the server certificate:
 *
 * <pre>{@code
 * {"version":1,"tokens":[
 *   {"id":"tk_ab12","label":"laptop","salt":"<hex>","hash":"<hex>",
 *    "permissions":["read","write"],"createdAtMillis":...,"expiresAtMillis":null,"revoked":false}
 * ]}
 * }</pre>
 *
 * <p>Only hashes are stored; the one-time secret is returned once, when the
 * token is issued, in the form {@code mca1.<id>.<base64url-secret>}. A token is
 * compared in constant time after a fixed-length digest, so a wrong secret can
 * not be discovered by timing or by prefix. The file itself never contains the
 * secret. The salt and hash use plain SHA-256 over {@code salt || secret}: the
 * secret is 256 bits of randomness, so a password-style KDF would add cost
 * without adding security, and the threat model here is a leaked file, not an
 * offline brute force of a human-chosen password.
 */
public final class ControlAuth {
    public static final String PERMISSION_READ = "read";
    public static final String PERMISSION_WRITE = "write";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TOKEN_PREFIX = "mca1";

    /** One stored credential, without the secret. */
    public static final class Entry {
        public final String id;
        public final String label;
        public final Set<String> permissions;
        public final long createdAtMillis;
        public final Long expiresAtMillis;
        public final boolean revoked;

        Entry(String id, String label, Set<String> permissions, long createdAtMillis,
              Long expiresAtMillis, boolean revoked) {
            this.id = id;
            this.label = label;
            this.permissions = permissions;
            this.createdAtMillis = createdAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.revoked = revoked;
        }

        public boolean has(String permission) {
            return permissions.contains(permission);
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("id", id);
            object.addProperty("label", label);
            JsonArray array = new JsonArray();
            for (String permission : permissions) {
                array.add(permission);
            }
            object.add("permissions", array);
            object.addProperty("createdAtMillis", createdAtMillis);
            if (expiresAtMillis == null) {
                object.add("expiresAtMillis", com.google.gson.JsonNull.INSTANCE);
            } else {
                object.addProperty("expiresAtMillis", expiresAtMillis);
            }
            object.addProperty("revoked", revoked);
            return object;
        }
    }

    /** The result of a successful authentication. */
    public static final class Auth {
        public final String tokenId;
        public final String label;
        public final Set<String> permissions;

        Auth(Entry entry) {
            this.tokenId = entry.id;
            this.label = entry.label;
            this.permissions = Set.copyOf(entry.permissions);
        }

        public boolean canRead() {
            return permissions.contains(PERMISSION_READ);
        }

        public boolean canWrite() {
            return permissions.contains(PERMISSION_WRITE);
        }

        public String label() {
            return label;
        }
    }

    /** Why an authentication attempt failed; the wire answer stays generic. */
    public enum Status {
        OK,
        MALFORMED,
        UNKNOWN_TOKEN,
        REVOKED,
        EXPIRED,
        BAD_SECRET,
        NO_PERMISSIONS
    }

    public static final class Result {
        public final Status status;
        public final Auth auth;

        Result(Status status, Auth auth) {
            this.status = status;
            this.auth = auth;
        }
    }

    private final Path file;
    private final Map<String, StoredToken> tokens = new ConcurrentHashMap<>();
    private final Object ioLock = new Object();

    private static final class StoredToken {
        final Entry entry;
        final byte[] salt;
        final byte[] hash;

        StoredToken(Entry entry, byte[] salt, byte[] hash) {
            this.entry = entry;
            this.salt = salt;
            this.hash = hash;
        }
    }

    public ControlAuth(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Load the file if it exists; a missing file is an empty store, not an error.
     *
     * <p>The parse is atomic: a malformed file leaves the previous store in
     * place and throws, so a bad reload cannot silently drop or replace live
     * credentials.
     */
    public synchronized LoadReport load() throws IOException {
        Map<String, StoredToken> parsed = new ConcurrentHashMap<>();
        if (!Files.isRegularFile(file)) {
            tokens.clear();
            return new LoadReport(true, 0, List.of());
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("control token file is not valid JSON (previous credentials kept): " + file);
        }
        JsonArray array = root.has("tokens") ? root.getAsJsonArray("tokens") : new JsonArray();
        int loaded = 0;
        for (JsonElement element : array) {
            try {
                JsonObject object = element.getAsJsonObject();
                String id = object.get("id").getAsString();
                String label = object.has("label") ? object.get("label").getAsString() : "";
                byte[] salt = hex(object.get("salt").getAsString());
                byte[] hash = hex(object.get("hash").getAsString());
                Set<String> permissions = new LinkedHashSet<>();
                for (JsonElement permission : object.getAsJsonArray("permissions")) {
                    permissions.add(normalizePermission(permission.getAsString()));
                }
                long createdAt = object.has("createdAtMillis")
                        ? object.get("createdAtMillis").getAsLong() : 0L;
                Long expiresAt = object.has("expiresAtMillis") && !object.get("expiresAtMillis").isJsonNull()
                        ? object.get("expiresAtMillis").getAsLong() : null;
                boolean revoked = object.has("revoked") && object.get("revoked").getAsBoolean();
                Entry entry = new Entry(id, label, permissions, createdAt, expiresAt, revoked);
                parsed.put(id, new StoredToken(entry, salt, hash));
                loaded++;
            } catch (RuntimeException exception) {
                // One malformed row must not take down the whole file.
                System.err.println("[mc-agent-interface] skipping malformed control token entry: " + exception);
            }
        }
        tokens.clear();
        tokens.putAll(parsed);
        return new LoadReport(false, loaded, List.of());
    }

    /** Live view of one stored credential, without revealing the secret. */
    public synchronized Entry entry(String id) {
        StoredToken stored = tokens.get(id);
        return stored == null ? null : stored.entry;
    }

    /**
     * Reload the file and report which token ids changed state in a way that
     * must close live sessions: removed, revoked, expired, or a permissions or
     * expiry change. A parse failure propagates and keeps the old store.
     */
    public synchronized List<String> reloadAndFindRevoked() throws IOException {
        Map<String, Entry> previouslyUsable = new LinkedHashMap<>();
        for (Map.Entry<String, StoredToken> entry : tokens.entrySet()) {
            StoredToken stored = entry.getValue();
            if (!stored.entry.revoked && !expired(stored.entry, System.currentTimeMillis())) {
                previouslyUsable.put(entry.getKey(), stored.entry);
            }
        }
        List<String> beforeIds = new ArrayList<>(tokens.keySet());
        load();
        List<String> affected = new ArrayList<>();
        for (String id : beforeIds) {
            StoredToken stored = tokens.get(id);
            if (stored == null) {
                if (previouslyUsable.containsKey(id)) {
                    affected.add(id);
                }
                continue;
            }
            Entry previous = previouslyUsable.get(id);
            if (previous == null) {
                continue; // was not usable before; nothing live to close
            }
            Entry current = stored.entry;
            boolean changed = current.revoked
                    || expired(current, System.currentTimeMillis())
                    || current.permissions.isEmpty()
                    || !current.permissions.equals(previous.permissions)
                    || !java.util.Objects.equals(current.expiresAtMillis, previous.expiresAtMillis);
            if (changed) {
                affected.add(id);
            }
        }
        return affected;
    }

    /** Issue a new token and return the secret exactly once. */
    public synchronized String issue(String label, Set<String> permissions, Long ttlSeconds) throws IOException {
        Set<String> normalized = new LinkedHashSet<>();
        for (String permission : permissions) {
            normalized.add(normalizePermission(permission));
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("a token needs at least one permission");
        }
        String id = "tk_" + randomHex(6);
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = hash(salt, secret);
        long now = System.currentTimeMillis();
        Long expiresAt = ttlSeconds == null || ttlSeconds <= 0 ? null : now + ttlSeconds * 1000L;
        Entry entry = new Entry(id, label == null ? "" : label, normalized, now, expiresAt, false);
        tokens.put(id, new StoredToken(entry, salt, hash));
        save();
        return TOKEN_PREFIX + "." + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    /** Revoke one token; returns true when the id exists. */
    public synchronized boolean revoke(String id) throws IOException {
        StoredToken stored = tokens.get(id);
        if (stored == null) {
            return false;
        }
        Entry old = stored.entry;
        Entry revoked = new Entry(old.id, old.label, old.permissions, old.createdAtMillis,
                old.expiresAtMillis, true);
        tokens.put(id, new StoredToken(revoked, stored.salt, stored.hash));
        save();
        return true;
    }

    public synchronized List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        for (StoredToken stored : tokens.values()) {
            list.add(stored.entry);
        }
        list.sort((left, right) -> left.id.compareTo(right.id));
        return list;
    }

    public synchronized int size() {
        return tokens.size();
    }

    /** Authenticate a presented secret. Never reveals which part was wrong. */
    public synchronized Result authenticate(String presented, long nowMillis) {
        if (presented == null || presented.isBlank()) {
            return new Result(Status.MALFORMED, null);
        }
        String[] parts = presented.split("\\.", 3);
        if (parts.length != 3 || !TOKEN_PREFIX.equals(parts[0])) {
            return new Result(Status.MALFORMED, null);
        }
        StoredToken stored = tokens.get(parts[1]);
        if (stored == null) {
            // Still burn a comparison so an unknown id is not faster to reject.
            hash(new byte[16], new byte[32]);
            return new Result(Status.UNKNOWN_TOKEN, null);
        }
        if (stored.entry.revoked) {
            return new Result(Status.REVOKED, null);
        }
        if (expired(stored.entry, nowMillis)) {
            return new Result(Status.EXPIRED, null);
        }
        byte[] secret;
        try {
            secret = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            return new Result(Status.MALFORMED, null);
        }
        if (!MessageDigest.isEqual(hash(stored.salt, secret), stored.hash)) {
            return new Result(Status.BAD_SECRET, null);
        }
        if (stored.entry.permissions.isEmpty()) {
            return new Result(Status.NO_PERMISSIONS, null);
        }
        return new Result(Status.OK, new Auth(stored.entry));
    }

    private boolean expired(Entry entry, long nowMillis) {
        return entry.expiresAtMillis != null && nowMillis > entry.expiresAtMillis;
    }

    private void save() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray array = new JsonArray();
        for (StoredToken stored : tokens.values()) {
            JsonObject object = stored.entry.toJson();
            object.addProperty("salt", toHex(stored.salt));
            object.addProperty("hash", toHex(stored.hash));
            array.add(object);
        }
        root.add("tokens", array);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        synchronized (ioLock) {
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomic) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static String normalizePermission(String permission) {
        String value = permission == null ? "" : permission.trim().toLowerCase(Locale.ROOT);
        if (!value.equals(PERMISSION_READ) && !value.equals(PERMISSION_WRITE)) {
            throw new IllegalArgumentException("unknown permission: " + permission + " (use read or write)");
        }
        return value;
    }

    static byte[] hash(byte[] salt, byte[] secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(secret);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return toHex(value);
    }

    private static byte[] hex(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    /** Outcome of loading or reloading the token file. */
    public static final class LoadReport {
        public final boolean missing;
        public final int loaded;
        public final List<String> revoked;

        LoadReport(boolean missing, int loaded, List<String> revoked) {
            this.missing = missing;
            this.loaded = loaded;
            this.revoked = revoked;
        }
    }
}
