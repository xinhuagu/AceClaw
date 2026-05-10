package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.AgentTypeRegistry;
import dev.aceclaw.core.agent.CancellationAware;
import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.SubAgentConfig;
import dev.aceclaw.core.agent.SubAgentRunner;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool that delegates tasks to sub-agents with isolated context windows.
 *
 * <p>The parent agent invokes this tool to spawn a child agent for focused tasks
 * like codebase exploration, planning, or general-purpose work. Sub-agents run
 * with filtered tool sets and fresh conversation history.
 *
 * <p>Supports both synchronous execution (default) and background execution
 * via the {@code run_in_background} parameter.
 *
 * <p>Implements {@link CancellationAware} so the parent loop's cancellation
 * token propagates to the sub-agent loop.
 */
public final class TaskTool implements Tool, CapabilityAware, CancellationAware {

    private static final Logger log = LoggerFactory.getLogger(TaskTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SubAgentRunner runner;
    private final AgentTypeRegistry typeRegistry;
    private volatile CancellationToken cancellationToken;
    /**
     * The calling agent's session id, threaded into
     * {@link SubAgentRunner#run(SubAgentConfig, String,
     * dev.aceclaw.core.llm.StreamEventHandler, CancellationToken, String)}
     * so the per-session permission check (#457) can find the right
     * allow-list when the sub-agent invokes a tool the user approved in
     * the parent session. {@code null} on the unbound instance held by
     * the daemon's tool registry; populated by {@link #forRequest(String)}
     * just before the tool runs.
     */
    private volatile String currentSessionId;

    public TaskTool(SubAgentRunner runner, AgentTypeRegistry typeRegistry) {
        this(runner, typeRegistry, null);
    }

    private TaskTool(SubAgentRunner runner, AgentTypeRegistry typeRegistry, String currentSessionId) {
        this.runner = runner;
        this.typeRegistry = typeRegistry;
        this.currentSessionId = currentSessionId;
    }

    /**
     * Returns a request-bound {@code TaskTool} so the parent session id
     * threads into {@link SubAgentRunner}'s loop config. Mirrors
     * {@code SkillTool.forRequest(...)}; concurrent sessions must not
     * share a single mutable instance because {@code currentSessionId}
     * would race between requests.
     *
     * <p>Without this, the registry-level instance has
     * {@code currentSessionId == null}, every {@code runner.run(...)}
     * call lands in the loop with {@code config.sessionId() == null},
     * and {@link SubAgentPermissionChecker} fails-closed for every
     * non-read-only tool — even ones the user already approved.
     */
    public TaskTool forRequest(String sessionId) {
        return new TaskTool(runner, typeRegistry, sessionId);
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
                .optionalProperty("run_in_background", SchemaBuilder.bool(
                        "If true, launch the sub-agent asynchronously and return a task_id " +
                        "immediately. Use task_output to check status or retrieve results. Default: false."))
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

        boolean runInBackground = input.has("run_in_background")
                && input.get("run_in_background").asBoolean(false);

        if (runInBackground) {
            return executeBackground(config, agentType, prompt);
        } else {
            return executeSynchronous(config, agentType, prompt);
        }
    }

    private ToolResult executeSynchronous(SubAgentConfig config, String agentType, String prompt) {
        log.info("Delegating task to sub-agent '{}': prompt length={}, parentSessionId={}",
                agentType, prompt.length(), currentSessionId);

        try {
            // #457: pass currentSessionId so the sub-agent's permission
            // check sees the parent session's allow-list. Null means
            // unbound (registry-level instance) — the runner falls
            // through to the legacy null-session path which deny-fails
            // for non-read-only tools. forRequest(...) prevents that.
            var result = runner.run(config, prompt, null, cancellationToken, currentSessionId);
            if (result.isEmpty()) {
                return new ToolResult("Sub-agent completed but produced no text output.", false);
            }
            return new ToolResult(result, false);
        } catch (Exception e) {
            log.error("Sub-agent '{}' failed: {}", agentType, e.getMessage(), e);
            return new ToolResult("Sub-agent error: " + e.getMessage(), true);
        }
    }

    private ToolResult executeBackground(SubAgentConfig config, String agentType, String prompt) {
        log.info("Launching background sub-agent '{}': prompt length={}, parentSessionId={}",
                agentType, prompt.length(), currentSessionId);

        try {
            // #457: same threading as the synchronous path — the
            // background task inherits the parent's allow-list scope.
            var task = runner.runInBackground(config, prompt, cancellationToken, currentSessionId);
            return new ToolResult(
                    "Background task launched successfully.\n" +
                    "Task ID: " + task.taskId() + "\n" +
                    "Agent: " + agentType + "\n\n" +
                    "Use task_output with this task_id to check status or retrieve results.",
                    false);
        } catch (Exception e) {
            log.error("Failed to launch background sub-agent '{}': {}", agentType, e.getMessage(), e);
            return new ToolResult("Failed to launch background task: " + e.getMessage(), true);
        }
    }

    /**
     * #480 PR 3: structured {@link Capability.SubAgentSpawn}. Depth is
     * carried by {@link dev.aceclaw.security.Provenance#subAgentDepth()}
     * on the audit entry, not by the variant — at {@code toCapability}
     * time we don't have the calling agent's depth in scope, so a
     * variant-side field would always be wrong for nested spawns.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        if (args == null || !args.has("agent_type") || args.get("agent_type").asText().isBlank()) {
            throw new IllegalArgumentException("task requires a non-blank agent_type");
        }
        return new Capability.SubAgentSpawn(args.get("agent_type").asText());
    }
}
