package dev.aceclaw.security;

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
    public PermissionDecision evaluate(PermissionRequest request) {
        // READ is always auto-approved in all modes
        if (request.level() == PermissionLevel.READ) {
            return new PermissionDecision.Approved();
        }

        return switch (mode) {
            case MODE_AUTO_ACCEPT -> new PermissionDecision.Approved();

            case MODE_PLAN -> new PermissionDecision.Denied(
                    "Operation denied: plan mode is read-only. " +
                    "Requested: " + request.description());

            case MODE_ACCEPT_EDITS -> switch (request.level()) {
                case READ, WRITE -> new PermissionDecision.Approved();
                case EXECUTE, DANGEROUS -> new PermissionDecision.NeedsUserApproval(
                        formatPrompt(request));
            };

            // MODE_NORMAL (default)
            default -> new PermissionDecision.NeedsUserApproval(
                    formatPrompt(request));
        };
    }

    private static String formatPrompt(PermissionRequest request) {
        String action = switch (request.level()) {
            case WRITE -> "write to";
            case EXECUTE -> "execute";
            case DANGEROUS -> "perform a potentially destructive action:";
            default -> "access";
        };
        return String.format("The agent wants to %s: %s", action, request.description());
    }
}
