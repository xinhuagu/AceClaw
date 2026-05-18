package dev.aceclaw.security;

import java.nio.file.Path;
import java.util.Objects;

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
            case Capability.FileMove fm -> {
                // Both endpoints matter. Destination is the file being written
                // ("move/copy TO .env"); source is the file being read or
                // removed ("copy FROM .env" is exfiltration; "move FROM .env"
                // is also a delete of the source). Check destination first
                // because that's the more direct "write to sensitive" attack;
                // fall back to the source side if destination is safe but
                // source is sensitive.
                var dstDenial = denyIfSensitivePath(fm.destination(), "write to");
                if (dstDenial != null) yield dstDenial;
                yield denyIfSensitivePath(fm.source(), fm.deletesSource() ? "remove" : "read from");
            }
            default -> null;
        };
    }

    /**
     * Delegates to {@link SensitivePaths#matches(Path)} for the sensitivity
     * rule set, returning a {@link PermissionDecision.Denied} with a human
     * reason when the path matches. Sensitivity rules live in
     * {@code SensitivePaths} so adapter-side code (notably
     * {@code McpCapabilityInference}) can call the same rules without
     * depending on this policy class.
     */
    private static PermissionDecision.Denied denyIfSensitivePath(Path rawPath, String verb) {
        return SensitivePaths.matches(rawPath) ? deniedSensitive(verb, rawPath.normalize()) : null;
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
