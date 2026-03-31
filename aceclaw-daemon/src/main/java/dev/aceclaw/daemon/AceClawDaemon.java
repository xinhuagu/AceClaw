package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.*;
import dev.aceclaw.core.util.WaitSupport;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.daemon.cron.CronScheduler;
import dev.aceclaw.daemon.cron.CronExpression;
import dev.aceclaw.daemon.cron.CronJob;
import dev.aceclaw.daemon.cron.JobStore;
import dev.aceclaw.daemon.cron.CronTool;
import dev.aceclaw.daemon.deferred.DeferCheckTool;
import dev.aceclaw.daemon.deferred.DeferredActionScheduler;
import dev.aceclaw.daemon.deferred.DeferredActionStore;
import dev.aceclaw.daemon.deferred.DeferredEventFeed;
import dev.aceclaw.daemon.heartbeat.HeartbeatRunner;
import dev.aceclaw.infra.event.DeferEvent;
import dev.aceclaw.infra.event.EventBus;
import dev.aceclaw.infra.event.SchedulerEvent;
import dev.aceclaw.infra.health.*;
import dev.aceclaw.llm.LlmClientFactory;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.CandidateStateMachine;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.CrossSessionPatternMiner;
import dev.aceclaw.memory.DailyJournal;
import dev.aceclaw.memory.HistoricalLogIndex;
import dev.aceclaw.memory.HistoricalSessionSnapshot;
import dev.aceclaw.memory.MarkdownMemoryStore;
import dev.aceclaw.memory.CorrectionRulePromoter;
import dev.aceclaw.memory.MemoryConsolidator;
import dev.aceclaw.memory.StrategyRefiner;
import dev.aceclaw.memory.TrendDetector;
import dev.aceclaw.memory.WorkspacePaths;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.mcp.McpClientManager;
import dev.aceclaw.mcp.McpServerConfig;
import dev.aceclaw.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The AceClaw daemon — a persistent system process that orchestrates all services.
 *
 * <p>Boot sequence: Config -> Lock -> Infra -> Sessions -> Router -> Agent -> Listener -> Ready
 *
 * <p>The daemon manages:
 * <ul>
 *   <li>Configuration loading (AceClawConfig)</li>
 *   <li>Instance locking (DaemonLock)</li>
 *   <li>Unix Domain Socket listener (UdsListener)</li>
 *   <li>Session management (SessionManager)</li>
 *   <li>Request routing (RequestRouter)</li>
 *   <li>Streaming agent handler with permission management</li>
 *   <li>Graceful shutdown (ShutdownManager)</li>
 * </ul>
 */
public final class AceClawDaemon {
    private static final String HUMAN_REVIEW_APPLIED_ACTION = "human_review_applied";
    private static final int REVIEW_COUNT_WINDOW = 200;

    private static final Logger log = LoggerFactory.getLogger(AceClawDaemon.class);

    private final Path homeDir;
    private final AceClawConfig config;
    private final ObjectMapper objectMapper;
    private final DaemonLock lock;
    private final ShutdownManager shutdownManager;
    private final SessionManager sessionManager;
    private final SessionHistoryStore historyStore;
    private final AutoMemoryStore memoryStore;
    private final HistoricalLogIndex historicalLogIndex;
    private final LearningExplanationStore learningExplanationStore;
    private final LearningExplanationRecorder learningExplanationRecorder;
    private final LearningValidationStore learningValidationStore;
    private final LearningValidationRecorder learningValidationRecorder;
    private final LearningSignalReviewStore learningSignalReviewStore;
    private final LearningMaintenanceRunStore learningMaintenanceRunStore;
    private final LearningMaintenanceRecoveryStore learningMaintenanceRecoveryStore;
    private final MarkdownMemoryStore markdownStore;
    private final JobStore cronJobStore;
    private final EventBus eventBus;
    private final SchedulerEventFeed schedulerEventFeed;
    private final DeferredEventFeed deferredEventFeed;
    private final SkillDraftEventFeed skillDraftEventFeed;
    private final HealthMonitor healthMonitor;
    private final RequestRouter router;
    private final ConnectionBridge connectionBridge;
    private final UdsListener udsListener;
    private final Instant startedAt;

    // Set during wireAgentHandler(), used for boot execution and cron scheduler
    private LlmClient bootLlmClient;
    private ToolRegistry bootToolRegistry;
    private String bootModel;
    private String bootSystemPrompt;
    private CronScheduler cronScheduler;
    private LearningMaintenanceScheduler learningMaintenanceScheduler;
    private DeferredActionScheduler deferredActionScheduler;
    private DeferredActionStore deferredActionStore;
    private DeferCheckTool deferCheckTool;

    private volatile boolean running;

    private AceClawDaemon(Path homeDir) {
        this.homeDir = homeDir;
        this.startedAt = Instant.now();

        // Load configuration (files + env vars)
        Path workingDir = Path.of(System.getProperty("user.dir"));
        this.config = AceClawConfig.load(workingDir);

        // Infrastructure
        this.objectMapper = createObjectMapper();
        this.shutdownManager = new ShutdownManager();

        // Event bus (async pub/sub for health events, agent events, etc.)
        this.eventBus = new EventBus();
        eventBus.start();
        this.schedulerEventFeed = new SchedulerEventFeed();
        eventBus.subscribe(SchedulerEvent.class, schedulerEventFeed::append);
        this.deferredEventFeed = new DeferredEventFeed();
        eventBus.subscribe(DeferEvent.class, deferredEventFeed::append);
        this.skillDraftEventFeed = new SkillDraftEventFeed();

        // Health monitor (aggregates per-component health checks)
        this.healthMonitor = new HealthMonitor(eventBus);

        // Lock
        this.lock = new DaemonLock(homeDir.resolve("aceclaw.pid"));

        // Sessions & history persistence
        this.sessionManager = new SessionManager();
        this.historyStore = new SessionHistoryStore(homeDir);

        // Auto-memory store (workspace-scoped with daily journal)
        AutoMemoryStore ms = null;
        try {
            ms = AutoMemoryStore.forWorkspace(homeDir, workingDir);
        } catch (java.io.IOException e) {
            log.warn("Failed to initialize auto-memory store: {}", e.getMessage());
        }
        this.memoryStore = ms;
        HistoricalLogIndex hli = null;
        try {
            hli = new HistoricalLogIndex(homeDir);
        } catch (java.io.IOException e) {
            log.warn("Failed to initialize historical log index: {}", e.getMessage());
        }
        this.historicalLogIndex = hli;
        this.learningExplanationStore = new LearningExplanationStore();
        this.learningExplanationRecorder = new LearningExplanationRecorder(learningExplanationStore);
        this.learningValidationStore = new LearningValidationStore();
        this.learningValidationRecorder = new LearningValidationRecorder(learningValidationStore);
        this.learningSignalReviewStore = new LearningSignalReviewStore();
        this.learningMaintenanceRunStore = new LearningMaintenanceRunStore();
        this.learningMaintenanceRecoveryStore = new LearningMaintenanceRecoveryStore();

        // Markdown memory store (persistent MEMORY.md + topic files)
        MarkdownMemoryStore mds = null;
        try {
            mds = MarkdownMemoryStore.forWorkspace(homeDir, workingDir);
        } catch (java.io.IOException e) {
            log.warn("Failed to initialize markdown memory store: {}", e.getMessage());
        }
        this.markdownStore = mds;
        this.cronJobStore = new JobStore(homeDir);
        try {
            this.cronJobStore.load();
        } catch (java.io.IOException e) {
            log.warn("Failed to preload cron job store: {}", e.getMessage());
        }

        this.router = new RequestRouter(sessionManager, objectMapper);
        this.connectionBridge = new ConnectionBridge(router, objectMapper);

        // Wire the streaming agent handler with LLM, tools, and permissions
        wireAgentHandler(workingDir);

        // UDS listener
        this.udsListener = new UdsListener(
                homeDir.resolve("aceclaw.sock"),
                connectionBridge
        );
    }

    /**
     * Wires the streaming agent handler into the request router.
     *
     * <p>Creates the LLM client (from config), tool registry (with all tools),
     * permission manager, and streaming agent loop.
     */
    private void wireAgentHandler(Path workingDir) {
        // 1. LLM client (provider-agnostic via factory)
        String apiKey = config.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("API key not configured; set ANTHROPIC_API_KEY (or OPENAI_API_KEY for non-Anthropic providers) or add apiKey to ~/.aceclaw/config.json");
            apiKey = "not-configured";
        }
        String model = config.resolvedModel();
        LlmClient rawLlmClient;
        if ("anthropic".equals(config.provider())) {
            rawLlmClient = LlmClientFactory.createAnthropicClient(
                    apiKey, config.refreshToken(), config.baseUrl(),
                    config.context1m(), config.extraAnthropicBetas());
            // Tell the client which model is configured so capabilities() can detect 4.6 → 1M
            if (rawLlmClient instanceof dev.aceclaw.llm.anthropic.AnthropicClient ac) {
                ac.setConfiguredModel(model);
            }
        } else {
            rawLlmClient = LlmClientFactory.create(
                    config.provider(), apiKey, config.refreshToken(), config.baseUrl(), model);
        }

        // Wrap LLM client with circuit breaker for fault isolation
        var cbConfig = CircuitBreakerConfig.defaultForLlm();
        var circuitBreaker = new CircuitBreaker(cbConfig, eventBus);
        LlmClient llmClient = new CircuitBreakerLlmClient(rawLlmClient, circuitBreaker);

        // Register circuit breaker health check
        healthMonitor.register(new CircuitBreakerHealthCheck(circuitBreaker));
        log.info("LLM circuit breaker enabled: threshold={}, timeout={}s",
                cbConfig.failureThreshold(), cbConfig.resetTimeout().toSeconds());

        // Resolve effective context window: explicit config > provider default
        int contextWindow = config.contextWindowTokens() > 0
                ? config.contextWindowTokens()
                : llmClient.capabilities().contextWindowTokens();
        String contextSource = config.contextWindowTokens() > 0 ? "from config" : "auto-detected";
        log.info("Context window: {}K ({})", contextWindow / 1000, contextSource);

        // 2. Tool registry (with shared read-tracking for read-before-write enforcement)
        var toolRegistry = new ToolRegistry();
        var writeFileTool = new WriteFileTool(workingDir);
        toolRegistry.register(new ReadFileTool(workingDir, writeFileTool.readFiles()));
        toolRegistry.register(writeFileTool);
        toolRegistry.register(new EditFileTool(workingDir));
        toolRegistry.register(new BashExecTool(workingDir));
        toolRegistry.register(new GlobSearchTool(workingDir));
        toolRegistry.register(new GrepSearchTool(workingDir));
        toolRegistry.register(new ListDirTool(workingDir));
        toolRegistry.register(new WebFetchTool(workingDir));
        toolRegistry.register(new CronTool(
                cronJobStore, () -> cronScheduler != null && cronScheduler.isRunning()));

        // Deferred action store and tool (registered now, scheduler wired later after handler creation)
        this.deferredActionStore = new DeferredActionStore(homeDir);
        try {
            this.deferredActionStore.load();
        } catch (java.io.IOException e) {
            log.warn("Failed to preload deferred action store: {}", e.getMessage());
        }
        // DeferCheckTool registered with null scheduler; scheduler wired after handler creation
        this.deferCheckTool = new DeferCheckTool(null);
        toolRegistry.register(deferCheckTool);

        // Memory management tool (agent can actively save/search/list memories)
        if (memoryStore != null) {
            toolRegistry.register(new dev.aceclaw.tools.MemoryTool(memoryStore, workingDir));
        }

