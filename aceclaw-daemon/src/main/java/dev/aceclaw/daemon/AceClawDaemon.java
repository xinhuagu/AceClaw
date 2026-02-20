package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.*;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.infra.event.EventBus;
import dev.aceclaw.infra.health.*;
import dev.aceclaw.llm.LlmClientFactory;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.DailyJournal;
import dev.aceclaw.memory.MarkdownMemoryStore;
import dev.aceclaw.memory.MemoryConsolidator;
import dev.aceclaw.memory.StrategyRefiner;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.mcp.McpClientManager;
import dev.aceclaw.mcp.McpServerConfig;
import dev.aceclaw.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;

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

    private static final Logger log = LoggerFactory.getLogger(AceClawDaemon.class);

    private final Path homeDir;
    private final AceClawConfig config;
    private final ObjectMapper objectMapper;
    private final DaemonLock lock;
    private final ShutdownManager shutdownManager;
    private final SessionManager sessionManager;
    private final SessionHistoryStore historyStore;
    private final AutoMemoryStore memoryStore;
    private final MarkdownMemoryStore markdownStore;
    private final EventBus eventBus;
    private final HealthMonitor healthMonitor;
    private final RequestRouter router;
    private final ConnectionBridge connectionBridge;
    private final UdsListener udsListener;
    private final Instant startedAt;

    // Set during wireAgentHandler(), used for boot execution
    private LlmClient bootLlmClient;
    private ToolRegistry bootToolRegistry;
    private String bootModel;
    private String bootSystemPrompt;

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

        // Markdown memory store (persistent MEMORY.md + topic files)
        MarkdownMemoryStore mds = null;
        try {
            mds = MarkdownMemoryStore.forWorkspace(homeDir, workingDir);
        } catch (java.io.IOException e) {
            log.warn("Failed to initialize markdown memory store: {}", e.getMessage());
        }
        this.markdownStore = mds;

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
        LlmClient rawLlmClient = LlmClientFactory.create(
                config.provider(), apiKey, config.refreshToken(), config.baseUrl(), model);

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

        // Web search (requires Brave Search API key)
        if (config.braveSearchApiKey() != null) {
            toolRegistry.register(new WebSearchTool(workingDir, config.braveSearchApiKey()));
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
        var readOnlyTools = java.util.Set.of(
                "read_file", "glob", "grep", "list_directory",
                "web_fetch", "web_search", "screen_capture", "skill");
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
        if (!skillRegistry.isEmpty()) {
            var contentResolver = new SkillContentResolver(workingDir);
            var skillTool = new SkillTool(skillRegistry, contentResolver, subAgentRunner);
            toolRegistry.register(skillTool);
            log.info("Skills registered: {}", skillRegistry.names());
        } else {
            log.debug("No skills found, SkillTool not registered");
        }

        // 5. System prompt (with 8-tier memory hierarchy + daily journal + model identity + budget)
        //    Budget scales with context window: small models (32K) get smaller memory budgets
        DailyJournal journal = memoryStore != null ? memoryStore.getDailyJournal() : null;
        var promptBudget = SystemPromptBudget.forContextWindow(
                contextWindow, config.maxTokens());
        log.info("System prompt budget: {}K per tier, {}K total (context={}K, maxOutput={}K)",
                promptBudget.maxPerTierChars() / 1000, promptBudget.maxTotalChars() / 1000,
                contextWindow / 1000, config.maxTokens() / 1000);
        String systemPrompt = SystemPromptLoader.load(
                workingDir, memoryStore, journal, markdownStore, model, config.provider(), promptBudget);

        // 5b. Inject skill descriptions into system prompt so the LLM knows
        //     what each skill does and when to invoke it proactively.
        if (!skillRegistry.isEmpty()) {
            String skillDescriptions = skillRegistry.formatDescriptions();
            if (!skillDescriptions.isEmpty()) {
                systemPrompt = systemPrompt + "\n\n" + skillDescriptions;
            }
        }

        // 6. Context compaction (accounting for actual system prompt size)
        int systemPromptTokens = dev.aceclaw.core.agent.ContextEstimator.estimateTokens(systemPrompt);
        var compactionConfig = new CompactionConfig(
                contextWindow, config.maxTokens(), systemPromptTokens,
                0.85, 0.60, 5);
        var compactor = new MessageCompactor(llmClient, model, compactionConfig);
        log.info("System prompt: {} chars (~{} tokens), effective conversation window: {} tokens",
                systemPrompt.length(), systemPromptTokens, compactionConfig.effectiveWindowTokens());

        // 7. Streaming agent loop (with compaction support)
        var agentLoop = new StreamingAgentLoop(
                llmClient, toolRegistry, model, systemPrompt,
                config.maxTokens(), config.thinkingBudget(), compactor);

        // 8. Streaming agent handler
        var agentHandler = new StreamingAgentHandler(
                sessionManager, agentLoop, toolRegistry, permissionManager, objectMapper);
        agentHandler.setLlmConfig(llmClient, model, systemPrompt);
        agentHandler.setTokenConfig(config.maxTokens(), config.thinkingBudget());
        agentHandler.setCompactor(compactor);
        agentHandler.setMemoryStore(memoryStore, workingDir);
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

        // 10. Self-improvement engine (post-turn learning analysis + strategy refinement)
        if (memoryStore != null) {
            var errorDetector = new ErrorDetector(memoryStore);
            var patternDetector = new PatternDetector(memoryStore);
            var strategyRefiner = new StrategyRefiner(memoryStore);
            var selfImprovementEngine = new SelfImprovementEngine(
                    errorDetector, patternDetector, memoryStore, strategyRefiner);
            agentHandler.setSelfImprovementEngine(selfImprovementEngine);
            log.info("Self-improvement engine wired (with strategy refinement)");
        }

        agentHandler.register(router);

        // Session-end memory extraction + consolidation
        // Runs SYNCHRONOUSLY to ensure extraction completes before session deactivation.
        // This is critical during shutdown — async virtual threads may not finish before JVM exits.
        // The extraction is pure-Java regex matching (no LLM calls), so blocking is fast.
        if (memoryStore != null) {
            final var extractionJournal = journal;
            final var archiveDir = markdownStore != null ? markdownStore.memoryDir() : null;
            sessionManager.setSessionEndCallback(session -> {
                var extracted = SessionEndExtractor.extract(session.messages());
                for (var mem : extracted) {
                    try {
                        memoryStore.add(mem.category(), mem.content(), mem.tags(),
                                "session-end:" + session.id(), false, workingDir);
                    } catch (Exception e) {
                        log.warn("Failed to save session-end memory: {}", e.getMessage());
                    }
                }
                if (!extracted.isEmpty()) {
                    log.info("Extracted {} memories from session {} on destroy",
                            extracted.size(), session.id());
                }
                if (extractionJournal != null) {
                    extractionJournal.append("Session " + session.id().substring(0, 8) +
                            " ended: " + session.messages().size() + " messages, " +
                            extracted.size() + " memories extracted");
                }

                // Run memory consolidation after extraction
                try {
                    var result = MemoryConsolidator.consolidate(
                            memoryStore, workingDir, archiveDir);
                    if (result.hasChanges() && extractionJournal != null) {
                        extractionJournal.append("Memory consolidated: " +
                                result.deduped() + " deduped, " +
                                result.merged() + " merged, " +
                                result.pruned() + " pruned");
                    }
                } catch (Exception e) {
                    log.warn("Memory consolidation failed: {}", e.getMessage());
                }
            });
        }

        // Expose model name, provider info, and health monitor to status endpoint
        router.setModelName(model);
        router.setProviderInfo(config.provider(), contextWindow);
        router.setHealthMonitor(healthMonitor);

        // Register model.list and model.switch RPC methods
        final var llmClientRef = llmClient;
        final var agentHandlerRef = agentHandler;
        final var providerNameRef = config.provider();

        // Register model.list and model.switch via shared helper
        ModelRpcHelper.registerModelList(router, objectMapper, agentHandlerRef, llmClientRef, providerNameRef);
        ModelRpcHelper.registerModelSwitch(router, objectMapper, agentHandlerRef, llmClientRef);

        // Store references for boot execution
        this.bootLlmClient = llmClient;
        this.bootToolRegistry = toolRegistry;
        this.bootModel = model;
        this.bootSystemPrompt = systemPrompt;

        log.info("Agent handler wired: provider={}, model={}, tools={}",
                config.provider(), model, toolRegistry.size());
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

        running = true;
        long bootMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();
        log.info("AceClaw daemon ready (boot: {}ms, socket: {})", bootMs, homeDir.resolve("aceclaw.sock"));

        // 5. Block until shutdown
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
            // Wait on the UDS accept thread; when it stops, we're shutting down
            while (running && udsListener.isRunning()) {
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Daemon await interrupted");
        }
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
}
