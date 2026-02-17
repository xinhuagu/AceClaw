package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.llm.anthropic.AnthropicClient;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionManager;
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
    private final RequestRouter router;
    private final ConnectionBridge connectionBridge;
    private final UdsListener udsListener;
    private final Instant startedAt;

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

        // Lock
        this.lock = new DaemonLock(homeDir.resolve("aceclaw.pid"));

        // Sessions & history persistence
        this.sessionManager = new SessionManager();
        this.historyStore = new SessionHistoryStore(homeDir);

        // Auto-memory store
        AutoMemoryStore ms = null;
        try {
            ms = new AutoMemoryStore(homeDir);
            ms.load(workingDir);
        } catch (java.io.IOException e) {
            log.warn("Failed to initialize auto-memory store: {}", e.getMessage());
        }
        this.memoryStore = ms;

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
     * <p>Creates the LLM client (from config), tool registry (with all 6 tools),
     * permission manager, and streaming agent loop.
     */
    private void wireAgentHandler(Path workingDir) {
        // 1. LLM client (supports both standard API keys and OAuth tokens)
        String apiKey = config.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("API key not configured; set ANTHROPIC_API_KEY or add apiKey to ~/.aceclaw/config.json");
            apiKey = "not-configured";
        }
        LlmClient llmClient;
        if (config.isOAuthToken()) {
            llmClient = new AnthropicClient(apiKey, config.refreshToken());
            log.info("Using OAuth authentication for LLM client");
        } else {
            llmClient = new AnthropicClient(apiKey);
        }

        // 2. Tool registry with all 6 tools
        var toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool(workingDir));
        toolRegistry.register(new WriteFileTool(workingDir));
        toolRegistry.register(new EditFileTool(workingDir));
        toolRegistry.register(new BashExecTool(workingDir));
        toolRegistry.register(new GlobSearchTool(workingDir));
        toolRegistry.register(new GrepSearchTool(workingDir));
        log.info("Registered {} tools", toolRegistry.size());

        // 3. Permission manager with default policy
        var permissionManager = new PermissionManager(new DefaultPermissionPolicy());

        // 4. System prompt (with auto-memory injection)
        String systemPrompt = SystemPromptLoader.load(workingDir, memoryStore);

        // 5. Streaming agent loop
        String model = config.model();
        var agentLoop = new StreamingAgentLoop(llmClient, toolRegistry, model, systemPrompt);

        // 6. Streaming agent handler
        var agentHandler = new StreamingAgentHandler(
                sessionManager, agentLoop, toolRegistry, permissionManager, objectMapper);
        agentHandler.setLlmConfig(llmClient, model, systemPrompt);
        agentHandler.register(router);

        log.info("Agent handler wired: model={}, tools={}", model, toolRegistry.size());
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
            @Override public String name() { return "Daemon Lock"; }
            @Override public int priority() { return 10; }
            @Override public void onShutdown() { lock.release(); }
        });

        shutdownManager.installShutdownHook();

        // 3. Start UDS listener
        try {
            udsListener.start();
        } catch (Exception e) {
            lock.release();
            throw new DaemonException("Failed to start UDS listener: " + e.getMessage(), e);
        }

        running = true;
        long bootMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();
        log.info("AceClaw daemon ready (boot: {}ms, socket: {})", bootMs, homeDir.resolve("aceclaw.sock"));

        // 4. Block until shutdown
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
