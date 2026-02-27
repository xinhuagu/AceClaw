package dev.aceclaw.core.agent;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable configuration for a skill loaded from a SKILL.md file.
 *
 * <p>Skills are structured knowledge packages with instructions, reference materials,
 * and supporting files. Each skill directory contains a SKILL.md with YAML frontmatter
 * and a markdown body that serves as the skill's instructions.
 *
 * <p>Two execution modes are supported:
 * <ul>
 *   <li>{@link ExecutionContext#INLINE} — resolved content is returned as a tool result
 *       for the current agent to act on</li>
 *   <li>{@link ExecutionContext#FORK} — resolved content is used as a prompt for a
 *       sub-agent that runs in isolation with a filtered tool set</li>
 * </ul>
 *
 * @param name                  unique skill name (derived from directory name)
 * @param description           brief description for system prompt injection
 * @param argumentHint          hint shown in tool schema (e.g. "&lt;environment&gt;"), may be null
 * @param context               execution mode: INLINE or FORK
 * @param model                 model to use for FORK mode (null = inherit parent model)
 * @param allowedTools          explicit tool whitelist for FORK mode (empty = all minus disallowed)
 * @param maxTurns              max ReAct iterations for FORK mode
 * @param userInvocable         whether the user can explicitly invoke this skill
 * @param disableModelInvocation whether the model is prevented from auto-invoking this skill
 * @param body                  SKILL.md body content (after frontmatter)
 * @param directory             path to the skill directory (for resolving supporting files)
 */
public record SkillConfig(
        String name,
        String description,
        String argumentHint,
        ExecutionContext context,
        String model,
        List<String> allowedTools,
        int maxTurns,
        boolean userInvocable,
        boolean disableModelInvocation,
        String body,
        Path directory
) {

    /** Default maximum turns for fork-mode skill execution. */
    public static final int DEFAULT_MAX_TURNS = AgentLoopConfig.DEFAULT_MAX_ITERATIONS;

    /** Execution context for a skill. */
    public enum ExecutionContext {
        /** Resolved content returned as tool result; current agent acts on it. */
        INLINE,
        /** Resolved content used as prompt for an isolated sub-agent. */
        FORK
    }

    public SkillConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name must not be null or blank");
        }
        if (description == null) {
            description = "";
        }
        if (context == null) {
            context = ExecutionContext.INLINE;
        }
        allowedTools = allowedTools != null ? List.copyOf(allowedTools) : List.of();
        if (maxTurns <= 0) {
            maxTurns = DEFAULT_MAX_TURNS;
        }
        if (body == null) {
            body = "";
        }
    }

    /**
     * Returns whether this skill uses the parent model (model is null).
     */
    public boolean inheritsModel() {
        return model == null;
    }
}
