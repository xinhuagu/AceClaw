package dev.chelava.security;

/**
 * Classification of how dangerous a tool operation is.
 *
 * <p>Used by the permission system to decide whether to auto-approve,
 * prompt the user, or block the operation.
 */
public enum PermissionLevel {

    /** Read-only operations (read_file, glob, grep). Auto-approved. */
    READ,

    /** Write operations (write_file, edit_file). Require user approval. */
    WRITE,

    /** Command execution (bash). Require user approval. */
    EXECUTE,

    /** Destructive operations (rm -rf, git push --force). Always require approval. */
    DANGEROUS
}
