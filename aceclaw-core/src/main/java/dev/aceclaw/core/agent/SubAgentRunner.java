package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.StreamEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /** Optional transcript store for persisting sub-agent conversations. */
    private volatile TranscriptStore transcriptStore;

    /** Session ID used to group transcripts (set by daemon wiring). */
    private volatile String sessionId;

    /** Background tasks running on virtual threads. */
    private final ConcurrentHashMap<String, BackgroundTask> backgroundTasks = new ConcurrentHashMap<>();

    /** Maximum age for completed background tasks before lazy cleanup. */
    private static final Duration BG_CLEANUP_AGE = Duration.ofMinutes(30);

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
        return runWithTranscript(config, prompt, handler, cancellationToken).text();
    }

    /**
     * Runs a sub-agent with cancellation support, returning the full result
     * including transcript and turn details.
     *
     * @param config            the sub-agent type configuration
     * @param prompt            the task prompt for the sub-agent
     * @param handler           optional stream event handler (may be null for silent execution)
     * @param cancellationToken optional cancellation token from parent (may be null)
     * @return the full sub-agent result with text, turn, and transcript
     * @throws LlmException if the LLM call fails
     */
    public SubAgentResult runWithTranscript(SubAgentConfig config, String prompt,
                                            StreamEventHandler handler,
                                            CancellationToken cancellationToken) throws LlmException {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Instant startedAt = Instant.now();

        String resolvedModel = config.inheritsModel() ? parentModel : config.model();
        var filteredRegistry = createFilteredRegistry(config);
        String systemPrompt = buildSystemPrompt(config);

        log.info("Starting sub-agent '{}' [{}]: model={}, tools={}, maxTurns={}",
                config.name(), taskId, resolvedModel, filteredRegistry.size(), config.maxTurns());

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

        Instant completedAt = Instant.now();
        String resultText = turn.text();

        log.info("Sub-agent '{}' [{}] completed: stopReason={}, usage=({} in, {} out)",
                config.name(), taskId, turn.finalStopReason(),
                turn.totalUsage().inputTokens(), turn.totalUsage().outputTokens());

        // Build and persist transcript
        var transcript = new SubAgentTranscript(
                taskId, config.name(), prompt,
                turn.newMessages(), startedAt, completedAt, resultText);

        persistTranscript(transcript);

        return new SubAgentResult(taskId, resultText, turn, transcript);
    }

    /**
     * Sets the transcript store for persisting sub-agent conversations.
     *
     * @param store     the transcript store
     * @param sessionId the session ID for grouping transcripts
     */
    public void setTranscriptStore(TranscriptStore store, String sessionId) {
        this.transcriptStore = store;
        this.sessionId = sessionId;
    }

    /**
     * Updates the session ID for transcript grouping (e.g., when session changes).
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Launches a sub-agent in the background on a virtual thread.
     *
     * <p>Returns immediately with a {@link BackgroundTask} that can be polled
     * or awaited for the result. Performs lazy cleanup of old completed tasks.
     *
     * @param config            the sub-agent type configuration
     * @param prompt            the task prompt for the sub-agent
     * @param cancellationToken optional cancellation token (may be null)
     * @return the background task handle
     */
    public BackgroundTask runInBackground(SubAgentConfig config, String prompt,
                                          CancellationToken cancellationToken) {
        cleanupCompletedTasks();

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Instant startedAt = Instant.now();

        var future = CompletableFuture.supplyAsync(() -> {
            try {
                return runWithTranscript(config, prompt, null, cancellationToken);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Thread.ofVirtual().name("bg-task-" + taskId).factory()::newThread);

        var task = new BackgroundTask(taskId, config.name(), prompt, future, startedAt);
        backgroundTasks.put(taskId, task);

        log.info("Launched background task [{}]: agent={}, prompt length={}",
                taskId, config.name(), prompt.length());

        return task;
    }

    /**
     * Gets a background task by ID.
     *
     * @param taskId the task ID
     * @return the task, or null if not found
     */
    public BackgroundTask getBackgroundTask(String taskId) {
        return backgroundTasks.get(taskId);
    }

    /**
     * Awaits a background task's completion with a timeout.
     *
     * @param taskId    the task ID
     * @param timeoutMs maximum time to wait in milliseconds (0 = non-blocking poll)
     * @return the result, or null if the task is not found
     * @throws TimeoutException if the timeout expires before the task completes
     * @throws ExecutionException if the task failed with an exception
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public SubAgentResult awaitBackgroundTask(String taskId, long timeoutMs)
            throws TimeoutException, ExecutionException, InterruptedException {
        var task = backgroundTasks.get(taskId);
        if (task == null) {
            return null;
        }

        if (timeoutMs <= 0) {
            // Non-blocking poll
            if (task.future().isDone()) {
                return task.future().get();
            }
            return null;
        }

        return task.future().get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Removes completed background tasks older than {@link #BG_CLEANUP_AGE}.
     */
    private void cleanupCompletedTasks() {
        Instant cutoff = Instant.now().minus(BG_CLEANUP_AGE);
        backgroundTasks.entrySet().removeIf(entry -> {
            var task = entry.getValue();
            return task.future().isDone() && task.startedAt().isBefore(cutoff);
        });
    }

    private void persistTranscript(SubAgentTranscript transcript) {
        var store = this.transcriptStore;
        var sid = this.sessionId;
        if (store != null && sid != null) {
            try {
                store.save(sid, transcript);
            } catch (Exception e) {
                log.warn("Failed to persist transcript for task {}: {}", transcript.taskId(), e.getMessage());
            }
        }
    }

    /**
     * Creates a filtered tool registry for a sub-agent.
     * Always excludes "task" and "task_output" to prevent nesting.
     *
     * @param config the sub-agent configuration
     * @return a new registry with only the permitted tools
     */
    ToolRegistry createFilteredRegistry(SubAgentConfig config) {
        var filtered = new ToolRegistry();

        // Collect tools to exclude (always include "task" and "task_output" for no-nesting)
        var excluded = new HashSet<>(config.disallowedTools());
        excluded.add("task");
        excluded.add("task_output");

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
            String resolvedModel = config.inheritsModel() ? parentModel : config.model();
            int cap = isSmallModel(resolvedModel) ? RULES_CAP_SMALL : RULES_CAP_LARGE;
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

    /**
     * Heuristic to detect small/cheap models that get a smaller rules budget.
     */
    private static boolean isSmallModel(String model) {
        if (model == null) return false;
        String lower = model.toLowerCase();
        return lower.contains("haiku") || lower.contains("mini") || lower.contains("flash");
    }
}
