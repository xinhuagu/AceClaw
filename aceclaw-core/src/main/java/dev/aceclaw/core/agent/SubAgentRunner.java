package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.StreamEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Creates and runs sub-agent loops for delegated tasks.
 *
 * <p>Each sub-agent gets a fresh {@link StreamingAgentLoop} with a filtered
 * {@link ToolRegistry} (always excluding "task" to prevent nesting),
 * an empty conversation history, and no compaction.
 *
 * <p>Supports cancellation propagation, permission checking, and project-rule
 * injection for enriched sub-agent system prompts.
 */
public final class SubAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(SubAgentRunner.class);

    /** Maximum project rules chars for non-inheriting models (haiku). */
    private static final int RULES_CAP_SMALL = 2000;

    /** Maximum project rules chars for inheriting models (opus/sonnet). */
    private static final int RULES_CAP_LARGE = 10000;

    private final LlmClient llmClient;
    private final ToolRegistry parentRegistry;
    private final String parentModel;
    private final Path workingDir;
    private final int maxTokens;
    private final int thinkingBudget;
    private final ToolPermissionChecker permissionChecker;
    private final String projectRules;

    /**
     * Creates a sub-agent runner with full configuration.
     *
     * @param llmClient         the LLM client (shared with parent)
     * @param parentRegistry    the parent tool registry
     * @param parentModel       the parent model name
     * @param workingDir        the project working directory
     * @param maxTokens         max output tokens per request
     * @param thinkingBudget    thinking budget tokens (0 = disabled)
     * @param permissionChecker optional permission checker for sub-agent tool calls (may be null)
     * @param projectRules      optional project rules from CLAUDE.md/ACECLAW.md (may be null)
     */
    public SubAgentRunner(LlmClient llmClient, ToolRegistry parentRegistry, String parentModel,
                          Path workingDir, int maxTokens, int thinkingBudget,
                          ToolPermissionChecker permissionChecker, String projectRules) {
        this.llmClient = llmClient;
        this.parentRegistry = parentRegistry;
        this.parentModel = parentModel;
        this.workingDir = workingDir;
        this.maxTokens = maxTokens;
        this.thinkingBudget = thinkingBudget;
        this.permissionChecker = permissionChecker;
        this.projectRules = projectRules;
    }

    /**
     * Backward-compatible constructor (no permission checker, no project rules).
     */
    public SubAgentRunner(LlmClient llmClient, ToolRegistry parentRegistry, String parentModel,
                          Path workingDir, int maxTokens, int thinkingBudget) {
        this(llmClient, parentRegistry, parentModel, workingDir, maxTokens, thinkingBudget, null, null);
    }

    /**
     * Runs a sub-agent with the given configuration and prompt.
     * Blocks until the sub-agent completes all iterations.
     *
     * @param config  the sub-agent type configuration
     * @param prompt  the task prompt for the sub-agent
     * @param handler optional stream event handler (may be null for silent execution)
     * @return the sub-agent's final text response
     * @throws LlmException if the LLM call fails
     */
    public String run(SubAgentConfig config, String prompt, StreamEventHandler handler) throws LlmException {
        return run(config, prompt, handler, null);
    }

    /**
     * Runs a sub-agent with cancellation support.
     *
     * @param config            the sub-agent type configuration
     * @param prompt            the task prompt for the sub-agent
     * @param handler           optional stream event handler (may be null for silent execution)
     * @param cancellationToken optional cancellation token from parent (may be null)
     * @return the sub-agent's final text response
     * @throws LlmException if the LLM call fails
     */
    public String run(SubAgentConfig config, String prompt, StreamEventHandler handler,
                      CancellationToken cancellationToken) throws LlmException {
        String resolvedModel = config.inheritsModel() ? parentModel : config.model();
        var filteredRegistry = createFilteredRegistry(config);
        String systemPrompt = buildSystemPrompt(config);

        log.info("Starting sub-agent '{}': model={}, tools={}, maxTurns={}",
                config.name(), resolvedModel, filteredRegistry.size(), config.maxTurns());

        // Build loop config with optional permission checker
        var loopConfigBuilder = AgentLoopConfig.builder();
        if (permissionChecker != null) {
            loopConfigBuilder.permissionChecker(permissionChecker);
        }
        var loopConfig = loopConfigBuilder.build();

        // Sub-agents use no compaction (short-lived, fresh context)
        var loop = new StreamingAgentLoop(
                llmClient, filteredRegistry, resolvedModel, systemPrompt,
                maxTokens, thinkingBudget, null, loopConfig);

        var effectiveHandler = handler != null ? handler : new StreamEventHandler() {};

        var turn = loop.runTurn(prompt, new ArrayList<>(), effectiveHandler, cancellationToken);

        log.info("Sub-agent '{}' completed: stopReason={}, usage=({} in, {} out)",
                config.name(), turn.finalStopReason(),
                turn.totalUsage().inputTokens(), turn.totalUsage().outputTokens());

        return turn.text();
    }

    /**
     * Creates a filtered tool registry for a sub-agent.
     * Always excludes "task" to prevent infinite nesting.
     *
     * @param config the sub-agent configuration
     * @return a new registry with only the permitted tools
     */
    ToolRegistry createFilteredRegistry(SubAgentConfig config) {
        var filtered = new ToolRegistry();

        // Collect tools to exclude (always include "task" for no-nesting)
        var excluded = new HashSet<>(config.disallowedTools());
        excluded.add("task");

        List<String> allowed = config.allowedTools();
        boolean hasAllowList = !allowed.isEmpty();

        for (var tool : parentRegistry.all()) {
            if (excluded.contains(tool.name())) {
                continue;
            }
            if (hasAllowList && !allowed.contains(tool.name())) {
                continue;
            }
            filtered.register(tool);
        }

        return filtered;
    }

    /**
     * Builds the system prompt for a sub-agent.
     *
     * <p>Structure (shared prefix for cache hits across same-type agents):
     * <ol>
     *   <li>Project rules from CLAUDE.md/ACECLAW.md (shared, cacheable prefix)</li>
     *   <li>Environment context (working dir, platform, date)</li>
     *   <li>Agent-specific template</li>
     * </ol>
     */
    String buildSystemPrompt(SubAgentConfig config) {
        var sb = new StringBuilder();

        // 1. Project rules (shared prefix — enables prompt cache hits)
        if (projectRules != null && !projectRules.isBlank()) {
            int cap = config.inheritsModel() ? RULES_CAP_LARGE : RULES_CAP_SMALL;
            String rules = projectRules.length() > cap
                    ? projectRules.substring(0, cap) + "\n... [truncated]\n"
                    : projectRules;
            sb.append("# Project Rules\n\n").append(rules).append("\n\n");
        }

        // 2. Environment context
        sb.append("# Environment\n\n");
        sb.append("- Working directory: ").append(workingDir.toAbsolutePath().normalize()).append("\n");
        sb.append("- Platform: ").append(System.getProperty("os.name")).append("\n");
        sb.append("- Date: ").append(LocalDate.now()).append("\n\n");

        // 3. Agent-specific template
        String template = config.systemPromptTemplate();
        if (template != null && !template.isBlank()) {
            String resolved = template.contains("%s")
                    ? String.format(template, workingDir)
                    : template;
            sb.append(resolved);
        } else {
            sb.append("You are a sub-agent. Complete the delegated task concisely and accurately.");
        }

        return sb.toString();
    }
}
