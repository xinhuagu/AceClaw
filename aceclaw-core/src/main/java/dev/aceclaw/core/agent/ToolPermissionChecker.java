package dev.aceclaw.core.agent;

/**
 * Functional interface for checking tool permissions before execution.
 *
 * <p>This abstraction allows the core agent loop to delegate permission
 * decisions without depending on aceclaw-security directly (dependency inversion).
 *
 * <p>Implementations typically wrap a {@code PermissionManager} from aceclaw-security.
 */
@FunctionalInterface
public interface ToolPermissionChecker {

    /**
     * Checks whether the given tool is permitted to execute.
     *
     * @param toolName  the tool name (e.g. "bash", "write_file")
     * @param inputJson the raw JSON input to the tool
     * @return the permission result
     */
    ToolPermissionResult check(String toolName, String inputJson);
}
