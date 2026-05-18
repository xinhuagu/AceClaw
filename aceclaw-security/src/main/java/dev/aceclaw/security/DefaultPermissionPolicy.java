package dev.aceclaw.security;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The default permission policy for the AceClaw agent.
 *
 * <p>Supports four permission modes matching PRD §4.7:
 * <ul>
 *   <li><b>normal</b> (default): Prompts for every dangerous operation (WRITE, EXECUTE, DANGEROUS)</li>
 *   <li><b>accept-edits</b>: Auto-accepts file edits (WRITE), prompts for EXECUTE and DANGEROUS</li>
 *   <li><b>plan</b>: Read-only — denies all WRITE, EXECUTE, and DANGEROUS operations</li>
 *   <li><b>auto-accept</b>: Auto-accepts everything (no permission prompts)</li>
 * </ul>
 *
 * <p>READ-level operations are always auto-approved in all modes.
 *
 * <h3>Structural hard-denial layer</h3>
 *
 * <p>Some capabilities are denied <em>regardless of mode</em> — including
 * {@code auto-accept}. Today that means writes and deletes targeting paths
 * that hold credentials or other operator-critical material:
 * {@code .env*}, {@code .ssh/*}, {@code .git/config}, {@code credentials.json},
 * anything under {@code /etc/}. This is the "cross-cutting rule" surface the
 * runtime-governance doc names: a single check the agent cannot route around
 * by being in the wrong mode. Detection matches on path segments (not
 * substrings) so {@code /repo/notes-on-dotenv.md} is unaffected.
 */
public final class DefaultPermissionPolicy implements PermissionPolicy {

    /** Permission mode constants. */
    public static final String MODE_NORMAL = "normal";
    public static final String MODE_ACCEPT_EDITS = "accept-edits";
    public static final String MODE_PLAN = "plan";
    public static final String MODE_AUTO_ACCEPT = "auto-accept";

    /**
     * File names whose <em>basename</em> is sensitive in every project layout.
     * Matched against {@link Path#getFileName()} case-insensitively (under
     * {@link Locale#ROOT}) so case-insensitive filesystems like the default
     * macOS APFS or Windows NTFS can't be bypassed with {@code .ENV} or
     * {@code Credentials.json} pointing at the same underlying file.
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
     * Compared case-insensitively for the same reason as
     * {@link #SENSITIVE_FILENAMES}.
     */
    private static final Set<String> SENSITIVE_PATH_SEGMENTS = Set.of(
            ".ssh",
            ".aws",
            ".gnupg",
            ".kube",
            ".docker");

    private final String mode;

    /**
     * Creates a policy with the standard "normal" permission rules.
     */
    public DefaultPermissionPolicy() {
        this(MODE_NORMAL);
    }

    /**
     * Creates a policy with the specified permission mode.
     *
     * @param mode one of "normal", "accept-edits", "plan", "auto-accept"
     * @throws IllegalArgumentException if mode is not recognized
     */
    public DefaultPermissionPolicy(String mode) {
        this.mode = switch (mode) {
            case MODE_NORMAL, MODE_ACCEPT_EDITS, MODE_PLAN, MODE_AUTO_ACCEPT -> mode;
            default -> throw new IllegalArgumentException(
                    "Unknown permission mode: '" + mode + "'. " +
                    "Valid modes: normal, accept-edits, plan, auto-accept");
        };
    }

    /**
     * Creates a policy with the legacy auto-approve flag.
     *
     * @param autoApproveAll if true, equivalent to "auto-accept" mode
     * @deprecated Use {@link #DefaultPermissionPolicy(String)} instead
     */
    @Deprecated
    public DefaultPermissionPolicy(boolean autoApproveAll) {
        this(autoApproveAll ? MODE_AUTO_ACCEPT : MODE_NORMAL);
    }

    /**
     * Returns the current permission mode.
     */
    public String mode() {
        return mode;
    }

    @Override
    public PermissionDecision evaluate(Capability capability, Provenance provenance, String description) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(description, "description");

        // READ is always auto-approved in all modes. (Structural hard-denials
        // are run earlier by PermissionManager via evaluateStructural — they
        // do not reach this method.)
        if (capability.risk() == PermissionLevel.READ) {
            return new PermissionDecision.Approved();
        }

        // Mode-based decision. `description` is the dispatcher's rich
        // human-readable phrasing — surface it in the prompt so the user
        // sees what the tool actually intends to do.
        return switch (mode) {
            case MODE_AUTO_ACCEPT -> new PermissionDecision.Approved();

            case MODE_PLAN -> new PermissionDecision.Denied(
                    "Operation denied: plan mode is read-only. " +
                    "Requested: " + description);

            case MODE_ACCEPT_EDITS -> switch (capability.risk()) {
                case READ, WRITE -> new PermissionDecision.Approved();
                case EXECUTE, DANGEROUS -> new PermissionDecision.NeedsUserApproval(
                        formatPrompt(capability, description));
            };

            // MODE_NORMAL (default)
            default -> new PermissionDecision.NeedsUserApproval(formatPrompt(capability, description));
        };
    }

    /**
     * Structural rules that fire before the session-blanket lookup so an
     * "always allow X" approval cannot let the agent route a write to
     * {@code .env} or {@code .ssh/id_rsa} past the policy. Returns
     * {@code null} when no rule applies — see
     * {@link PermissionPolicy#evaluateStructural(Capability)} for the
     * contract.
     */
    @Override
    public PermissionDecision.Denied evaluateStructural(Capability capability) {
        Objects.requireNonNull(capability, "capability");
        return switch (capability) {
            case Capability.FileWrite fw -> denyIfSensitivePath(fw.path(), "write to");
            case Capability.FileDelete fd -> denyIfSensitivePath(fd.path(), "delete");
            default -> null;
        };
    }

    /**
     * Walks {@code path}'s segments looking for sensitive markers. Uses
     * segment-level matching (not {@code String.contains}) so legitimate
     * siblings like {@code repo/notes-on-env.md} don't false-trip.
     *
     * <p>The path is {@link Path#normalize() normalized} first — purely
     * lexical {@code ..} collapsing, no filesystem I/O — so attempts like
     * {@code /tmp/../etc/hosts} cannot route around the {@code /etc/} rule.
     */
    private static PermissionDecision.Denied denyIfSensitivePath(Path rawPath, String verb) {
        return isSensitivePath(rawPath) ? deniedSensitive(verb, rawPath.normalize()) : null;
    }

    /**
     * Returns {@code true} when {@code rawPath} resolves to a path the
     * structural-denial layer refuses to write or delete. Exposed so
     * adapter-side code (notably {@code McpToolBridge}) can disambiguate
     * two-arg ops like {@code move(source, destination)} where either side
     * could be sensitive but only one capability variant can be emitted
     * (Codex P1 follow-up on #495 — "deny moves that remove sensitive
     * sources").
     *
     * <p>The path is {@link Path#normalize() normalized} before matching so
     * {@code /tmp/../etc/hosts} resolves to {@code /etc/hosts}. Basename and
     * segment comparisons are case-insensitive under {@link Locale#ROOT} so
     * case-insensitive filesystems (default macOS APFS, Windows NTFS) can't
     * be bypassed with {@code .ENV}.
     */
    public static boolean isSensitivePath(Path rawPath) {
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

        // Match any path segment for things like .ssh/, .aws/credentials.
        for (Path segment : path) {
            if (SENSITIVE_PATH_SEGMENTS.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        // .git/config is sensitive (credential-helper config sits here); the
        // rest of .git/ is fine to write so we cannot deny on segment ".git"
        // alone — gradle/git plugins legitimately rewrite .git/HEAD,
        // packed-refs, etc. during normal operation.
        if ("config".equals(nameLower)) {
            for (Path segment : path) {
                if (".git".equals(segment.toString().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }

        // Absolute /etc/* — system-wide config. Tools that legitimately
        // need to touch /etc must be invoked outside the agent.
        if (path.isAbsolute()) {
            var root = path.getRoot();
            if (root != null && path.getNameCount() > 0
                    && "etc".equals(path.getName(0).toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private static PermissionDecision.Denied deniedSensitive(String verb, Path path) {
        return new PermissionDecision.Denied(
                "Refusing to " + verb + " a sensitive path: " + path
                        + " (this rule overrides all permission modes)");
    }

    private static String formatPrompt(Capability capability, String description) {
        String action = switch (capability.risk()) {
            case WRITE -> "write to";
            case EXECUTE -> "execute";
            // Pre-#480 this branch ended with a colon and the format string
            // below contributed another, producing "...action:: description".
            // Keep the descriptor and let the format string add the single colon.
            case DANGEROUS -> "perform a potentially destructive action";
            default -> "access";
        };
        return String.format("The agent wants to %s: %s", action, description);
    }
}
