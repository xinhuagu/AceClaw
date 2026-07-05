package dev.aceclaw.security;

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
     */
    public static boolean matches(Path rawPath) {
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
}
