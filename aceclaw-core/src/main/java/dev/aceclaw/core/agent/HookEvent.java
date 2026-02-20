package dev.aceclaw.core.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Events that trigger hook execution at different points in the tool lifecycle.
 *
 * <p>Three event types correspond to Claude Code's hook events:
 * <ul>
 *   <li>{@link PreToolUse} — fired before tool execution (blocking; can modify input or block)</li>
 *   <li>{@link PostToolUse} — fired after successful tool execution (non-blocking)</li>
 *   <li>{@link PostToolUseFailure} — fired after failed tool execution (non-blocking)</li>
 * </ul>
 */
public sealed interface HookEvent {

    /** The session ID of the current agent session. */
    String sessionId();

    /** The current working directory. */
    String cwd();

    /** The name of the tool being invoked. */
    String toolName();

    /** The tool input as a JSON object. */
    JsonNode toolInput();

    /**
     * The event type name used in config matching and JSON serialization
     * (e.g. "PreToolUse", "PostToolUse", "PostToolUseFailure").
     */
    String eventName();

    /**
     * Fired before a tool is executed. The hook can block execution or modify the input.
     */
    record PreToolUse(String sessionId, String cwd, String toolName, JsonNode toolInput)
            implements HookEvent {
        @Override
        public String eventName() { return "PreToolUse"; }
    }

    /**
     * Fired after a tool executes successfully. Non-blocking — hooks run but cannot alter the result.
     *
     * @param toolOutput the textual output of the tool execution
     */
    record PostToolUse(String sessionId, String cwd, String toolName, JsonNode toolInput,
                       String toolOutput) implements HookEvent {
        @Override
        public String eventName() { return "PostToolUse"; }
    }

    /**
     * Fired after a tool execution fails. Non-blocking — hooks run for auditing/logging.
     *
     * @param error the error message from the failed execution
     */
    record PostToolUseFailure(String sessionId, String cwd, String toolName, JsonNode toolInput,
                              String error) implements HookEvent {
        @Override
        public String eventName() { return "PostToolUseFailure"; }
    }
}
