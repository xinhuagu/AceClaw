package dev.aceclaw.security;

import java.nio.file.Path;
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
     * Matched against {@link Path#getFileName()} so siblings like
     * {@code dotenv-notes.md} don't trigger.
     */
    private static final Set<String> SENSITIVE_FILENAMES = Set.of(
            ".env",
            "credentials.json",
            ".netrc",
            "id_rsa",
            "id_ed25519");

    /**
     * Path segments that, when present anywhere in the path, mark the file
     * as sensitive. {@code .ssh} catches both {@code ~/.ssh/id_rsa} and a
     * cloned {@code ./.ssh/config}; {@code .git/config} catches per-repo
     * git config writes (a common credential-store smuggling vector).
     */
    private static final Set<String> SENSITIVE_PATH_SEGMENTS = Set.of(
            ".ssh",
            ".aws",
            ".gnupg");

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
        // 1. Structural hard denial — overrides every mode, including auto-accept.
        var denial = checkHardDenial(capability);
        if (denial != null) return denial;

        // 2. READ is always auto-approved in all modes.
        if (capability.risk() == PermissionLevel.READ) {
            return new PermissionDecision.Approved();
        }

        // 3. Mode-based decision. `description` is the dispatcher's rich
        //    human-readable phrasing — surface it in the prompt so the user
        //    sees what the tool actually intends to do.
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
     * Returns a {@link PermissionDecision.Denied} when the capability targets
     * a structurally sensitive resource, or {@code null} when no hard rule
     * applies. Today only file writes and deletes have hard rules; other
     * variants fall through to the mode-driven path. Adding a rule here is
     * the way to encode "this resource is sensitive no matter the mode."
     */
    private static PermissionDecision checkHardDenial(Capability capability) {
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
    private static PermissionDecision denyIfSensitivePath(Path rawPath, String verb) {
        var path = rawPath.normalize();
        // Match the basename (e.g. ".env", "credentials.json")
        var fileName = path.getFileName();
        if (fileName != null) {
            String name = fileName.toString();
            if (SENSITIVE_FILENAMES.contains(name)) {
                return deniedSensitive(verb, path);
            }
            // .env, .env.local, .env.production all match — but NOT
            // dotenv-notes.md or env-template (basename must START with .env).
            if (name.startsWith(".env")) {
                return deniedSensitive(verb, path);
            }
        }

        // Match any path segment for things like .ssh/, .aws/credentials.
        for (Path segment : path) {
            if (SENSITIVE_PATH_SEGMENTS.contains(segment.toString())) {
                return deniedSensitive(verb, path);
            }
        }

        // .git/config is sensitive (credential-helper config sits here); the
        // rest of .git/ is fine to write so we cannot deny on segment ".git"
        // alone — gradle/git plugins legitimately rewrite .git/HEAD,
        // packed-refs, etc. during normal operation.
        var nameOnly = fileName == null ? "" : fileName.toString();
        if ("config".equals(nameOnly)) {
            for (Path segment : path) {
                if (".git".equals(segment.toString())) {
                    return deniedSensitive(verb, path);
                }
            }
        }

        // Absolute /etc/* — system-wide config. Tools that legitimately
        // need to touch /etc must be invoked outside the agent.
        if (path.isAbsolute()) {
            var root = path.getRoot();
            if (root != null && path.getNameCount() > 0
                    && "etc".equals(path.getName(0).toString())) {
                return deniedSensitive(verb, path);
            }
        }

        return null;
    }

    private static PermissionDecision deniedSensitive(String verb, Path path) {
        return new PermissionDecision.Denied(
                "Refusing to " + verb + " a sensitive path: " + path
                        + " (this rule overrides all permission modes)");
    }

    private static String formatPrompt(Capability capability, String description) {
        String action = switch (capability.risk()) {
            case WRITE -> "write to";
            case EXECUTE -> "execute";
            case DANGEROUS -> "perform a potentially destructive action:";
            default -> "access";
        };
        return String.format("The agent wants to %s: %s", action, description);
    }
}