        // Browser tool (lazy Chromium instance, registered as shutdown participant)
        var browserTool = new BrowserTool(workingDir);
        toolRegistry.register(browserTool);
        shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
            @Override public String name() { return "Browser"; }
            @Override public int priority() { return 80; }
            @Override public void onShutdown() { browserTool.close(); }
        });

        // Platform-conditional tools (macOS only)
        if (AppleScriptTool.isSupported()) {
            toolRegistry.register(new AppleScriptTool(workingDir));
            toolRegistry.register(new ScreenCaptureTool(workingDir));
        }

        // Web search (Brave when API key set, DuckDuckGo Lite fallback otherwise)
        if (config.braveSearchApiKey() != null) {
            toolRegistry.register(new WebSearchTool(workingDir, config.braveSearchApiKey()));
        } else {
            toolRegistry.register(new WebSearchTool(workingDir));
        }

        // MCP servers (config-driven external tool providers)
        // Started asynchronously to avoid blocking daemon boot (npx downloads can be slow)
        var mcpConfig = McpServerConfig.load(workingDir);
        if (!mcpConfig.isEmpty()) {
            var mcpManager = new McpClientManager(mcpConfig);
            shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
                @Override public String name() { return "MCP Servers"; }
                @Override public int priority() { return 85; }
                @Override public void onShutdown() { mcpManager.close(); }
            });
            Thread.ofVirtual().name("mcp-init").start(() -> {
                try {
                    mcpManager.start();
                    for (var tool : mcpManager.bridgedTools()) {
                        toolRegistry.register(tool);
                    }
                    log.info("MCP: {} servers, {} tools registered (async)",
                            mcpConfig.size(), mcpManager.bridgedTools().size());
                } catch (Exception e) {
                    log.error("MCP initialization failed: {}", e.getMessage(), e);
                }
            });
            log.info("MCP: {} server(s) configured, initializing in background...", mcpConfig.size());
        }

        log.info("Registered {} base tools", toolRegistry.size());

        // 3. Permission manager — mode from config (default: "normal")
        //    Created early because sub-agent permission checker references it.
        var permissionManager = new PermissionManager(new DefaultPermissionPolicy(config.permissionMode()));

        // 4. Sub-agent infrastructure (task delegation) and skills
        var agentTypeRegistry = AgentTypeRegistry.load(workingDir);

        // Sub-agent permission checker: auto-approve READ tools + session-approved, deny rest
        // Note: "memory" excluded — MemoryTool has save/delete (write operations).
        // "skill" included — skill execution is gated by skill config's allowedTools.
        var builtinReadOnlyTools = java.util.Set.of(
                "read_file", "glob", "grep", "list_directory",
                "web_fetch", "web_search", "screen_capture", "skill");
        // Merge built-in whitelist with extra tools from config
        var extraTools = config.subAgentAutoApproveTools();
        java.util.Set<String> readOnlyTools;
        if (extraTools.isEmpty()) {
            readOnlyTools = builtinReadOnlyTools;
        } else {
            readOnlyTools = new java.util.HashSet<>(builtinReadOnlyTools);
            readOnlyTools.addAll(extraTools);
            readOnlyTools = java.util.Set.copyOf(readOnlyTools);
            log.info("Sub-agent auto-approve tools extended with: {}", extraTools);
        }
        var subAgentPermChecker = new SubAgentPermissionChecker(
                readOnlyTools, permissionManager::hasSessionApproval);

        // Project rules for sub-agent system prompts
        String projectRules = SystemPromptLoader.extractProjectRules(workingDir);

        var subAgentRunner = new SubAgentRunner(
                llmClient, toolRegistry, model, workingDir,
                config.maxTokens(), config.thinkingBudget(),
                subAgentPermChecker, projectRules);

        // Transcript store for sub-agent debugging/auditing
        var transcriptStore = new TranscriptStore(homeDir.resolve("transcripts"));
        subAgentRunner.setTranscriptStore(transcriptStore, "default");
        transcriptStore.cleanup(); // Clean up old transcripts on startup

        toolRegistry.register(new dev.aceclaw.tools.TaskTool(subAgentRunner, agentTypeRegistry));
        toolRegistry.register(new dev.aceclaw.tools.TaskOutputTool(subAgentRunner));
        log.info("Sub-agent types available: {}", agentTypeRegistry.names());

        // Skill system (project + user skills from .aceclaw/skills/)
        var skillRegistry = SkillRegistry.load(workingDir);
        DynamicSkillGenerator dynamicSkillGenerator = null;
        {
            var contentResolver = new SkillContentResolver(workingDir);
            var skillTool = new SkillTool(skillRegistry, contentResolver, subAgentRunner);
            toolRegistry.register(skillTool);
            if (!skillRegistry.isEmpty()) {
            log.info("Skills registered: {}", skillRegistry.names());
            } else {
                log.debug("No disk-backed skills found, SkillTool registered for runtime skills only");
            }
        }

        // 5. System prompt (with 8-tier memory hierarchy + daily journal + model identity + budget)
        //    Budget scales with context window: small models (32K) get smaller memory budgets
        DailyJournal journal = memoryStore != null ? memoryStore.getDailyJournal() : null;
        var promptBudget = SystemPromptBudget.forContextWindow(
                contextWindow, config.maxTokens());
        log.info("System prompt budget: {}K per tier, {}K total (context={}K, maxOutput={}K)",
                promptBudget.maxPerTierChars() / 1000, promptBudget.maxTotalChars() / 1000,
                contextWindow / 1000, config.maxTokens() / 1000);

        // Collect registered tool names for dynamic tool guidance in system prompt
        var toolNames = toolRegistry.all().stream()
                .map(dev.aceclaw.core.agent.Tool::name)
                .collect(java.util.stream.Collectors.toSet());
        String systemPrompt = SystemPromptLoader.load(
                workingDir, memoryStore, journal, markdownStore, model, config.provider(), promptBudget,
                toolNames, config.braveSearchApiKey() != null);

        // 5b. Inject skill descriptions into system prompt so the LLM knows
        //     what each skill does and when to invoke it proactively.
        String skillDescriptions = skillRegistry.isEmpty() ? "" : skillRegistry.formatDescriptions();
        if (!skillDescriptions.isEmpty()) {
            systemPrompt = systemPrompt + "\n\n" + skillDescriptions;
        }

        // 6. Context compaction (accounting for actual system prompt size)
        int systemPromptTokens = dev.aceclaw.core.agent.ContextEstimator.estimateTokens(systemPrompt);
        var compactionConfig = new CompactionConfig(
                contextWindow, config.maxTokens(), systemPromptTokens,
                0.85, 0.60, 5);
        var compactor = new MessageCompactor(llmClient, model, compactionConfig);
        log.info("System prompt: {} chars (~{} tokens), effective conversation window: {} tokens",
                systemPrompt.length(), systemPromptTokens, compactionConfig.effectiveWindowTokens());

        // 7. Streaming agent loop (with compaction support + context budget)
        var loopConfig = dev.aceclaw.core.agent.AgentLoopConfig.builder()
                .maxIterations(config.maxTurns())
                .build();
        var agentLoop = new StreamingAgentLoop(
                llmClient, toolRegistry, model, systemPrompt,
                config.maxTokens(), config.thinkingBudget(),
                contextWindow, compactor, loopConfig);

        // Log startup token budget breakdown
        int toolDefTokens = ContextEstimator.estimateToolDefinitions(toolRegistry.toDefinitions());
        int availableTokens = contextWindow - config.maxTokens();
        log.info("Context budget: system={}t, tools={}t, total_fixed={}t, available={}t (window={}t, output={}t)",
                systemPromptTokens, toolDefTokens,
                systemPromptTokens + toolDefTokens, availableTokens,
                contextWindow, config.maxTokens());

        // 8. Streaming agent handler
        var agentHandler = new StreamingAgentHandler(
                sessionManager, agentLoop, toolRegistry, permissionManager, objectMapper);
        // Use config model for anthropic (user's choice), client's resolved model for other providers
        // (factory may translate or fall back, e.g. copilot ignores anthropic model names)
        String effectiveModel = "anthropic".equals(config.provider()) ? model : llmClient.defaultModel();
        agentHandler.setLlmConfig(llmClient, effectiveModel, systemPrompt);
        agentHandler.setTokenConfig(config.maxTokens(), config.thinkingBudget(), config.maxTurns(), contextWindow);
        agentHandler.setContextAssemblyConfig(
                markdownStore,
                config.provider(),
                promptBudget,
                config.braveSearchApiKey() != null,
                skillRegistry::formatDescriptions);
        agentHandler.setAdaptiveContinuationConfig(
                config.adaptiveContinuationEnabled(),
                config.adaptiveContinuationMaxSegments(),
                config.adaptiveContinuationNoProgressThreshold(),
                config.adaptiveContinuationMaxTotalTokens(),
                config.adaptiveContinuationMaxWallClockSeconds());
        agentHandler.setPlannerConfig(config.plannerEnabled(), config.plannerThreshold());
        agentHandler.setAdaptiveReplanEnabled(config.adaptiveReplanEnabled());
        agentHandler.setWatchdogConfig(
                config.maxAgentTurns(), config.maxAgentWallTimeSec(),
                config.maxAgentHardTurns(), config.maxAgentHardWallTimeSec());
        agentHandler.setPlanBudgetConfig(
                config.maxPlanStepWallTimeSec(),
                config.maxPlanTotalWallTimeSec());

        // Plan checkpoint store for crash-safe plan progress persistence and resume
        var planCheckpointStore = new FilePlanCheckpointStore(
                homeDir.resolve("checkpoints").resolve("plans"), objectMapper);
        planCheckpointStore.cleanup(7); // clean old checkpoints on startup
        agentHandler.setPlanCheckpointStore(planCheckpointStore);

        agentHandler.setCompactor(compactor);
        agentHandler.setMemoryStore(memoryStore, workingDir);
        agentHandler.setAntiPatternGateFeedbackStore(new AntiPatternGateFeedbackStore(
                workingDir,
                config.antiPatternGateMinBlockedBeforeRollback(),
                config.antiPatternGateMaxFalsePositiveRate()));
        if (journal != null) {
            agentHandler.setDailyJournal(journal);
        }

        // 9. Hook system (command hooks at tool lifecycle points)
        var hookRegistry = HookRegistry.load(config.hooks());
        if (!hookRegistry.isEmpty()) {
            var hookExecutor = new CommandHookExecutor(hookRegistry, objectMapper, workingDir);
            agentHandler.setHookExecutor(hookExecutor);
            log.info("Hook system wired: {} matchers across {} event types",
                    hookRegistry.size(),
                    (hookRegistry.hasHooksFor("PreToolUse") ? 1 : 0) +
                    (hookRegistry.hasHooksFor("PostToolUse") ? 1 : 0) +
                    (hookRegistry.hasHooksFor("PostToolUseFailure") ? 1 : 0));
        }

        // 10. Self-improvement engine (post-turn learning analysis + strategy refinement + candidate pipeline)
        // Shared lock serializing all draft generation, validation, and release operations.
        // Prevents races between per-turn trigger, startup catch-up, and RPC handlers.
        final var draftPipelineLock = new java.util.concurrent.locks.ReentrantLock();
        CandidateStore candidateStoreRef = null;
        ValidationGateEngine validationGateEngine = null;
        AutoReleaseController autoReleaseController = null;
        if (memoryStore != null) {
            var errorDetector = new ErrorDetector(memoryStore);
            var patternDetector = new PatternDetector(memoryStore);
            var failureSignalDetector = new FailureSignalDetector();
            var strategyRefiner = new StrategyRefiner(memoryStore);
            if (config.skillDraftValidationEnabled()) {
                validationGateEngine = new ValidationGateEngine(
                        config.skillDraftValidationStrictMode(),
                        config.skillDraftValidationReplayRequired(),
                        Path.of(config.skillDraftValidationReplayReport()),
                        config.skillDraftValidationMaxTokenEstimationErrorRatio());
            }

            // Candidate store for learning pipeline (promotion/demotion state machine)
            CandidateStore cs = null;
            if (config.candidatePromotionEnabled() || config.candidateInjectionEnabled()) {
                try {
                    var smConfig = new CandidateStateMachine.Config(
                            config.candidatePromotionMinEvidence(),
                            config.candidatePromotionMinScore(),
                            config.candidatePromotionMaxFailureRate(),
                            3, java.util.Set.of());
                    cs = new CandidateStore(homeDir, smConfig);
                    cs.load();
                    log.info("Candidate store loaded: {} candidates", cs.all().size());
                } catch (java.io.IOException e) {
                    log.warn("Failed to initialize candidate store: {}", e.getMessage());
                    cs = null;
                }
            }

            final var validationGateForAuto = validationGateEngine;
            final var candidateStoreForAuto = cs;
            if (validationGateForAuto != null && cs != null && config.skillAutoReleaseEnabled()) {
                autoReleaseController = new AutoReleaseController(
                        new AutoReleaseController.Config(
                                config.skillAutoReleaseMinCandidateScore(),
                                config.skillAutoReleaseMinEvidence(),
                                config.skillAutoReleaseCanaryMinAttempts(),
                                config.skillAutoReleaseCanaryMaxFailureRate(),
                                config.skillAutoReleaseCanaryMaxTimeoutRate(),
                                config.skillAutoReleaseCanaryMaxPermissionBlockRate(),
                                config.skillAutoReleaseRollbackMaxFailureRate(),
                                config.skillAutoReleaseRollbackMaxTimeoutRate(),
                                config.skillAutoReleaseRollbackMaxPermissionBlockRate(),
                                Duration.ofHours(Math.max(1, config.skillAutoReleaseHealthLookbackHours())),
                                config.skillAutoReleaseCanaryDwellHours()
                        ),
                        validationGateForAuto
                );
            }
            final var autoReleaseForAuto = autoReleaseController;
            var selfImprovementEngine = new SelfImprovementEngine(
                    errorDetector, patternDetector, failureSignalDetector, memoryStore, strategyRefiner, cs,
                    config.candidatePromotionEnabled(),
                    (validationGateForAuto != null || candidateStoreForAuto != null) ? projectPath -> {
                        draftPipelineLock.lock();
                        try {
                            // Auto-generate skill drafts from newly promoted candidates
                            if (candidateStoreForAuto != null) {
                                var generator = new SkillDraftGenerator();
                                var summary = generator.generateFromPromoted(candidateStoreForAuto, projectPath);
                                publishSkillDraftCreatedEvents(summary, projectPath, "auto-promotion");
                                if (summary.createdDrafts() > 0) {
                                    log.info("Auto skill draft generation: {} created, {} skipped",
                                            summary.createdDrafts(), summary.skippedDrafts());
                                }
                            }
                            // Validate drafts and evaluate for auto-release
                            if (validationGateForAuto != null) {
                                var validation = validationGateForAuto.validateAll(projectPath, "auto-promotion");
                                publishSkillDraftValidationEvents(validation, workingDir, "auto-promotion");
                                if (autoReleaseForAuto != null && candidateStoreForAuto != null) {
                                    var release = autoReleaseForAuto.evaluateAll(projectPath, candidateStoreForAuto, "auto-promotion");
                                    publishSkillDraftReleaseEvents(release, workingDir, "auto-promotion");
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Auto draft generation/validation failed: {}", e.getMessage());
                        } finally {
                            draftPipelineLock.unlock();
                        }
                    } : null);
            selfImprovementEngine.setLearningExplanationRecorder(learningExplanationRecorder);
            agentHandler.setSelfImprovementEngine(selfImprovementEngine);
            candidateStoreRef = cs;

            // Pass candidate store to agent handler for prompt injection
            if (cs != null && config.candidateInjectionEnabled()) {
                agentHandler.setCandidateStore(cs);
                agentHandler.setCandidateInjectionEnabled(true);
                agentHandler.setCandidateInjectionConfig(
                        config.candidateInjectionMaxCount(), config.candidateInjectionMaxTokens());
            } else {
                agentHandler.setCandidateInjectionEnabled(false);
            }

            // Wire runtime metrics exporter and injection audit log
            var runtimeMetricsExporter = new RuntimeMetricsExporter();
            agentHandler.setRuntimeMetricsExporter(runtimeMetricsExporter);
            if (cs != null) {
                var memoryDir = Path.of(System.getProperty("user.home"), ".aceclaw", "memory");
                agentHandler.setInjectionAuditLog(new dev.aceclaw.memory.InjectionAuditLog(memoryDir));
            }

            log.info("Self-improvement engine wired (with strategy refinement + candidate pipeline + runtime metrics)");

            // Catch-up: generate drafts for any PROMOTED candidates that don't have drafts yet.
            // This handles candidates promoted before the auto-trigger existed (pre-#175).
            final var catchupCs = cs;
            final var catchupValidation = validationGateEngine;
            final var catchupAutoRelease = autoReleaseController;
            if (catchupCs != null && !catchupCs.byState(dev.aceclaw.memory.CandidateState.PROMOTED).isEmpty()) {
                Thread.ofVirtual().name("draft-catchup").start(() -> {
                    draftPipelineLock.lock();
                    try {
                        var generator = new SkillDraftGenerator();
                        var summary = generator.generateFromPromoted(catchupCs, workingDir);
                        publishSkillDraftCreatedEvents(summary, workingDir, "startup-catchup");
                        if (summary.createdDrafts() > 0) {
                            log.info("Draft catch-up: {} new drafts generated for existing promoted candidates",
                                    summary.createdDrafts());
                        }
                        if (catchupValidation != null && summary.createdDrafts() > 0) {
                            var validation = catchupValidation.validateAll(workingDir, "startup-catchup");
                            publishSkillDraftValidationEvents(validation, workingDir, "startup-catchup");
                            if (catchupAutoRelease != null) {
                                var release = catchupAutoRelease.evaluateAll(workingDir, catchupCs, "startup-catchup");
                                publishSkillDraftReleaseEvents(release, workingDir, "startup-catchup");
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Draft catch-up failed: {}", e.getMessage());
                    } finally {
                        draftPipelineLock.unlock();
                    }
                });
            }
        }

        dynamicSkillGenerator = new DynamicSkillGenerator(
                llmClient,
                agentHandler::getModelForSession,
                skillRegistry);
        dynamicSkillGenerator.setLearningExplanationRecorder(learningExplanationRecorder);
        dynamicSkillGenerator.setLearningValidationRecorder(learningValidationRecorder);
        agentHandler.setLearningExplanationRecorder(learningExplanationRecorder);
        agentHandler.setLearningValidationRecorder(learningValidationRecorder);
        agentHandler.setDynamicSkillGenerator(dynamicSkillGenerator);

        // Wire deferred action scheduler (no turn lock dependency — uses isolated context)
        if (config.deferredActionEnabled()) {
            this.deferredActionScheduler = new DeferredActionScheduler(
                    deferredActionStore, sessionManager,
                    llmClient, toolRegistry, model, systemPrompt,
                    config.maxTokens(), config.thinkingBudget(),
                    eventBus, config.deferredActionTickSeconds());
            deferCheckTool.setScheduler(deferredActionScheduler);
            log.info("Deferred action scheduler wired (tick every {}s)",
                    config.deferredActionTickSeconds());
        }

        agentHandler.register(router);

        // Session-end memory extraction + consolidation
        // Runs SYNCHRONOUSLY to ensure extraction completes before session deactivation.
        // This is critical during shutdown — async virtual threads may not finish before JVM exits.
        // The extraction is pure-Java regex matching (no LLM calls), so blocking is fast.
        final var extractionJournal = journal;
        final var archiveDir = markdownStore != null ? markdownStore.memoryDir() : null;
        final var agentHandlerForCleanup = agentHandler;
        final var sessionAnalyzer = memoryStore != null ? new SessionAnalyzer() : null;
        final var historicalIndexRebuilder = historicalLogIndex != null
                ? new HistoricalIndexRebuilder(historyStore, historicalLogIndex)
                : null;
        final var crossSessionPatternMiner = historicalLogIndex != null ? new CrossSessionPatternMiner() : null;
        final var trendDetector = historicalLogIndex != null ? new TrendDetector() : null;
        final var maintenanceCandidateBridge = config.candidatePromotionEnabled() && candidateStoreRef != null
                ? new LearningMaintenanceCandidateBridge(candidateStoreRef)
                : null;
        final var maintenanceCandidateStore = candidateStoreRef;
        final var maintenanceValidationGate = validationGateEngine;
        final var maintenanceAutoRelease = autoReleaseController;
        if (memoryStore != null) {
            learningMaintenanceScheduler = new LearningMaintenanceScheduler(
                    LearningMaintenanceScheduler.Config.defaults(Duration.ofSeconds(config.schedulerTickSeconds())),
                    java.time.Clock.systemUTC(),
                    sessionManager::sessionCount,
                    scopes -> scopes.stream()
                            .mapToLong(scope -> memoryStore.largestBackingFileBytes(scope.workingDir()))
                            .max()
                            .orElse(0L),
                    (trigger, scope) -> runLearningMaintenancePipeline(
                            trigger,
                            memoryStore,
                            archiveDir,
                            extractionJournal,
                            historicalIndexRebuilder,
                            crossSessionPatternMiner,
                            trendDetector,
                            maintenanceCandidateBridge,
                            maintenanceCandidateStore,
                            maintenanceValidationGate,
                            maintenanceAutoRelease,
                            draftPipelineLock,
                            scope.workspaceHash(),
                            scope.workingDir()),
                    learningMaintenanceRecoveryStore
            );
        }
        final var runtimeSkillGeneratorForSessionEnd = dynamicSkillGenerator;
        sessionManager.setSessionEndCallback(session -> {
            if (memoryStore != null) {
                var sessionWorkingDir = session.projectPath().toAbsolutePath().normalize();
                var sessionWorkspaceHash = WorkspacePaths.workspaceHash(sessionWorkingDir);
                historyStore.saveSession(session);
                var extracted = SessionEndExtractor.extract(session.messages());
                for (var mem : extracted) {
                    try {
                        memoryStore.add(mem.category(), mem.content(), mem.tags(),
                                "session-end:" + session.id(), false, sessionWorkingDir);
                    } catch (Exception e) {
                        log.warn("Failed to save session-end memory: {}", e.getMessage());
                    }
                }
                if (!extracted.isEmpty()) {
                    log.info("Extracted {} memories from session {} on destroy",
                            extracted.size(), session.id());
                }
                var metricsSnapshot = agentHandlerForCleanup.snapshotSessionMetrics(session.id());
                var learnings = sessionAnalyzer.analyze(session.messages(), metricsSnapshot);
                for (var insight : learnings.insights()) {
                    if (!shouldPersistSessionAnalysisInsight(insight)) {
                        continue;
                    }
                    try {
                        memoryStore.addIfAbsent(
                                insight.category(),
                                insight.content(),
                                insight.tags(),
                                "session-analysis:" + session.id(),
                                false,
                                sessionWorkingDir);
                        learningExplanationRecorder.recordMemoryWrite(
                                sessionWorkingDir,
                                session.id(),
                                "session-analysis",
                                insight.category(),
                                insight.content(),
                                insight.tags(),
                                List.of(new LearningExplanation.EvidenceRef(
                                        "session-summary",
                                        session.id(),
                                        learnings.sessionSummary())));
                    } catch (Exception e) {
                        log.warn("Failed to save session analysis memory: {}", e.getMessage());
                    }
                }
                var shortId = session.id().length() > 8
                        ? session.id().substring(0, 8) : session.id();
                if (extractionJournal != null) {
                    extractionJournal.append("Session " + shortId +
                            " ended: " + session.messages().size() + " messages, " +
                            extracted.size() + " memories extracted");
                    if (!learnings.sessionSummary().isBlank()) {
                        extractionJournal.append("Session retrospective (" + shortId + "): "
                                + learnings.sessionSummary());
                    }
                }
                if (historicalLogIndex != null) {
                    try {
                        var snapshot = new HistoricalSessionSnapshot(
                                session.id(),
                                sessionWorkspaceHash,
                                Instant.now(),
                                learnings.executedCommands(),
                                learnings.errorsEncountered(),
                                learnings.extractedFilePaths(),
                                metricsSnapshot,
                                learnings.backtrackingDetected(),
                                learnings.endToEndStrategy()
                        );
                        historyStore.saveSnapshot(snapshot);
                        historicalLogIndex.index(snapshot);
                    } catch (Exception e) {
                        log.warn("Failed to index session history: {}", e.getMessage());
                    }
                }
                if (learningMaintenanceScheduler != null) {
                    try {
                        learningMaintenanceScheduler.onSessionClosed(sessionWorkspaceHash, sessionWorkingDir);
                    } catch (Exception e) {
                        log.warn("Learning maintenance trigger failed: {}", e.getMessage());
                    }
                }
                if (runtimeSkillGeneratorForSessionEnd != null) {
                    try {
                        agentHandlerForCleanup.awaitSessionPostProcessing(session.id());
                        int persistedDrafts = runtimeSkillGeneratorForSessionEnd.persistDrafts(session.id(), sessionWorkingDir);
                        if (persistedDrafts > 0) {
                            log.info("Persisted {} runtime skill drafts for session {}", persistedDrafts, session.id());
                        }
                    } catch (Exception e) {
                        log.warn("Runtime skill draft persistence failed: {}", e.getMessage());
                    }
                }
            }

            // Clean up session-scoped resources in the agent handler
            agentHandlerForCleanup.clearSessionOverride(session.id());
            agentHandlerForCleanup.clearSessionMetrics(session.id());
        });

        // Expose model name, provider info, and health monitor to status endpoint
        router.setModelName(effectiveModel);
        router.setProviderInfo(config.provider(), contextWindow);
        router.setHealthMonitor(healthMonitor);

        // Register model.list and model.switch RPC methods
        final var llmClientRef = llmClient;
        final var agentHandlerRef = agentHandler;
        final var providerNameRef = config.provider();

        // Register model.list and model.switch via shared helper
        ModelRpcHelper.registerModelList(router, objectMapper, agentHandlerRef, llmClientRef, providerNameRef);
        ModelRpcHelper.registerModelSwitch(router, objectMapper, agentHandlerRef, llmClientRef);
        ContextRpcHelper.registerContextInspect(
                router, objectMapper, agentHandlerRef, agentHandlerRef::getContextWindowTokens);

        // Runtime controls: candidate injection kill-switch and manual rollback.
        final var candidateStoreForRpc = candidateStoreRef;
        final var validationGateForRpc = validationGateEngine;
        final var autoReleaseForRpc = autoReleaseController;
        router.register("candidate.injection.set", params -> {
            if (params == null || !params.has("enabled")) {
                throw new IllegalArgumentException("Missing required parameter: enabled");
            }
            boolean enabled = params.get("enabled").asBoolean();
            agentHandlerRef.setCandidateInjectionEnabled(enabled);
            Integer maxTokens = params != null && params.has("maxTokens")
                    ? Math.max(0, params.get("maxTokens").asInt()) : null;
            if (maxTokens != null) {
                agentHandlerRef.setCandidateInjectionConfig(config.candidateInjectionMaxCount(), maxTokens);
            }
            boolean persist = params != null && params.has("persist") && params.get("persist").asBoolean(false);
            String scope = params != null && params.has("scope") ? params.get("scope").asText() : "project";
            var result = objectMapper.createObjectNode();
            result.put("enabled", enabled);
            if (maxTokens != null) {
                result.put("maxTokens", maxTokens);
            }
            result.put("persisted", false);
            if (persist) {
                var written = AceClawConfig.persistCandidateInjectionSettings(
                        workingDir, enabled, maxTokens, scope);
                result.put("persisted", true);
                result.put("scope", scope);
                result.put("configFile", written.toString());
            }
            return result;
        });
        router.register("antiPatternGate.override.set", params -> {
            if (params == null) {
                throw new IllegalArgumentException("Missing required params");
            }
            if (!params.has("sessionId")) {
                throw new IllegalArgumentException("Missing required parameter: sessionId");
            }
            if (!params.has("tool")) {
                throw new IllegalArgumentException("Missing required parameter: tool");
            }
            String sessionId = params.get("sessionId").asText();
            String tool = params.get("tool").asText();
            long ttlSeconds = params.has("ttlSeconds") ? Math.max(1L, params.get("ttlSeconds").asLong()) : 300L;
            String reason = params.has("reason") ? params.get("reason").asText() : "manual override";
            agentHandlerRef.setAntiPatternGateOverride(sessionId, tool, ttlSeconds, reason);
            var status = agentHandlerRef.getAntiPatternGateOverride(sessionId, tool);
            var result = objectMapper.createObjectNode();
            result.put("sessionId", status.sessionId());
            result.put("tool", status.tool());
            result.put("active", status.active());
            result.put("ttlSecondsRemaining", status.ttlSecondsRemaining());
            result.put("reason", status.reason());
            return result;
        });
        router.register("antiPatternGate.override.get", params -> {
            if (params == null) {
                throw new IllegalArgumentException("Missing required params");
            }
            if (!params.has("sessionId")) {
                throw new IllegalArgumentException("Missing required parameter: sessionId");
            }
            if (!params.has("tool")) {
                throw new IllegalArgumentException("Missing required parameter: tool");
            }
            String sessionId = params.get("sessionId").asText();
            String tool = params.get("tool").asText();
            var status = agentHandlerRef.getAntiPatternGateOverride(sessionId, tool);
            var result = objectMapper.createObjectNode();
            result.put("sessionId", status.sessionId());
            result.put("tool", status.tool());
            result.put("active", status.active());
            result.put("ttlSecondsRemaining", status.ttlSecondsRemaining());
            result.put("reason", status.reason());
            return result;
        });
        router.register("antiPatternGate.override.clear", params -> {
            if (params == null) {
                throw new IllegalArgumentException("Missing required params");
            }
            if (!params.has("sessionId")) {
                throw new IllegalArgumentException("Missing required parameter: sessionId");
            }
            if (!params.has("tool")) {
                throw new IllegalArgumentException("Missing required parameter: tool");
            }
            String sessionId = params.get("sessionId").asText();
            String tool = params.get("tool").asText();
            boolean cleared = agentHandlerRef.clearAntiPatternGateOverride(sessionId, tool);
            var result = objectMapper.createObjectNode();
            result.put("sessionId", sessionId);
            result.put("tool", tool);
            result.put("cleared", cleared);
            return result;
        });
        router.register("candidate.rollback", params -> {
            if (candidateStoreForRpc == null) {
                throw new IllegalStateException("Candidate store is not initialized");
            }
            if (params == null || !params.has("candidateId")) {
                throw new IllegalArgumentException("Missing required parameter: candidateId");
            }
            String candidateId = params.get("candidateId").asText();
            String reason = params.has("reason") ? params.get("reason").asText() : "manual rollback";
            var transition = candidateStoreForRpc.rollbackPromoted(candidateId, reason);
            var result = objectMapper.createObjectNode();
            result.put("applied", transition.isPresent());
            transition.ifPresent(t -> {
                result.put("candidateId", t.candidateId());
                result.put("fromState", t.fromState().name());
                result.put("toState", t.toState().name());
                result.put("reasonCode", t.reasonCode());
                result.put("timestamp", t.timestamp().toString());
            });
            return result;
        });
        router.register("skill.draft.generate", params -> {
            if (candidateStoreForRpc == null) {
                throw new IllegalStateException("Candidate store is not initialized");
            }
            draftPipelineLock.lock();
            try {
                var generator = new SkillDraftGenerator();
                var summary = generator.generateFromPromoted(candidateStoreForRpc, workingDir);
                publishSkillDraftCreatedEvents(summary, workingDir, "draft-generated");
                var result = objectMapper.createObjectNode();
                result.put("processedPromotedCandidates", summary.processedPromotedCandidates());
                result.put("createdDrafts", summary.createdDrafts());
                result.put("skippedDrafts", summary.skippedDrafts());
                var paths = objectMapper.createArrayNode();
                summary.draftPaths().forEach(path -> paths.add(path.replace('\\', '/')));
                result.set("draftPaths", paths);
                result.put("auditFile", workingDir.relativize(summary.auditFile()).toString().replace('\\', '/'));
                if (validationGateForRpc != null) {
                    var validation = validationGateForRpc.validateAll(workingDir, "draft-generated");
                    publishSkillDraftValidationEvents(validation, workingDir, "draft-generated");
                    result.set("validation", toValidationJson(validation, workingDir));
                    if (autoReleaseForRpc != null && candidateStoreForRpc != null) {
                        var release = autoReleaseForRpc.evaluateAll(workingDir, candidateStoreForRpc, "draft-generated");
                        publishSkillDraftReleaseEvents(release, workingDir, "draft-generated");
                        result.set("release", toReleaseJson(release));
                    }
                }
                return result;
            } finally {
                draftPipelineLock.unlock();
            }
        });
        router.register("skill.draft.validate", params -> {
            if (validationGateForRpc == null) {
                throw new IllegalStateException("Skill draft validation is disabled");
            }
            draftPipelineLock.lock();
            try {
                String trigger = params != null && params.has("trigger")
                        ? params.get("trigger").asText() : "manual";
                if (params != null && params.has("draftPath")) {
                    Path draftPath = workingDir.resolve(params.get("draftPath").asText()).normalize();
                    var summary = validationGateForRpc.validateSingleDraft(workingDir, draftPath, trigger);
                    publishSkillDraftValidationEvents(summary, workingDir, trigger);
                    return toValidationJson(summary, workingDir);
                }
                var summary = validationGateForRpc.validateAll(workingDir, trigger);
                publishSkillDraftValidationEvents(summary, workingDir, trigger);
                return toValidationJson(summary, workingDir);
            } finally {
                draftPipelineLock.unlock();
            }
        });
        router.register("skill.release.evaluate", params -> {
            if (autoReleaseForRpc == null || candidateStoreForRpc == null) {
                throw new IllegalStateException("Skill auto release is disabled");
            }
            draftPipelineLock.lock();
            try {
                String trigger = params != null && params.has("trigger")
                        ? params.get("trigger").asText() : "manual";
                var summary = autoReleaseForRpc.evaluateAll(workingDir, candidateStoreForRpc, trigger);
                publishSkillDraftReleaseEvents(summary, workingDir, trigger);
                return toReleaseJson(summary);
            } finally {
                draftPipelineLock.unlock();
            }
        });
        router.register("skill.release.pause", params -> {
            if (autoReleaseForRpc == null) {
                throw new IllegalStateException("Skill auto release is disabled");
            }
            if (params == null || !params.has("skillName")) {
                throw new IllegalArgumentException("Missing required parameter: skillName");
            }
            String skillName = params.get("skillName").asText();
            String reason = params.has("reason") ? params.get("reason").asText() : "manual pause";
            String trigger = params.has("trigger") ? params.get("trigger").asText() : "manual";
            var summary = autoReleaseForRpc.pause(workingDir, skillName, reason, trigger);
            publishSkillDraftReleaseEvents(summary, workingDir, trigger);
            return toReleaseJson(summary);
        });
        router.register("skill.release.forceRollback", params -> {
            if (autoReleaseForRpc == null) {
                throw new IllegalStateException("Skill auto release is disabled");
            }
            if (params == null || !params.has("skillName")) {
                throw new IllegalArgumentException("Missing required parameter: skillName");
            }
            String skillName = params.get("skillName").asText();
            String reason = params.has("reason") ? params.get("reason").asText() : "manual force rollback";
            String trigger = params.has("trigger") ? params.get("trigger").asText() : "manual";
            var summary = autoReleaseForRpc.forceRollback(workingDir, skillName, reason, trigger);
            publishSkillDraftReleaseEvents(summary, workingDir, trigger);
            return toReleaseJson(summary);
        });
        router.register("skill.release.forcePromote", params -> {
            if (autoReleaseForRpc == null) {
                throw new IllegalStateException("Skill auto release is disabled");
            }
            if (params == null || !params.has("skillName")) {
                throw new IllegalArgumentException("Missing required parameter: skillName");
            }
            String skillName = params.get("skillName").asText();
            String stageRaw = params.has("targetStage") ? params.get("targetStage").asText() : "canary";
            AutoReleaseController.Stage stage;
            try {
                stage = AutoReleaseController.Stage.valueOf(stageRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid targetStage: " + stageRaw + " (allowed: canary, active)");
            }
            String reason = params.has("reason") ? params.get("reason").asText() : "manual force promote";
            String trigger = params.has("trigger") ? params.get("trigger").asText() : "manual";
            var summary = autoReleaseForRpc.forcePromote(workingDir, skillName, stage, reason, trigger);
            publishSkillDraftReleaseEvents(summary, workingDir, trigger);
            return toReleaseJson(summary);
        });

        // Session skill packer (extract successful workflow from session into skill draft)
        int packBudget = SessionSkillPacker.deriveMaxConversationChars(contextWindow);
        var skillPacker = new SessionSkillPacker(
                historyStore, sessionManager, llmClient, model, objectMapper, packBudget);
        router.register("skill.pack", params -> {
            if (params == null || !params.has("sessionId")) {
                throw new IllegalArgumentException("Missing required parameter: sessionId");
            }
            var sessionId = params.get("sessionId").asText();
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("Missing required parameter: sessionId");
            }
            String name = params.has("name") && !params.get("name").isNull()
                    ? params.get("name").asText() : null;
            Integer turnStart = params.has("turnStart") && !params.get("turnStart").isNull()
                    ? params.get("turnStart").asInt() : null;
            Integer turnEnd = params.has("turnEnd") && !params.get("turnEnd").isNull()
                    ? params.get("turnEnd").asInt() : null;

            try {
                var packResult = skillPacker.pack(sessionId, name, turnStart, turnEnd, workingDir);
                var result = objectMapper.createObjectNode();
                result.put("skillName", packResult.skillName());
                result.put("path", packResult.relativePath());
                result.put("stepCount", packResult.stepCount());
                return result;
            } catch (dev.aceclaw.core.llm.LlmException e) {
                throw new RuntimeException("LLM extraction failed: " + e.getMessage(), e);
            }
        });

        // Scheduler feed polling for foreground CLI notifications.
        router.register("scheduler.cron.status", params -> {
            var result = objectMapper.createObjectNode();
            boolean schedulerRunning = cronScheduler != null && cronScheduler.isRunning();
            result.put("schedulerRunning", schedulerRunning);
            if (cronScheduler != null) {
                result.put("jobRunning", cronScheduler.isJobRunning());
                if (cronScheduler.currentJobId() != null) {
                    result.put("currentJobId", cronScheduler.currentJobId());
                }
                if (cronScheduler.currentJobStartedAt() != null) {
                    result.put("currentJobStartedAt", cronScheduler.currentJobStartedAt().toString());
                }
            } else {
                result.put("jobRunning", false);
            }

            var jobs = objectMapper.createArrayNode();
            for (CronJob job : cronJobStore.all().stream()
                    .sorted(Comparator.comparing(CronJob::id))
                    .toList()) {
                var jn = objectMapper.createObjectNode();
                jn.put("id", job.id());
                jn.put("name", job.name());
                jn.put("expression", job.expression());
                jn.put("enabled", job.enabled());
                jn.put("heartbeat", job.id().startsWith(HeartbeatRunner.JOB_ID_PREFIX));
                jn.put("kind", cronKind(job));
                jn.put("description", summarizePrompt(job.prompt()));
                if (job.lastRunAt() != null) {
                    jn.put("lastRunAt", job.lastRunAt().toString());
                }
                if (job.lastError() != null && !job.lastError().isBlank()) {
                    jn.put("lastError", job.lastError());
                }
                try {
                    Instant lastRun = job.lastRunAt() != null ? job.lastRunAt() : Instant.EPOCH;
                    Instant nextFire = CronExpression.parse(job.expression()).nextFireTime(lastRun);
                    if (nextFire != null) {
                        jn.put("nextFireAt", nextFire.toString());
                    }
                } catch (Exception ignored) {
                }
                jobs.add(jn);
            }
            result.set("jobs", jobs);
            return result;
        });

        // Scheduler feed polling for foreground CLI notifications.
        router.register("scheduler.events.poll", params -> {
            long afterSeq = 0L;
            int limit = 20;
            if (params != null) {
                if (params.has("afterSeq")) {
                    afterSeq = Math.max(0L, params.get("afterSeq").asLong());
                }
                if (params.has("limit")) {
                    limit = params.get("limit").asInt(20);
                }
            }

            var polled = schedulerEventFeed.poll(afterSeq, limit);
            var result = objectMapper.createObjectNode();
            result.put("nextSeq", polled.nextSequence());
            var events = objectMapper.createArrayNode();
            for (var entry : polled.entries()) {
                var node = objectMapper.createObjectNode();
                node.put("seq", entry.sequence());
                var event = entry.event();
                node.put("jobId", event.jobId());
                switch (event) {
                    case SchedulerEvent.JobTriggered e -> {
                        node.put("type", "triggered");
                        node.put("cronExpression", e.cronExpression());
                        node.put("timestamp", e.timestamp().toString());
                    }
                    case SchedulerEvent.JobCompleted e -> {
                        node.put("type", "completed");
                        node.put("durationMs", e.durationMs());
                        node.put("summary", e.summary());
                        node.put("timestamp", e.timestamp().toString());
                    }
                    case SchedulerEvent.JobFailed e -> {
                        node.put("type", "failed");
                        node.put("error", e.error());
                        node.put("attempt", e.attempt());
                        node.put("maxAttempts", e.maxAttempts());
                        node.put("timestamp", e.timestamp().toString());
                    }
                    case SchedulerEvent.JobSkipped e -> {
                        node.put("type", "skipped");
                        node.put("reason", e.reason());
                        node.put("timestamp", e.timestamp().toString());
                    }
                }
                events.add(node);
            }
            result.set("events", events);
            return result;
        });

        // Deferred event feed polling for foreground CLI notifications.
        router.register("deferred.events.poll", params -> {
            long afterSeq = 0L;
            int limit = 20;
            if (params != null) {
                if (params.has("afterSeq")) {
                    afterSeq = Math.max(0L, params.get("afterSeq").asLong());
                }
                if (params.has("limit")) {
                    limit = params.get("limit").asInt(20);
                }
            }

            var polled = deferredEventFeed.poll(afterSeq, limit);
            var result = objectMapper.createObjectNode();
            result.put("nextSeq", polled.nextSequence());
            var events = objectMapper.createArrayNode();
            for (var entry : polled.entries()) {
                var node = objectMapper.createObjectNode();
                node.put("seq", entry.sequence());
                var event = entry.event();
                node.put("actionId", event.actionId());
                node.put("sessionId", event.sessionId());
                node.put("timestamp", event.timestamp().toString());
                switch (event) {
                    case DeferEvent.ActionScheduled e -> {
                        node.put("type", "scheduled");
                        node.put("goal", e.goal());
                        node.put("runAt", e.runAt().toString());
                    }
                    case DeferEvent.ActionTriggered _ -> {
                        node.put("type", "triggered");
                    }
                    case DeferEvent.ActionCompleted e -> {
                        node.put("type", "completed");
                        node.put("durationMs", e.durationMs());
                        node.put("summary", e.summary());
                    }
                    case DeferEvent.ActionFailed e -> {
                        node.put("type", "failed");
                        node.put("error", e.error());
                        node.put("attempt", e.attempt());
                        node.put("maxAttempts", e.maxAttempts());
                    }
                    case DeferEvent.ActionExpired _ -> {
                        node.put("type", "expired");
                    }
                    case DeferEvent.ActionCancelled e -> {
                        node.put("type", "cancelled");
                        node.put("reason", e.reason());
                    }
                    case DeferEvent.ActionRescheduled e -> {
                        node.put("type", "rescheduled");
                        node.put("reason", e.reason());
                        node.put("delaySeconds", e.delaySeconds());
                        node.put("newRunAt", e.newRunAt().toString());
                    }
                }
                events.add(node);
            }
            result.set("events", events);
            return result;
        });

        router.register("skill.draft.events.poll", params -> {
            long afterSeq = 0L;
            int limit = 20;
            if (params != null) {
                if (params.has("afterSeq")) {
                    afterSeq = Math.max(0L, params.get("afterSeq").asLong());
                }
                if (params.has("limit")) {
                    limit = params.get("limit").asInt(20);
                }
            }

            var polled = skillDraftEventFeed.poll(afterSeq, limit);
            var result = objectMapper.createObjectNode();
            result.put("nextSeq", polled.nextSequence());
            var events = objectMapper.createArrayNode();
            for (var entry : polled.entries()) {
                var node = objectMapper.createObjectNode();
                node.put("seq", entry.sequence());
                var event = entry.event();
                node.put("type", event.type());
                node.put("timestamp", event.timestamp().toString());
                node.put("trigger", event.trigger());
                node.put("skillName", event.skillName());
                node.put("draftPath", event.draftPath());
                node.put("candidateId", event.candidateId());
                if (!event.verdict().isBlank()) {
                    node.put("verdict", event.verdict());
                }
                if (!event.releaseStage().isBlank()) {
                    node.put("releaseStage", event.releaseStage());
                }
                node.put("paused", event.paused());
                var reasons = objectMapper.createArrayNode();
                event.reasons().forEach(reasons::add);
                node.set("reasons", reasons);
                events.add(node);
            }
            result.set("events", events);
            return result;
        });

        router.register("learning.explanations.list", params -> {
            Path projectPath = workingDir;
            int limit = 30;
            if (params != null) {
                if (params.has("project") && !params.get("project").asText("").isBlank()) {
                    projectPath = Path.of(params.get("project").asText()).toAbsolutePath().normalize();
                }
                if (params.has("limit")) {
                    limit = Math.max(1, Math.min(200, params.get("limit").asInt(30)));
                }
            }

            var result = objectMapper.createObjectNode();
            var explanations = objectMapper.createArrayNode();
            for (var explanation : learningExplanationStore.recent(projectPath, limit)) {
                var node = objectMapper.createObjectNode();
                node.put("timestamp", explanation.timestamp().toString());
                node.put("actionType", explanation.actionType());
                node.put("targetType", explanation.targetType());
                node.put("targetId", explanation.targetId());
                node.put("sessionId", explanation.sessionId());
                node.put("trigger", explanation.trigger());
                node.put("summary", explanation.summary());
                var tags = objectMapper.createArrayNode();
                explanation.tags().forEach(tags::add);
                node.set("tags", tags);
                var evidence = objectMapper.createArrayNode();
                for (var ref : explanation.evidence()) {
                    var en = objectMapper.createObjectNode();
                    en.put("type", ref.type());
                    en.put("ref", ref.ref());
                    en.put("detail", ref.detail());
                    evidence.add(en);
                }
                node.set("evidence", evidence);
                explanations.add(node);
            }
            result.set("explanations", explanations);
            return result;
        });

        router.register("learning.summary", params -> {
            Path projectPath = workingDir;
            int limit = 10;
            if (params != null) {
                if (params.has("project") && !params.get("project").asText("").isBlank()) {
                    projectPath = Path.of(params.get("project").asText()).toAbsolutePath().normalize();
                }
                if (params.has("limit")) {
                    limit = Math.max(1, Math.min(50, params.get("limit").asInt(10)));
                }
            }

            var result = objectMapper.createObjectNode();
            var explanationCounts = objectMapper.createObjectNode();
            var validationCounts = objectMapper.createObjectNode();
            var reviewCounts = objectMapper.createObjectNode();
            var recentActions = objectMapper.createArrayNode();
            var recentValidations = objectMapper.createArrayNode();
            var recentReviews = objectMapper.createArrayNode();
            var maintenanceRuns = objectMapper.createArrayNode();
            var latestReviews = learningSignalReviewStore.latestByTarget(projectPath);

            for (var explanation : learningExplanationStore.recent(projectPath, 100)) {
                explanationCounts.put(
                        explanation.actionType(),
                        explanationCounts.path(explanation.actionType()).asInt(0) + 1);
            }
            var recentExplanations = learningExplanationStore.recent(projectPath, Integer.MAX_VALUE);
            for (var explanation : recentExplanations) {
                if (!isOperatorFacingAction(explanation.actionType())) {
                    continue;
                }
                var node = objectMapper.createObjectNode();
                node.put("timestamp", explanation.timestamp().toString());
                node.put("actionType", explanation.actionType());
                node.put("targetType", explanation.targetType());
                node.put("targetId", explanation.targetId());
                node.put("summary", explanation.summary());
                node.put("trigger", explanation.trigger());
                applyReviewMetadata(node, explanation.targetType(), explanation.targetId(), latestReviews);
                recentActions.add(node);
                if (recentActions.size() >= limit) {
                    break;
                }
            }

            for (var validation : learningValidationStore.recent(projectPath, 100)) {
                var key = validation.verdict().name().toLowerCase();
                validationCounts.put(key, validationCounts.path(key).asInt(0) + 1);
            }
            for (var validation : learningValidationStore.recent(projectPath, limit)) {
                var node = objectMapper.createObjectNode();
                node.put("timestamp", validation.timestamp().toString());
                node.put("targetType", validation.targetType());
                node.put("targetId", validation.targetId());
                node.put("verdict", validation.verdict().name().toLowerCase());
                node.put("policy", validation.policy());
                node.put("summary", validation.summary());
                applyReviewMetadata(node, validation.targetType(), validation.targetId(), latestReviews);
                recentValidations.add(node);
            }

            for (var review : learningSignalReviewStore.recent(projectPath, REVIEW_COUNT_WINDOW)) {
                var key = review.action().name().toLowerCase(Locale.ROOT);
                reviewCounts.put(key, reviewCounts.path(key).asInt(0) + 1);
            }
            for (var review : learningSignalReviewStore.recent(projectPath, limit)) {
                var key = review.action().name().toLowerCase(Locale.ROOT);
                var node = objectMapper.createObjectNode();
                node.put("timestamp", review.timestamp().toString());
                node.put("targetType", review.targetType());
                node.put("targetId", review.targetId());
                node.put("action", key);
                node.put("summary", review.summary());
                node.put("note", review.note());
                node.put("reviewer", review.reviewer());
                recentReviews.add(node);
            }

            int totalDeduped = 0;
            int totalMerged = 0;
            int totalPruned = 0;
            for (var run : learningMaintenanceRunStore.recent(projectPath, limit)) {
                totalDeduped += run.deduped();
                totalMerged += run.merged();
                totalPruned += run.pruned();
                var node = objectMapper.createObjectNode();
                node.put("timestamp", run.timestamp().toString());
                node.put("trigger", run.trigger());
                node.put("summary", run.summary());
                node.put("deduped", run.deduped());
                node.put("merged", run.merged());
                node.put("pruned", run.pruned());
                node.put("errorChains", run.errorChains());
                node.put("stableWorkflows", run.stableWorkflows());
                node.put("convergingStrategies", run.convergingStrategies());
                node.put("degradationSignals", run.degradationSignals());
                node.put("trends", run.trends());
                node.put("candidateObservations", run.candidateObservations());
                node.put("candidateTransitions", run.candidateTransitions());
                node.put("candidatePromoted", run.candidatePromoted());
                maintenanceRuns.add(node);
            }

            var maintenanceTotals = objectMapper.createObjectNode();
            maintenanceTotals.put("deduped", totalDeduped);
            maintenanceTotals.put("merged", totalMerged);
            maintenanceTotals.put("pruned", totalPruned);
            result.set("maintenanceTotals", maintenanceTotals);
            result.set("explanationCounts", explanationCounts);
            result.set("validationCounts", validationCounts);
            result.set("reviewCounts", reviewCounts);
            result.set("recentActions", recentActions);
            result.set("recentValidations", recentValidations);
            result.set("recentReviews", recentReviews);
            result.set("maintenanceRuns", maintenanceRuns);
            return result;
        });

        router.register("learning.review.list", params -> {
            Path projectPath = workingDir;
            int limit = 20;
            if (params != null) {
                if (params.has("project") && !params.get("project").asText("").isBlank()) {
                    projectPath = Path.of(params.get("project").asText()).toAbsolutePath().normalize();
                }
                if (params.has("limit")) {
                    limit = Math.max(1, Math.min(100, params.get("limit").asInt(20)));
                }
            }

            var result = objectMapper.createObjectNode();
            var reviews = objectMapper.createArrayNode();
            for (var review : learningSignalReviewStore.recent(projectPath, limit)) {
                var node = objectMapper.createObjectNode();
                node.put("timestamp", review.timestamp().toString());
                node.put("targetType", review.targetType());
                node.put("targetId", review.targetId());
                node.put("action", review.action().name().toLowerCase(Locale.ROOT));
                node.put("summary", review.summary());
                node.put("note", review.note());
                node.put("reviewer", review.reviewer());
                node.put("sessionId", review.sessionId());
                reviews.add(node);
            }
            result.set("reviews", reviews);
            return result;
        });

        router.register("learning.reviewable.list", params -> {
            Path projectPath = workingDir;
            int limit = 20;
            if (params != null) {
                if (params.has("project") && !params.get("project").asText("").isBlank()) {
                    projectPath = Path.of(params.get("project").asText()).toAbsolutePath().normalize();
                }
                if (params.has("limit")) {
                    limit = Math.max(1, Math.min(100, params.get("limit").asInt(20)));
                }
            }

            var result = objectMapper.createObjectNode();
            var signals = objectMapper.createArrayNode();
            var latestReviews = learningSignalReviewStore.latestByTarget(projectPath);
            var seen = new java.util.LinkedHashSet<String>();
            for (var explanation : learningExplanationStore.recent(projectPath, 200)) {
                if (HUMAN_REVIEW_APPLIED_ACTION.equals(explanation.actionType())) {
                    continue;
                }
                if (explanation.targetType().isBlank() || explanation.targetId().isBlank()) {
                    continue;
                }
                String key = explanation.targetType() + ":" + explanation.targetId();
                if (!seen.add(key)) {
                    continue;
                }
                var node = objectMapper.createObjectNode();
                node.put("kind", "explanation");
                node.put("timestamp", explanation.timestamp().toString());
                node.put("actionType", explanation.actionType());
                node.put("targetType", explanation.targetType());
                node.put("targetId", explanation.targetId());
                node.put("summary", explanation.summary());
                node.put("trigger", explanation.trigger());
                applyReviewMetadata(node, explanation.targetType(), explanation.targetId(), latestReviews);
                signals.add(node);
                if (signals.size() >= limit) {
                    break;
                }
            }
            result.set("signals", signals);
            return result;
        });

        router.register("learning.review.apply", params -> {
            if (params == null) {
                throw new IllegalArgumentException("learning.review.apply requires params");
            }
            Path projectPath = workingDir;
            if (params.has("project") && !params.get("project").asText("").isBlank()) {
                projectPath = Path.of(params.get("project").asText()).toAbsolutePath().normalize();
            }
            String targetType = params.path("targetType").asText("").trim();
            String targetId = params.path("targetId").asText("").trim();
            String actionText = params.path("action").asText("").trim();
            String note = params.path("note").asText("").trim();
            String reviewer = params.path("reviewer").asText("human").trim();
            String sessionId = params.path("sessionId").asText("").trim();
            if (targetType.isBlank() || targetId.isBlank() || actionText.isBlank()) {
                throw new IllegalArgumentException("targetType, targetId, and action are required");
            }
            var action = LearningSignalReview.Action.valueOf(actionText.toUpperCase(Locale.ROOT));
            String summary = "Human review marked " + targetType + " '" + targetId + "' as "
                    + action.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".";
            learningExplanationRecorder.recordHumanReview(projectPath, targetType, targetId, action, note, reviewer, sessionId);
            learningValidationRecorder.recordHumanReview(projectPath, targetType, targetId, action, note, reviewer, sessionId);
            learningSignalReviewStore.append(projectPath, new LearningSignalReview(
                    Instant.now(),
                    targetType,
                    targetId,
                    action,
                    summary,
                    note,
                    reviewer,
                    sessionId,
                    List.of("human-review", action.name().toLowerCase(Locale.ROOT), targetType)));

            var result = objectMapper.createObjectNode();
            result.put("applied", true);
            result.put("targetType", targetType);
            result.put("targetId", targetId);
            result.put("action", action.name().toLowerCase(Locale.ROOT));
            result.put("summary", summary);
            return result;
        });

        // Deferred action RPC routes
        router.register("deferred.status", params -> {
            var result = objectMapper.createObjectNode();
            boolean deferredRunning = deferredActionScheduler != null && deferredActionScheduler.isRunning();
            result.put("schedulerRunning", deferredRunning);

            var actionsArray = objectMapper.createArrayNode();
            if (deferredActionStore != null) {
                for (var action : deferredActionStore.all()) {
                    var an = objectMapper.createObjectNode();
                    an.put("actionId", action.actionId());
                    an.put("sessionId", action.sessionId());
                    an.put("goal", action.goal());
                    an.put("state", action.state().name());
                    an.put("createdAt", action.createdAt().toString());
                    an.put("runAt", action.runAt().toString());
                    an.put("expiresAt", action.expiresAt().toString());
                    an.put("attempts", action.attempts());
                    an.put("maxRetries", action.maxRetries());
                    if (action.lastError() != null) {
                        an.put("lastError", action.lastError());
                    }
                    if (action.lastOutput() != null) {
                        an.put("lastOutput", action.lastOutput());
                    }
                    actionsArray.add(an);
                }
            }
            result.set("actions", actionsArray);
            return result;
        });

        router.register("deferred.cancel", params -> {
            String actionId = params != null && params.has("actionId")
                    ? params.get("actionId").asText() : null;
            if (actionId == null || actionId.isBlank()) {
                throw new IllegalArgumentException("actionId is required");
            }
            String reason = params.has("reason") ? params.get("reason").asText() : "user-cancelled";

            if (deferredActionScheduler == null) {
                throw new IllegalStateException("Deferred action scheduler is not enabled");
            }

            boolean cancelled = deferredActionScheduler.cancel(actionId, reason);
            var result = objectMapper.createObjectNode();
            result.put("cancelled", cancelled);
            result.put("actionId", actionId);
            return result;
        });

        // Store references for boot execution
        this.bootLlmClient = llmClient;
        this.bootToolRegistry = toolRegistry;
        this.bootModel = model;
        this.bootSystemPrompt = systemPrompt;

        log.info("Agent handler wired: provider={}, model={}, tools={}",
                config.provider(), model, toolRegistry.size());
    }

    private static String summarizePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        String firstLine = prompt.strip().split("\\R", 2)[0].trim();
        if (firstLine.length() <= 120) {
            return firstLine;
        }
        return firstLine.substring(0, 117) + "...";
    }

    private static String cronKind(CronJob job) {
        if (job.id() != null && job.id().startsWith(HeartbeatRunner.JOB_ID_PREFIX)) {
            return "heartbeat-longterm";
        }
        String id = job.id() == null ? "" : job.id().toLowerCase();
        String p = job.prompt() == null ? "" : job.prompt().toLowerCase();
        boolean oneShotHint = id.contains("once") || id.contains("one-shot")
                || p.contains("remove self")
                || p.contains("delete self")
                || p.contains("cron remove");
        return oneShotHint ? "one-shot" : "scheduled";
    }

    private com.fasterxml.jackson.databind.node.ObjectNode toValidationJson(
            ValidationGateEngine.ValidationSummary summary, Path workingDir) {
        var node = objectMapper.createObjectNode();
        node.put("totalDrafts", summary.totalDrafts());
        node.put("passCount", summary.passCount());
        node.put("holdCount", summary.holdCount());
        node.put("blockCount", summary.blockCount());
        node.put("auditFile", workingDir.relativize(summary.auditFile()).toString().replace('\\', '/'));
        var decisions = objectMapper.createArrayNode();
        for (var decision : summary.decisions()) {
            var dn = objectMapper.createObjectNode();
            dn.put("draftPath", decision.draftPath());
            dn.put("verdict", decision.verdict().name().toLowerCase());
            dn.put("evaluatedAt", decision.evaluatedAt().toString());
            dn.put("trigger", decision.trigger());
            var reasons = objectMapper.createArrayNode();
            for (var reason : decision.reasons()) {
                var rn = objectMapper.createObjectNode();
                rn.put("gate", reason.gate());
                rn.put("code", reason.code());
                rn.put("outcome", reason.outcome().name().toLowerCase());
                rn.put("message", reason.message());
                reasons.add(rn);
            }
            dn.set("reasons", reasons);
            decisions.add(dn);
        }
        node.set("decisions", decisions);
        return node;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode toReleaseJson(
            AutoReleaseController.EvaluationSummary summary) {
        var node = objectMapper.createObjectNode();
        var releases = objectMapper.createArrayNode();
        for (var release : summary.releases()) {
            var rn = objectMapper.createObjectNode();
            rn.put("skillName", release.skillName());
            rn.put("draftPath", release.draftPath());
            rn.put("candidateId", release.candidateId());
            rn.put("stage", release.stage().name().toLowerCase());
            rn.put("paused", release.paused());
            rn.put("updatedAt", release.updatedAt().toString());
            rn.put("lastReasonCode", release.lastReasonCode());
            rn.put("lastReason", release.lastReason());
            releases.add(rn);
        }
        var events = objectMapper.createArrayNode();
        for (var event : summary.events()) {
            var en = objectMapper.createObjectNode();
            en.put("timestamp", event.timestamp().toString());
            en.put("trigger", event.trigger());
            en.put("skillName", event.skillName());
            en.put("fromStage", event.fromStage() == null ? "none" : event.fromStage().name().toLowerCase());
            en.put("toStage", event.toStage().name().toLowerCase());
            en.put("reasonCode", event.reasonCode());
            en.put("reason", event.reason());
            events.add(en);
        }
        node.put("totalReleases", summary.releases().size());
        node.put("eventsCount", summary.events().size());
        node.set("releases", releases);
        node.set("events", events);
        return node;
    }

    private void publishSkillDraftCreatedEvents(
            SkillDraftGenerator.GenerationSummary summary,
            Path workingDir,
            String trigger
    ) {
        if (summary == null || summary.draftPaths().isEmpty()) {
            return;
        }
        for (String draftPath : summary.draftPaths()) {
            Path draftFile = workingDir.resolve(draftPath).normalize();
            String skillName = draftFile.getParent() != null ? draftFile.getParent().getFileName().toString() : "";
            String candidateId = "";
            try {
                candidateId = parseDraftFrontmatter(draftFile).getOrDefault("source-candidate-id", "");
            } catch (Exception ignored) {
            }
            learningExplanationRecorder.recordSkillDraft(
                    workingDir,
                    trigger,
                    skillName,
                    draftPath.replace('\\', '/'),
                    candidateId);
            skillDraftEventFeed.append(new SkillDraftEvent(
                    Instant.now(),
                    "draft_created",
                    trigger,
                    skillName,
                    draftPath.replace('\\', '/'),
                    candidateId,
                    "",
                    "",
                    false,
                    List.of()
            ));
        }
    }

    private void publishSkillDraftValidationEvents(
            ValidationGateEngine.ValidationSummary summary,
            Path projectRoot,
            String trigger
    ) {
        if (summary == null || summary.changedDecisions().isEmpty()) {
            return;
        }
        for (var decision : summary.changedDecisions()) {
            learningValidationRecorder.recordDraftValidation(projectRoot, trigger, decision);
            String skillName = skillNameFromDraftPath(decision.draftPath());
            var reasons = decision.reasons().stream()
                    .map(reason -> reason.code() + ": " + reason.message())
                    .toList();
            skillDraftEventFeed.append(new SkillDraftEvent(
                    decision.evaluatedAt(),
                    "validation_changed",
                    trigger,
                    skillName,
                    decision.draftPath(),
                    "",
                    decision.verdict().name().toLowerCase(),
                    "",
                    false,
                    reasons
            ));
        }
    }

    private void publishSkillDraftReleaseEvents(
            AutoReleaseController.EvaluationSummary summary,
            Path projectRoot,
            String trigger
    ) {
        if (summary == null || summary.events().isEmpty()) {
            return;
        }
        for (var event : summary.events()) {
            learningValidationRecorder.recordReleaseValidation(projectRoot, trigger, event);
            var release = summary.releases().stream()
                    .filter(candidate -> candidate.skillName().equals(event.skillName()))
                    .findFirst()
                    .orElse(null);
            skillDraftEventFeed.append(new SkillDraftEvent(
                    event.timestamp(),
                    "release_changed",
                    trigger,
                    event.skillName(),
                    release == null ? "" : release.draftPath(),
                    release == null ? "" : release.candidateId(),
                    "",
                    event.toStage().name().toLowerCase(),
                    release != null && release.paused(),
                    List.of(event.reasonCode() + ": " + event.reason())
            ));
        }
    }

    private static String skillNameFromDraftPath(String draftPath) {
        Path p = Path.of(draftPath.replace('\\', '/'));
        if (p.getNameCount() < 2) {
            return "";
        }
        return p.getName(p.getNameCount() - 2).toString();
    }

    private static Map<String, String> parseDraftFrontmatter(Path draftFile) throws IOException {
        String raw = Files.readString(draftFile);
        String[] lines = raw.split("\n");
        int first = -1;
        int second = -1;
        for (int i = 0; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                if (first < 0) {
                    first = i;
                } else {
                    second = i;
                    break;
                }
            }
        }
        var map = new LinkedHashMap<String, String>();
        if (first < 0 || second <= first) {
            return map;
        }
        for (int i = first + 1; i < second; i++) {
            String line = lines[i];
            int idx = line.indexOf(':');
            if (idx <= 0) continue;
            String key = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(idx + 1).trim();
            map.put(key, stripQuotes(value));
        }
        return map;
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) return value == null ? "" : value;
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Creates a daemon with the default home directory (~/.aceclaw).
     */
    public static AceClawDaemon createDefault() {
        var home = Path.of(System.getProperty("user.home"), ".aceclaw");
        return new AceClawDaemon(home);
    }

    /**
     * Creates a daemon with a custom home directory (for testing).
     */
    public static AceClawDaemon create(Path homeDir) {
        return new AceClawDaemon(homeDir);
    }

    /**
     * Starts the daemon. Blocks until shutdown.
     *
     * @throws DaemonException if the daemon cannot start
     */
    public void start() throws DaemonException {
        log.info("AceClaw daemon starting...");

        // 1. Acquire instance lock
        try {
            var lockResult = lock.tryAcquire();
            switch (lockResult) {
                case DaemonLock.LockResult.Acquired a ->
                        log.info("Instance lock acquired (PID {})", a.pid());
                case DaemonLock.LockResult.AlreadyRunning r ->
                        throw new DaemonException("Another daemon is already running (PID " + r.pid() + ")");
                case DaemonLock.LockResult.StaleLock s ->
                        log.warn("Recovered stale lock from PID {} (auto-reacquired)", s.stalePid());
            }
        } catch (java.io.IOException e) {
            throw new DaemonException("Failed to acquire daemon lock: " + e.getMessage(), e);
        }

        // 2. Register shutdown participants (reverse order of startup)
        router.setShutdownCallback(this::shutdown);

        shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
            @Override public String name() { return "UDS Listener"; }
            @Override public int priority() { return 100; }
            @Override public void onShutdown() { udsListener.stop(); }
        });

        shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
            @Override public String name() { return "Session History"; }
            @Override public int priority() { return 95; }
            @Override public void onShutdown() { historyStore.flushAll(sessionManager.activeSessions()); }
        });

        shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
            @Override public String name() { return "Workspace Attachments"; }
            @Override public int priority() { return 91; }
            @Override public void onShutdown() { router.attachmentRegistry().releaseAll(); }
        });

        shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
            @Override public String name() { return "Session Manager"; }
            @Override public int priority() { return 90; }
            @Override public void onShutdown() { sessionManager.destroyAll(); }
        });

        shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
            @Override public String name() { return "Health Monitor"; }
            @Override public int priority() { return 92; }
            @Override public void onShutdown() { healthMonitor.stop(); }
        });

        shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
            @Override public String name() { return "Event Bus"; }
            @Override public int priority() { return 15; }
            @Override public void onShutdown() { eventBus.stop(); }
        });

        shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
            @Override public String name() { return "Daemon Lock"; }
            @Override public int priority() { return 10; }
            @Override public void onShutdown() { lock.release(); }
        });

        shutdownManager.installShutdownHook();

        // 3. Start health monitor
        healthMonitor.start();

        // 3.5 Execute BOOT.md (best-effort, runs before accepting connections)
        if (config.bootEnabled()) {
            try {
                Path workingDir = Path.of(System.getProperty("user.dir"));
                var bootResult = BootExecutor.execute(
                        homeDir, workingDir,
                        bootLlmClient, bootToolRegistry,
                        bootModel, bootSystemPrompt,
                        config.maxTokens(), config.thinkingBudget(),
                        config.maxTurns(),
                        config.bootTimeoutSeconds());
                if (bootResult.executed()) {
                    log.info("Boot completed: {}", bootResult.summary());
                }
            } catch (Exception e) {
                log.error("Boot execution failed (daemon will continue): {}", e.getMessage(), e);
            }
        } else {
            log.debug("Boot execution disabled via config");
        }

        // 4. Start UDS listener
        try {
            udsListener.start();
        } catch (Exception e) {
            lock.release();
            throw new DaemonException("Failed to start UDS listener: " + e.getMessage(), e);
        }

        // 5. Start cron scheduler (after listener is ready, jobs run in background)
        if (config.schedulerEnabled()) {
            try {
                cronScheduler = new CronScheduler(
                        cronJobStore, bootLlmClient, bootToolRegistry,
                        bootModel, bootSystemPrompt,
                        config.maxTokens(), config.thinkingBudget(),
                        eventBus, config.schedulerTickSeconds());
                cronScheduler.start();

                shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
                    @Override public String name() { return "Cron Scheduler"; }
                    @Override public int priority() { return 88; }
                    @Override public void onShutdown() { cronScheduler.stop(); }
                });
            } catch (Exception e) {
                log.error("Cron scheduler startup failed (daemon will continue): {}", e.getMessage(), e);
            }
        } else {
            log.debug("Cron scheduler disabled via config");
        }

        if (learningMaintenanceScheduler != null) {
            try {
                Path startupWorkingDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
                learningMaintenanceScheduler.start();
                learningMaintenanceScheduler.registerWorkspace(
                        WorkspacePaths.workspaceHash(startupWorkingDir),
                        startupWorkingDir);
                shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
                    @Override public String name() { return "Learning Maintenance Scheduler"; }
                    @Override public int priority() { return 87; }
                    @Override public void onShutdown() { learningMaintenanceScheduler.stop(); }
                });
            } catch (Exception e) {
                log.error("Learning maintenance scheduler startup failed (daemon will continue): {}", e.getMessage(), e);
            }
        }

        // 5.5. Start deferred action scheduler
        if (config.deferredActionEnabled() && deferredActionScheduler != null) {
            try {
                deferredActionScheduler.start();

                shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
                    @Override public String name() { return "Deferred Action Scheduler"; }
                    @Override public int priority() { return 87; }
                    @Override public void onShutdown() { deferredActionScheduler.stop(); }
                });
            } catch (Exception e) {
                log.error("Deferred action scheduler startup failed (daemon will continue): {}", e.getMessage(), e);
            }
        } else if (!config.deferredActionEnabled()) {
            log.debug("Deferred action scheduler disabled via config");
        }

        // 6. Start heartbeat runner (after cron scheduler, syncs HEARTBEAT.md into cron jobs)
        if (config.heartbeatEnabled() && cronScheduler != null) {
            try {
                Path hbWorkingDir = Path.of(System.getProperty("user.dir"));
                var heartbeatRunner = new HeartbeatRunner(
                        cronScheduler, homeDir, hbWorkingDir,
                        config.heartbeatActiveHours(), config.schedulerTickSeconds());
                heartbeatRunner.start();

                shutdownManager.register(new ShutdownManager.ShutdownParticipant() {
                    @Override public String name() { return "Heartbeat Runner"; }
                    @Override public int priority() { return 89; }
                    @Override public void onShutdown() { heartbeatRunner.stop(); }
                });
            } catch (Exception e) {
                log.error("Heartbeat runner startup failed (daemon will continue): {}", e.getMessage(), e);
            }
        } else if (!config.heartbeatEnabled()) {
            log.debug("Heartbeat runner disabled via config");
        } else {
            log.debug("Heartbeat runner enabled but cron scheduler is disabled; skipping startup");
        }

        running = true;
        long bootMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();
        log.info("AceClaw daemon ready (boot: {}ms, socket: {})", bootMs, homeDir.resolve("aceclaw.sock"));

        // 6. Block until shutdown
        awaitShutdown();
    }

    /**
     * Triggers graceful shutdown.
     */
    public void shutdown() {
        if (!running) return;
        running = false;
        log.info("AceClaw daemon shutting down...");
        shutdownManager.executeShutdown();
    }

    /**
     * Returns whether the daemon is currently running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the daemon home directory.
     */
    public Path homeDir() {
        return homeDir;
    }

    /**
     * Returns the session manager.
     */
    public SessionManager sessionManager() {
        return sessionManager;
    }

    /**
     * Returns the request router (for registering additional handlers).
     */
    public RequestRouter router() {
        return router;
    }

    /**
     * Returns the session history store.
     */
    public SessionHistoryStore historyStore() {
        return historyStore;
    }

    /**
     * Returns the auto-memory store (may be null if initialization failed).
     */
    public AutoMemoryStore memoryStore() {
        return memoryStore;
    }

    private void awaitShutdown() {
        try {
            // Wait on the UDS accept thread; when it stops, we're shutting down.
            WaitSupport.awaitCondition(() -> !running || !udsListener.isRunning(), Duration.ofMillis(500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Daemon await interrupted");
        }
    }

    private static boolean shouldPersistSessionAnalysisInsight(SessionAnalyzer.SessionInsight insight) {
        return switch (insight.category()) {
            case CODEBASE_INSIGHT, ANTI_PATTERN, SUCCESSFUL_STRATEGY -> true;
            default -> false;
        };
    }

    private static ObjectMapper createObjectMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Entry point for running the daemon as a standalone process.
     *
     * <p>Used by {@code DaemonStarter} when spawning the daemon in the background.
     */
    public static void main(String[] args) {
        var daemon = AceClawDaemon.createDefault();
        try {
            daemon.start();
        } catch (DaemonException e) {
            log.error("Daemon failed to start: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Exception thrown when the daemon fails to start.
     */
    public static final class DaemonException extends Exception {
        public DaemonException(String message) { super(message); }
        public DaemonException(String message, Throwable cause) { super(message, cause); }
    }

    private static String summarizeTrends(List<TrendDetector.Trend> trends) {
        return trends.stream()
                .limit(3)
                .map(TrendDetector.Trend::description)
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    private static boolean isOperatorFacingAction(String actionType) {
        return switch (actionType) {
            case "runtime_skill_created", "runtime_skill_persisted", "skill_refinement",
                    HUMAN_REVIEW_APPLIED_ACTION,
                    "skill_draft_created", "candidate_transition" -> true;
            default -> false;
        };
    }

    private void applyReviewMetadata(com.fasterxml.jackson.databind.node.ObjectNode node,
                                     String targetType,
                                     String targetId,
                                     Map<String, LearningSignalReview> latestReviews) {
        if (targetType == null || targetType.isBlank() || targetId == null || targetId.isBlank()) {
            return;
        }
        var review = latestReviews.get(targetType + ":" + targetId);
        if (review == null) {
            return;
        }
        node.put("reviewAction", review.action().name().toLowerCase(Locale.ROOT));
        node.put("reviewSummary", review.summary());
    }

    private void runLearningMaintenancePipeline(
            String trigger,
            AutoMemoryStore memoryStore,
            Path archiveDir,
            DailyJournal journal,
            HistoricalIndexRebuilder historicalIndexRebuilder,
            CrossSessionPatternMiner crossSessionPatternMiner,
            TrendDetector trendDetector,
            LearningMaintenanceCandidateBridge maintenanceCandidateBridge,
            CandidateStore candidateStore,
            ValidationGateEngine validationGateEngine,
            AutoReleaseController autoReleaseController,
            java.util.concurrent.locks.ReentrantLock draftPipelineLock,
            String workspaceHash,
            Path workingDir
    ) {
        var maintenanceSummary = new LearningMaintenanceRunBuilder(trigger, workspaceHash, workingDir);
        try {
            var result = MemoryConsolidator.consolidate(memoryStore, workingDir, archiveDir);
            maintenanceSummary.consolidation(result);
            if (result.hasChanges() && journal != null) {
                journal.append("Memory consolidated (" + trigger + "): "
                        + result.deduped() + " deduped, "
                        + result.merged() + " merged, "
                        + result.pruned() + " pruned");
            }
        } catch (Exception e) {
            log.warn("Memory consolidation failed (trigger={}): {}", trigger, e.getMessage());
        }

        // Correction → Rule auto-promotion: detect repeated corrections and promote to ACECLAW.md rules
        try {
            var aceClawMdPath = workingDir.resolve(".aceclaw").resolve("ACECLAW.md");
            var existingFingerprints = CorrectionRulePromoter.loadExistingFingerprints(aceClawMdPath);
            var allEntries = memoryStore.all();
            var promotionResult = CorrectionRulePromoter.detectRepeatedCorrections(
                    allEntries, existingFingerprints);
            if (promotionResult.hasPromotions()) {
                int written = CorrectionRulePromoter.appendRules(aceClawMdPath, promotionResult.rules());
                if (journal != null) {
                    journal.append("Correction rule promotion (" + trigger + "): "
                            + written + " rules promoted from "
                            + promotionResult.scannedCorrections() + " corrections");
                }
                log.info("Auto-promoted {} correction rules to ACECLAW.md (trigger={})",
                        written, trigger);
            }
        } catch (Exception e) {
            log.warn("Correction rule promotion failed (trigger={}): {}", trigger, e.getMessage());
        }

        if (historicalIndexRebuilder != null) {
            try {
                var rebuild = historicalIndexRebuilder.rebuildWorkspaceIfStale(workspaceHash);
                if (rebuild.rebuilt() && journal != null) {
                    journal.append("Historical index rebuilt (" + trigger + "): "
                            + rebuild.rebuiltSessions() + " sessions re-indexed");
                }
            } catch (Exception e) {
                log.warn("Historical index rebuild failed (trigger={}): {}", trigger, e.getMessage());
            }
        }

        CrossSessionPatternMiner.MiningResult miningResult = null;
        if (crossSessionPatternMiner != null && historicalLogIndex != null) {
            try {
                miningResult = crossSessionPatternMiner.mine(
                        historicalLogIndex, memoryStore, workspaceHash, workingDir);
                maintenanceSummary.mining(miningResult);
                if ((!miningResult.frequentErrorChains().isEmpty()
                        || !miningResult.stableWorkflows().isEmpty()
                        || !miningResult.convergingStrategies().isEmpty()
                        || !miningResult.degradationSignals().isEmpty())
                        && journal != null) {
                    journal.append("Cross-session miner (" + trigger + "): "
                            + miningResult.frequentErrorChains().size() + " error chains, "
                            + miningResult.stableWorkflows().size() + " stable workflows, "
                            + miningResult.convergingStrategies().size() + " converging strategies, "
                            + miningResult.degradationSignals().size() + " degradation signals");
                }
            } catch (Exception e) {
                log.warn("Cross-session pattern mining failed (trigger={}): {}", trigger, e.getMessage());
            }
        }

        List<TrendDetector.Trend> trends = List.of();
        if (trendDetector != null && historicalLogIndex != null) {
            try {
                trends = trendDetector.detect(
                        historicalLogIndex, memoryStore, workspaceHash, workingDir);
                maintenanceSummary.trends(trends);
                if (!trends.isEmpty() && journal != null) {
                    journal.append("Trend detector (" + trigger + "): " + trends.size()
                            + " significant trends. " + summarizeTrends(trends));
                }
            } catch (Exception e) {
                log.warn("Trend detection failed (trigger={}): {}", trigger, e.getMessage());
            }
        }

        if (maintenanceCandidateBridge != null && candidateStore != null
                && (miningResult != null || !trends.isEmpty())) {
            try {
                var bridgeResult = maintenanceCandidateBridge.bridge(
                        trigger,
                        miningResult != null ? miningResult
                                : new CrossSessionPatternMiner.MiningResult(List.of(), List.of(), List.of(), List.of()),
                        trends);
                maintenanceSummary.bridge(bridgeResult);
                if (bridgeResult.upserts() > 0 && journal != null) {
                    journal.append("Maintenance candidate bridge (" + trigger + "): "
                            + bridgeResult.upserts() + " observations, "
                            + bridgeResult.transitions() + " transitions, "
                            + bridgeResult.promoted() + " promoted");
                }
                for (var candidate : bridgeResult.observedCandidates()) {
                    learningExplanationRecorder.recordCandidateObservation(
                            workingDir,
                            "",
                            "maintenance-" + trigger,
                            candidate,
                            candidate.content(),
                            List.of(new LearningExplanation.EvidenceRef(
                                    "maintenance-trigger",
                                    trigger,
                                    candidate.toolTag())));
                }
                for (var transition : bridgeResult.stateTransitions()) {
                    learningExplanationRecorder.recordCandidateTransition(
                            workingDir,
                            "",
                            "maintenance-" + trigger,
                            transition);
                }
                if (bridgeResult.promoted() > 0) {
                    triggerMaintenanceDraftLifecycle(
                            "maintenance-" + trigger,
                            workingDir,
                            candidateStore,
                            validationGateEngine,
                            autoReleaseController,
                            draftPipelineLock);
                }
            } catch (Exception e) {
                log.warn("Maintenance candidate bridge failed (trigger={}): {}", trigger, e.getMessage());
            }
        }
        try {
            learningMaintenanceRunStore.append(workingDir, maintenanceSummary.build());
        } catch (Exception e) {
            log.debug("Failed to persist maintenance summary (trigger={}): {}", trigger, e.getMessage());
        }
    }

    private void triggerMaintenanceDraftLifecycle(
            String trigger,
            Path workingDir,
            CandidateStore candidateStore,
            ValidationGateEngine validationGateEngine,
            AutoReleaseController autoReleaseController,
            java.util.concurrent.locks.ReentrantLock draftPipelineLock
    ) {
        if (candidateStore == null || draftPipelineLock == null) {
            return;
        }
        draftPipelineLock.lock();
        try {
            try {
                var generator = new SkillDraftGenerator();
                var summary = generator.generateFromPromoted(candidateStore, workingDir);
                publishSkillDraftCreatedEvents(summary, workingDir, trigger);
                if (validationGateEngine != null && summary.createdDrafts() > 0) {
                    var validation = validationGateEngine.validateAll(workingDir, trigger);
                    publishSkillDraftValidationEvents(validation, workingDir, trigger);
                    if (autoReleaseController != null) {
                        var release = autoReleaseController.evaluateAll(workingDir, candidateStore, trigger);
                        publishSkillDraftReleaseEvents(release, workingDir, trigger);
                    }
                }
            } catch (Exception e) {
                log.warn("Maintenance draft lifecycle failed (trigger={}): {}", trigger, e.getMessage());
            }
        } finally {
            draftPipelineLock.unlock();
        }
    }

    private static final class LearningMaintenanceRunBuilder {
        private final String trigger;
        private final String workspaceHash;
        private final Path workingDir;
        private int deduped;
        private int merged;
        private int pruned;
        private int errorChains;
        private int stableWorkflows;
        private int convergingStrategies;
        private int degradationSignals;
        private int trends;
        private int candidateObservations;
        private int candidateTransitions;
        private int candidatePromoted;

        private LearningMaintenanceRunBuilder(String trigger, String workspaceHash, Path workingDir) {
            this.trigger = trigger;
            this.workspaceHash = workspaceHash;
            this.workingDir = workingDir;
        }

        void consolidation(dev.aceclaw.memory.MemoryConsolidator.ConsolidationResult result) {
            if (result == null) {
                return;
            }
            deduped = result.deduped();
            merged = result.merged();
            pruned = result.pruned();
        }

        void mining(CrossSessionPatternMiner.MiningResult result) {
            if (result == null) {
                return;
            }
            errorChains = result.frequentErrorChains().size();
            stableWorkflows = result.stableWorkflows().size();
            convergingStrategies = result.convergingStrategies().size();
            degradationSignals = result.degradationSignals().size();
        }

        void trends(List<TrendDetector.Trend> trendList) {
            trends = trendList != null ? trendList.size() : 0;
        }

        void bridge(LearningMaintenanceCandidateBridge.BridgeResult bridgeResult) {
            if (bridgeResult == null) {
                return;
            }
            candidateObservations = bridgeResult.upserts();
            candidateTransitions = bridgeResult.transitions();
            candidatePromoted = bridgeResult.promoted();
        }

        LearningMaintenanceRun build() {
            return new LearningMaintenanceRun(
                    Instant.now(),
                    trigger,
                    workspaceHash,
                    workingDir != null ? workingDir.toString() : "",
                    deduped,
                    merged,
                    pruned,
                    errorChains,
                    stableWorkflows,
                    convergingStrategies,
                    degradationSignals,
                    trends,
                    candidateObservations,
                    candidateTransitions,
                    candidatePromoted,
                    "maintenance "
                            + trigger
                            + ": deduped=" + deduped
                            + ", merged=" + merged
                            + ", pruned=" + pruned
                            + ", mining=" + (errorChains + stableWorkflows + convergingStrategies + degradationSignals)
                            + ", trends=" + trends
                            + ", bridge=" + candidateObservations + "/" + candidateTransitions + "/" + candidatePromoted);
        }
    }
}
