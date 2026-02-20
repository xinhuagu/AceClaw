package dev.aceclaw.core.agent;

/**
 * Executes hooks for a given hook event.
 *
 * <p>Implementations run one or more hook commands (shell scripts, binaries, etc.)
 * and return the aggregate result. For {@link HookEvent.PreToolUse} events,
 * the first {@link HookResult.Block} stops further hook execution.
 * For post-execution events, all hooks always run.
 */
@FunctionalInterface
public interface HookExecutor {

    /**
     * Executes all matching hooks for the given event.
     *
     * @param event the hook event describing the tool invocation context
     * @return the result of hook execution
     */
    HookResult execute(HookEvent event);
}
