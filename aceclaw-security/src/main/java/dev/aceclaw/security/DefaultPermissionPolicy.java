package dev.aceclaw.security;

/**
 * The default permission policy for the AceClaw agent.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@link PermissionLevel#READ}: auto-approved (read-only operations are safe)</li>
 *   <li>{@link PermissionLevel#WRITE}: requires user approval</li>
 *   <li>{@link PermissionLevel#EXECUTE}: requires user approval</li>
 *   <li>{@link PermissionLevel#DANGEROUS}: requires user approval (cannot be session-approved)</li>
 * </ul>
 */
public final class DefaultPermissionPolicy implements PermissionPolicy {

    @Override
    public PermissionDecision evaluate(PermissionRequest request) {
        return switch (request.level()) {
            case READ -> new PermissionDecision.Approved();
            case WRITE -> new PermissionDecision.NeedsUserApproval(
                    formatPrompt(request, "write to"));
            case EXECUTE -> new PermissionDecision.NeedsUserApproval(
                    formatPrompt(request, "execute"));
            case DANGEROUS -> new PermissionDecision.NeedsUserApproval(
                    formatPrompt(request, "perform a potentially destructive action:"));
        };
    }

    private static String formatPrompt(PermissionRequest request, String action) {
        return String.format("The agent wants to %s: %s", action, request.description());
    }
}
