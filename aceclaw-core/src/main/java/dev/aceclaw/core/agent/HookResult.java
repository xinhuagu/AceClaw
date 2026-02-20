package dev.aceclaw.core.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result of a hook execution, determining whether tool execution should proceed.
 *
 * <p>Exit code semantics (matching Claude Code):
 * <ul>
 *   <li>0 → {@link Proceed} — allow execution, optionally with modified input</li>
 *   <li>2 → {@link Block} — block execution with a reason</li>
 *   <li>other → {@link Error} — non-blocking error, log and continue</li>
 * </ul>
 */
public sealed interface HookResult {

    /**
     * Hook permits execution to proceed.
     *
     * @param stdout       raw stdout from the hook process
     * @param updatedInput optional modified tool input (null = no modification)
     * @param additionalContext optional context string to append to tool description
     */
    record Proceed(String stdout, JsonNode updatedInput, String additionalContext) implements HookResult {
        /** Convenience constructor for a simple proceed with no modifications. */
        public Proceed() { this(null, null, null); }
    }

    /**
     * Hook blocks execution.
     *
     * @param reason human-readable reason for blocking
     */
    record Block(String reason) implements HookResult {}

    /**
     * Hook encountered a non-blocking error (exit code != 0 and != 2).
     * Execution should continue; the error is logged.
     *
     * @param exitCode the process exit code
     * @param message  stderr or error description
     */
    record Error(int exitCode, String message) implements HookResult {}
}
