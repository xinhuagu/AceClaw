package dev.chelava.security;

/**
 * The result of a permission check.
 *
 * <p>Three possible outcomes: approved (proceed), denied (block with reason),
 * or needs user approval (prompt the user before proceeding).
 */
public sealed interface PermissionDecision {

    /**
     * The operation is approved and may proceed.
     */
    record Approved() implements PermissionDecision {}

    /**
     * The operation is denied and must not proceed.
     *
     * @param reason human-readable explanation of why it was denied
     */
    record Denied(String reason) implements PermissionDecision {}

    /**
     * The operation requires user approval before proceeding.
     *
     * @param prompt the message to display to the user for approval
     */
    record NeedsUserApproval(String prompt) implements PermissionDecision {}

    /**
     * Convenience: checks if this decision allows the operation to proceed.
     */
    default boolean isApproved() {
        return this instanceof Approved;
    }
}
