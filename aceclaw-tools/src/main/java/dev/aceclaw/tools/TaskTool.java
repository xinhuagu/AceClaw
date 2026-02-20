package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.AgentTypeRegistry;
import dev.aceclaw.core.agent.CancellationAware;
import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.SubAgentConfig;
import dev.aceclaw.core.agent.SubAgentRunner;
import dev.aceclaw.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool that delegates tasks to sub-agents with isolated context windows.
 *
 * <p>The parent agent invokes this tool to spawn a child agent for focused tasks
 * like codebase exploration, planning, or general-purpose work. Sub-agents run
 * with filtered tool sets and fresh conversation history.
 *
 * <p>Implements {@link CancellationAware} so the parent loop's cancellation
 * token propagates to the sub-agent loop.
 */
public final class TaskTool implements Tool, CancellationAware {

    private static final Logger log = LoggerFactory.getLogger(TaskTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SubAgentRunner runner;
    private final AgentTypeRegistry typeRegistry;
    private volatile CancellationToken cancellationToken;

    public TaskTool(SubAgentRunner runner, AgentTypeRegistry typeRegistry) {
        this.runner = runner;
        this.typeRegistry = typeRegistry;
    }

    @Override
    public void setCancellationToken(CancellationToken token) {
        this.cancellationToken = token;
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        var names = typeRegistry.names();
        return SchemaBuilder.object()
                .requiredProperty("agent_type", SchemaBuilder.stringEnum(
                        "The type of sub-agent to use. Available types: " + String.join(", ", names),
                        names.toArray(new String[0])))
                .requiredProperty("prompt", SchemaBuilder.string(
                        "The task description for the sub-agent. Be specific and include all necessary context."))
                .optionalProperty("max_turns", SchemaBuilder.integer(
                        "Maximum ReAct iterations for the sub-agent (default: " +
                        SubAgentConfig.DEFAULT_MAX_TURNS + "). Use lower values for simple tasks."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("agent_type") || input.get("agent_type").asText().isBlank()) {
            return new ToolResult("Missing required parameter: agent_type", true);
        }
        if (!input.has("prompt") || input.get("prompt").asText().isBlank()) {
            return new ToolResult("Missing required parameter: prompt", true);
        }

        var agentType = input.get("agent_type").asText();
        var prompt = input.get("prompt").asText();

        var configOpt = typeRegistry.get(agentType);
        if (configOpt.isEmpty()) {
            return new ToolResult(
                    "Unknown agent type: " + agentType +
                    ". Available types: " + String.join(", ", typeRegistry.names()), true);
        }

        var config = configOpt.get();

        // Override maxTurns if specified
        if (input.has("max_turns") && !input.get("max_turns").isNull()) {
            int maxTurns = input.get("max_turns").asInt(SubAgentConfig.DEFAULT_MAX_TURNS);
            if (maxTurns > 0 && maxTurns != config.maxTurns()) {
                config = new SubAgentConfig(
                        config.name(), config.description(), config.model(),
                        config.allowedTools(), config.disallowedTools(),
                        maxTurns, config.systemPromptTemplate());
            }
        }

        log.info("Delegating task to sub-agent '{}': prompt length={}", agentType, prompt.length());

        try {
            var result = runner.run(config, prompt, null, cancellationToken);
            if (result.isEmpty()) {
                return new ToolResult("Sub-agent completed but produced no text output.", false);
            }
            return new ToolResult(result, false);
        } catch (Exception e) {
            log.error("Sub-agent '{}' failed: {}", agentType, e.getMessage(), e);
            return new ToolResult("Sub-agent error: " + e.getMessage(), true);
        }
    }
}
