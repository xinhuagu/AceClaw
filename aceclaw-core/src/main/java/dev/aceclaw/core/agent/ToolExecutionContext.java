package dev.aceclaw.core.agent;

/**
 * Per-thread context for in-flight tool execution.
 *
 * <p>Carries the {@code toolUseId} of the currently-running tool call to
 * downstream code that wraps {@link Tool#execute} but doesn't get the id
 * through the interface — most importantly the daemon's
 * {@code PermissionAwareTool}, which needs the id to put on
 * {@code permission.request} notifications so the dashboard can target
 * the correct tool node when multiple parallel calls each need approval.
 *
 * <p>The agent loop sets the id immediately before {@link Tool#execute}
 * and clears it in a finally block. Virtual threads (used for parallel
 * tool execution) get their own ThreadLocal slot, so concurrent tool
 * calls don't collide.
 */
public final class ToolExecutionContext {

    private static final ThreadLocal<String> CURRENT_TOOL_USE_ID = new ThreadLocal<>();

    private ToolExecutionContext() {}

    /** Sets the tool-use id for the current thread. */
    public static void setCurrentToolUseId(String toolUseId) {
        CURRENT_TOOL_USE_ID.set(toolUseId);
    }

    /**
     * Returns the tool-use id of the call currently executing on this
     * thread, or {@code null} if no agent loop has set one (e.g.
     * tools invoked outside the streaming loop).
     */
    public static String currentToolUseId() {
        return CURRENT_TOOL_USE_ID.get();
    }

    /** Clears the slot — called from the agent loop's finally block. */
    public static void clearCurrentToolUseId() {
        CURRENT_TOOL_USE_ID.remove();
    }
}
