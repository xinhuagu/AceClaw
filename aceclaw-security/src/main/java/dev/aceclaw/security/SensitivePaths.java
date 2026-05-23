package dev.aceclaw.security;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Canonical "do not write or delete this file" rule set.
 *
 * <p>Decoupled from any {@link PermissionPolicy} implementation so callers
 * that need the same sensitivity classification — notably MCP-side capability
 * inference at the adapter boundary — don't have to depend on a specific
 * policy class. {@link DefaultPermissionPolicy} delegates here for its
 * structural-denial layer; {@code McpCapabilityInference} (in
 * {@code aceclaw-mcp}) calls {@link #matches(Path)} directly to disambiguate
 * move/copy source vs destination sensitivity.
 *
 * <h3>What is "sensitive"</h3>
 *
 * Files that hold credentials, signing keys, or operator-critical
 * configuration. The rule set is intentionally narrow — a write/delete
 * blocked here is "the agent shouldn't be doing this regardless of mode or
 * approval state" rather than "this is unusual."
 *
 * <h3>Matching rules</h3>
 *
 * <ul>
 *   <li><b>Basename</b> match against a fixed lowercased set
 *       ({@code .env}, {@code credentials.json}, {@code id_rsa}, …).</li>
 *   <li><b>{@code .env*} prefix</b> on the basename so {@code .env},
 *       {@code .env.local}, {@code .env.production} all match while
 *       {@code dotenv-notes.md} does not.</li>
 *   <li><b>Path segment</b> match anywhere in the path for credential-
 *       bearing directories ({@code .ssh}, {@code .aws}, {@code .gnupg},
 *       {@code .kube}, {@code .docker}).</li>
 *   <li><b>{@code .git/config}</b> specifically — the rest of {@code .git/}
 *       is fine to write (gradle/git plugins legitimately do).</li>
 *   <li><b>{@code /etc/}</b> as an absolute prefix.</li>
 * </ul>
 *
 * <p>All string comparisons use {@link Locale#ROOT} so a case-insensitive
 * filesystem (default macOS APFS, Windows NTFS) can't be bypassed with
 * {@code .ENV} or {@code Credentials.json}. The path is
 * {@link Path#normalize() normalized} before matching so {@code /tmp/../etc/hosts}
 * resolves to {@code /etc/hosts}.
 *
 * <h3>Symlink resolution</h3>
 *
 * The matcher is run on the lexical path first; if that says "not sensitive",
 * the path is canonicalized via {@link Path#toRealPath} and the rules run
 * again on the resolved form. So writing through {@code /tmp/safe-link}
 * (a symlink to {@code ~/.ssh/config}) is denied just like writing
 * {@code ~/.ssh/config} directly. For paths that don't yet exist (the common
 * case for new file writes), the deepest existing prefix is resolved and the
 * missing tail re-appended — that's enough to catch the "symlinked
 * directory" trick (e.g. {@code /tmp/safe-dir/id_rsa} where
 * {@code safe-dir → ~/.ssh}).
 *
 * <p>This is still best-effort, not airtight: a TOCTOU window between the
 * permission check and the actual write can swap the symlink underneath.
 * True enforcement needs the OS-level sandbox (Seatbelt / bubblewrap) that
 * {@code runtime-governance.md} still marks as 🚧. Symlink resolution here
 * closes the easy bypasses — a malicious or buggy MCP server putting a
 * safe-looking name in front of a sensitive target — while keeping the rule
 * set agnostic to which adversary model is in play.
 */
public final class SensitivePaths {

    private SensitivePaths() {}

    /**
     * File names whose <em>basename</em> is sensitive in every project layout.
     * Matched against {@link Path#getFileName()} case-insensitively.
     * Store these entries lowercased; the comparison lowercases the input.
     */
    private static final Set<String> SENSITIVE_FILENAMES = Set.of(
            ".env",
            "credentials.json",
            ".netrc",
            "id_rsa",
            "id_ed25519",
            "id_ecdsa",
            ".npmrc",
            ".pypirc",
            "service-account.json");

    /**
     * Path segments that, when present anywhere in the path, mark the file
     * as sensitive. {@code .ssh} catches both {@code ~/.ssh/id_rsa} and a
     * cloned {@code ./.ssh/config}; {@code .git/config} catches per-repo
     * git config writes (a common credential-store smuggling vector).
     */
    private static final Set<String> SENSITIVE_PATH_SEGMENTS = Set.of(
            ".ssh",
            ".aws",
            ".gnupg",
            ".kube",
            ".docker");

    /**
     * Returns {@code true} when {@code rawPath} resolves to a path that
     * should not be written or deleted by the agent. Case-insensitive,
     * normalize-before-match, segment-level (not substring) comparisons —
     * see class-level javadoc for the full rule set.
     *
     * <p>Runs the lexical rules on the input path first; if they don't fire,
     * canonicalizes through symlinks and re-runs. Both checks must pass for
     * a path to be considered safe. Lexical-first means a lexically-sensitive
     * path (e.g. {@code /etc/hosts}) is still denied even if filesystem
     * resolution would move it away from the rule (e.g. macOS resolves
     * {@code /etc} to {@code /private/etc}).
     */
    public static boolean matches(Path rawPath) {
        if (rawPath == null) return false;
        if (matchesLexical(rawPath)) return true;
        // Lexical rules cleared — but a symlink-fronted alias could still
        // route through to a sensitive target. Resolve and try again.
        Path resolved = resolveSymlinksBestEffort(rawPath);
        if (resolved != null && !resolved.equals(rawPath.normalize())) {
            return matchesLexical(resolved);
        }
        return false;
    }

    /**
     * Lexical-only sensitivity check. Pure function over the normalized path
     * string — no filesystem IO, no symlink resolution. Public for callers
     * that need the rule set without the side effects (audit dry-runs,
     * symbolic analysis, etc.).
     */
    public static boolean matchesLexical(Path rawPath) {
        if (rawPath == null) return false;
        var path = rawPath.normalize();
        var fileName = path.getFileName();
        String nameLower = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
        if (fileName != null) {
            if (SENSITIVE_FILENAMES.contains(nameLower)) return true;
            // .env, .env.local, .env.production all match — but NOT
            // dotenv-notes.md or env-template (basename must START with .env).
            if (nameLower.startsWith(".env")) return true;
        }

        // Single pass over segments: detect (a) any sensitive segment like
        // .ssh/.aws/.gnupg/.kube/.docker (b) the presence of .git/ so the
        // basename=="config" rule below can fire without re-iterating.
        // .git/config alone is sensitive — the rest of .git/ (HEAD,
        // packed-refs, etc.) is touched by legitimate git plugins.
        boolean sawDotGit = false;
        for (Path segment : path) {
            String seg = segment.toString().toLowerCase(Locale.ROOT);
            if (SENSITIVE_PATH_SEGMENTS.contains(seg)) return true;
            if (".git".equals(seg)) sawDotGit = true;
        }
        if (sawDotGit && "config".equals(nameLower)) return true;

        // Absolute /etc/* — system-wide config. The agent's intent is what
        // matters here ("write /etc/hosts" is a system-config write regardless
        // of host OS), so use an OS-independent string check rather than
        // Path.isAbsolute() which returns false on Windows for Unix-style
        // paths (no drive letter). Render with forward slashes, lowercase,
        // then prefix-check.
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.equals("/etc") || normalized.startsWith("/etc/")) {
            return true;
        }

        return false;
    }

    /**
     * Canonicalizes {@code raw} through symlinks, tolerating non-existent
     * tail segments. Used by {@link #matches} to catch writes routed through
     * a safe-looking name that aliases to a sensitive target.
     *
     * <p>Algorithm: try {@link Path#toRealPath} on the full path first. If
     * that throws (commonly because the file doesn't exist yet — a new-file
     * write), walk parents up until one resolves, then re-append the
     * skipped trailing segments. Returns {@code null} when nothing along the
     * path resolves or any unrecoverable IO error occurs — the caller treats
     * that as "no further information" and the lexical check alone is taken
     * as authoritative.
     *
     * <p>Package-private for testing the corner cases (symlink to sensitive
     * target, symlink in the middle of the path, fully non-existent path).
     */
    static Path resolveSymlinksBestEffort(Path raw) {
        if (raw == null) return null;
        Path probe = raw;
        Path appended = null;
        // Walk up until a parent resolves on disk. The accumulated `appended`
        // captures the segments we popped off, in original order, to be
        // re-attached to the real path of the deepest existing ancestor.
        while (probe != null) {
            try {
                Path real = probe.toRealPath();
                return appended == null ? real : real.resolve(appended);
            } catch (IOException missingOrUnreadable) {
                Path name = probe.getFileName();
                if (name != null) {
                    appended = appended == null ? name : name.resolve(appended);
                }
                probe = probe.getParent();
            } catch (SecurityException sandboxRefusedRead) {
                // A SecurityManager (or platform sandbox) blocked our probe.
                // Don't crash the permission check — fall back to lexical
                // and let the caller's other defenses kick in.
                return null;
            }
        }
        return null;
    }
}
