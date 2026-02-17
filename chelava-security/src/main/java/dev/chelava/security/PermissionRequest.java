package dev.chelava.security;

/**
 * Describes a tool action that may need permission approval.
 *
 * @param toolName    name of the tool requesting permission (e.g. "bash")
 * @param description human-readable description of what the tool will do
 * @param level       the permission level required for this operation
 */
public record PermissionRequest(
        String toolName,
        String description,
        PermissionLevel level
) {

    /**
     * Creates a read-level permission request (auto-approved).
     */
    public static PermissionRequest read(String toolName, String description) {
        return new PermissionRequest(toolName, description, PermissionLevel.READ);
    }

    /**
     * Creates a write-level permission request.
     */
    public static PermissionRequest write(String toolName, String description) {
        return new PermissionRequest(toolName, description, PermissionLevel.WRITE);
    }

    /**
     * Creates an execute-level permission request.
     */
    public static PermissionRequest execute(String toolName, String description) {
        return new PermissionRequest(toolName, description, PermissionLevel.EXECUTE);
    }

    /**
     * Creates a dangerous-level permission request.
     */
    public static PermissionRequest dangerous(String toolName, String description) {
        return new PermissionRequest(toolName, description, PermissionLevel.DANGEROUS);
    }
}
