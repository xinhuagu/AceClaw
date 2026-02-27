package dev.aceclaw.core.agent;

import java.util.List;

/**
 * Configuration for a sub-agent type.
 *
 * <p>Defines what model, tools, and system prompt a sub-agent should use.
 * Built-in types (explore, plan, general, bash) are provided by
 * {@link AgentTypeRegistry#withBuiltins()}, and custom types can be loaded
 * from {@code .aceclaw/agents/*.md} files.
 *
 * @param name                unique agent type name (e.g. "explore", "plan")
 * @param description         when to use this agent type (shown in tool description)
 * @param model               model to use (null = inherit parent model)
 * @param allowedTools        explicit list of allowed tools (empty = all minus disallowed)
 * @param disallowedTools     tools to exclude (always includes "task" for no-nesting)
 * @param maxTurns            maximum ReAct iterations (default from {@link AgentLoopConfig})
 * @param systemPromptTemplate system prompt markdown body for the sub-agent
 */
public record SubAgentConfig(
        String name,
        String description,
        String model,
        List<String> allowedTools,
        List<String> disallowedTools,
        int maxTurns,
        String systemPromptTemplate
) {

    /** Default maximum turns for sub-agents. */
    public static final int DEFAULT_MAX_TURNS = AgentLoopConfig.DEFAULT_MAX_ITERATIONS;

    public SubAgentConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Sub-agent name must not be null or blank");
        }
        if (description == null) {
            description = "";
        }
        allowedTools = allowedTools != null ? List.copyOf(allowedTools) : List.of();
        disallowedTools = disallowedTools != null ? List.copyOf(disallowedTools) : List.of();
        if (maxTurns <= 0) {
            maxTurns = DEFAULT_MAX_TURNS;
        }
        if (systemPromptTemplate == null) {
            systemPromptTemplate = "";
        }
    }

    /**
     * Returns whether this agent type uses the parent model (model is null).
     */
    public boolean inheritsModel() {
        return model == null;
    }
}
