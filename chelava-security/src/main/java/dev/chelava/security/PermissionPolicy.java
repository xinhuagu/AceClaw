package dev.chelava.security;

/**
 * Strategy interface for permission evaluation.
 *
 * <p>Different policies can be composed to create layered permission logic
 * (e.g. session-level approvals, allowlists, blocklists).
 */
@FunctionalInterface
public interface PermissionPolicy {

    /**
     * Evaluates whether the given permission request should be approved,
     * denied, or needs user confirmation.
     *
     * @param request the permission request to evaluate
     * @return the decision
     */
    PermissionDecision evaluate(PermissionRequest request);
}
