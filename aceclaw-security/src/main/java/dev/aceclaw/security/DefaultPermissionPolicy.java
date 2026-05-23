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
 * <p>Some capabilities can be denied <em>regardless of mode</em> — including
 * {@code auto-accept}. Today that targets writes and deletes against paths
 * that hold credentials or other operator-critical material:
 * {@code .env*}, {@code .ssh/*}, {@code .git/config}, {@code credentials.json},
 * anything under {@code /etc/}. Detection matches on path segments (not
 * substrings) so {@code /repo/notes-on-dotenv.md} is unaffected.
 *
 * <p>The layer is <b>opt-in</b> via the {@code denySensitivePaths}
 * constructor flag (default: {@code false}). With the flag off,
 * {@link #evaluateStructural} returns {@code null} for every input and the
 * policy behaves exactly like the pre-#480 mode-only policy — so an upgrade
 * doesn't break workflows that legitimately write {@code .env} templates,
 * {@code .git/config} entries, etc. Security-conscious operators enable it
 * by setting {@code security.denySensitivePaths = true} in
 * {@code ~/.aceclaw/config.json}; once on, the rule overrides every mode and
 * every prior approval (session blanket, sub-agent dispatch).
 */
public final class DefaultPermissionPolicy implements PermissionPolicy {

    /** Permission mode constants. */
    public static final String MODE_NORMAL = "normal";
    public static final String MODE_ACCEPT_EDITS = "accept-edits";
    public static final String MODE_PLAN = "plan";
    public static final String MODE_AUTO_ACCEPT = "auto-accept";

    private final String mode;
    private final boolean denySensitivePaths;

    /**
     * Creates a policy with the standard "normal" permission rules and
     * structural sensitive-path denials <b>off</b> (opt-in).
     */
    public DefaultPermissionPolicy() {
        this(MODE_NORMAL, false);
    }

    /**
     * Creates a policy with the specified permission mode and structural
     * sensitive-path denials <b>off</b> (opt-in). Backwards-compatible with
     * pre-flag callers — pass {@code denySensitivePaths=true} to enable the
     * hard-denial layer.
     *
     * @param mode one of "normal", "accept-edits", "plan", "auto-accept"
     * @throws IllegalArgumentException if mode is not recognized
     */
    public DefaultPermissionPolicy(String mode) {
        this(mode, false);
    }

    /**
     * Creates a policy with the specified permission mode and an explicit
     * choice on whether structural sensitive-path denials are active.
     *
     * @param mode               one of "normal", "accept-edits", "plan", "auto-accept"
     * @param denySensitivePaths when {@code true}, writes/deletes targeting
     *                           credential paths ({@code .env*}, {@code .ssh/*},
     *                           {@code /etc/*}, etc.) are hard-denied via
     *                           {@link #evaluateStructural}; when {@code false},
     *                           {@code evaluateStructural} returns {@code null}
     *                           and the policy behaves as mode-only.
     * @throws IllegalArgumentException if mode is not recognized
     */
    public DefaultPermissionPolicy(String mode, boolean denySensitivePaths) {
        this.mode = switch (mode) {
            case MODE_NORMAL, MODE_ACCEPT_EDITS, MODE_PLAN, MODE_AUTO_ACCEPT -> mode;
            default -> throw new IllegalArgumentException(
                    "Unknown permission mode: '" + mode + "'. " +
                    "Valid modes: normal, accept-edits, plan, auto-accept");
        };
        this.denySensitivePaths = denySensitivePaths;
    }

    /**
     * Creates a policy with the legacy auto-approve flag.
     *
     * @param autoApproveAll if true, equivalent to "auto-accept" mode
     * @deprecated Use {@link #DefaultPermissionPolicy(String, boolean)} instead
     */
    @Deprecated
    public DefaultPermissionPolicy(boolean autoApproveAll) {
        this(autoApproveAll ? MODE_AUTO_ACCEPT : MODE_NORMAL, false);
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
     * Returns whether the structural sensitive-path layer is enabled on this
     * policy instance — i.e. whether {@link #evaluateStructural} will ever
     * return a non-{@code null} denial.
     */
    public boolean denySensitivePaths() {
        return denySensitivePaths;
    }

    /**
     * Structural rules that fire before the session-blanket lookup so an
     * "always allow X" approval cannot let the agent route a write to
     * {@code .env} or {@code .ssh/id_rsa} past the policy. Returns
     * {@code null} when no rule applies — see
     * {@link PermissionPolicy#evaluateStructural(Capability)} for the
     * contract.
     *
     * <p>When {@code denySensitivePaths} was set to {@code false} on this
     * instance (the default), this method short-circuits to {@code null} for
     * every input — the policy is pure mode-based and never structurally
     * denies. Operators opt in to the hard-denial layer via the constructor
     * flag (typically wired from {@code security.denySensitivePaths} in
     * {@code ~/.aceclaw/config.json}).
     */
    @Override
    public PermissionDecision.Denied evaluateStructural(Capability capability) {
        Objects.requireNonNull(capability, "capability");
        if (!denySensitivePaths) return null;
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
