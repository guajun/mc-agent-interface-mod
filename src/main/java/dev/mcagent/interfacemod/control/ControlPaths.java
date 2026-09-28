package dev.mcagent.interfacemod.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Path safety for operations a remote credential can trigger.
 *
 * <p>A control token is not a filesystem capability. The snapshot name is a
 * directory name inside the server's own snapshots root, never a path: an
 * absolute path, a {@code ..} segment, a separator or a symlink that escapes
 * the root is rejected before anything is created.
 */
public final class ControlPaths {
    private static final Set<String> RESERVED_WINDOWS_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private ControlPaths() {
    }

    /**
     * Validate a snapshot name as one safe directory segment and return it.
     *
     * @throws IllegalArgumentException when the name is empty, too long, a
     *         traversal, an absolute path, contains a separator, or is a
     *         reserved device name on Windows.
     */
    public static String requireSafeSnapshotName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 64) {
            throw new IllegalArgumentException("snapshot name must be 1..64 characters");
        }
        if (value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("snapshot name cannot be a path traversal");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean safe = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_' || character == '.';
            if (!safe) {
                throw new IllegalArgumentException(
                        "snapshot name may only contain letters, digits, '.', '-' and '_'");
            }
        }
        if (value.startsWith(".")) {
            throw new IllegalArgumentException("snapshot name cannot start with '.'");
        }
        if (value.endsWith(".")) {
            // Windows strips trailing dots, which would alias another name.
            throw new IllegalArgumentException("snapshot name cannot end with '.'");
        }
        String base = value.toLowerCase(Locale.ROOT);
        int dot = base.indexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        if (RESERVED_WINDOWS_NAMES.contains(base)) {
            throw new IllegalArgumentException("snapshot name '" + value + "' is a reserved device name");
        }
        return value;
    }

    /**
     * Resolve the snapshot directory for a validated name and prove it stays
     * inside {@code <serverDir>/snapshots}. The root is created; the leaf is
     * not, so the caller's own creation is the first filesystem change.
     */
    public static Path snapshotDirectory(Path serverDir, String name) throws IOException {
        String safe = requireSafeSnapshotName(name);
        Path root = serverDir.toAbsolutePath().normalize().resolve("snapshots");
        Files.createDirectories(root);
        Path rootReal = root.toRealPath();
        Path candidate = root.resolve(safe).normalize();
        if (!root.equals(candidate.getParent())) {
            throw new IllegalArgumentException("snapshot directory escapes the snapshots root");
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(candidate)) {
                throw new IOException("snapshot directory is a symlink; refusing to write through it");
            }
            Path candidateReal = candidate.toRealPath();
            if (!candidateReal.startsWith(rootReal)) {
                throw new IOException("snapshot directory resolves outside the snapshots root");
            }
        }
        return candidate;
    }

    /** Re-check after creation that a directory really is inside the root. */
    public static void requireInsideRoot(Path root, Path directory) throws IOException {
        Path rootReal = root.toRealPath();
        Path directoryReal = directory.toRealPath();
        if (!directoryReal.startsWith(rootReal)) {
            throw new IOException("directory resolves outside the snapshots root");
        }
    }
}
