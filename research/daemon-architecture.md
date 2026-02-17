# AceClaw Daemon Architecture — Device as Agent

## 1. Executive Summary

AceClaw is not just a CLI tool you invoke and dismiss. **AceClaw is a persistent, always-on AI agent that lives on your device.** The daemon is the heart of the system — a single long-lived JVM process that manages all sessions, memory, scheduling, and infrastructure. CLI sessions, IDE plugins, and future clients are all **thin clients** that connect to this daemon.

This document defines the unified startup architecture, daemon lifecycle, and the "Device as Agent" paradigm.

### Design Shift

```
BEFORE (CLI-first, Gateway optional):
  User types `aceclaw` → starts REPL → does work → exits → everything dies

AFTER (Daemon-first, CLI is a client):
  Daemon runs always → User types `aceclaw` → connects to daemon → does work → disconnects → daemon lives on
```

### Why Daemon-First?

| Problem with CLI-First | Daemon-First Solution |
|------------------------|----------------------|
| No persistent state between sessions | Daemon holds memory, sessions, learned patterns |
| Agent Teams die when CLI exits | Teams live in the daemon, survive client disconnects |
| No proactive capabilities | Daemon can run cron tasks, heartbeats, monitors |
| Can't serve multiple clients | Daemon multiplexes CLI, IDE, MCP, Web clients |
| No background learning | Daemon consolidates memory, refines skills in idle time |
| Cold start every time | Daemon is warm; CLI connects in <10ms |

---

## 2. The "Device as Agent" Paradigm

### 2.1 Vision

Your device IS the agent. AceClaw runs as a system daemon, just like Docker Desktop, Tailscale, or Ollama. It:

- **Learns continuously** — consolidates auto-memory, refines skills, indexes codebases during idle periods
- **Acts proactively** — runs scheduled tasks (HEARTBEAT.md, cron jobs), monitors repositories, watches for CI failures
- **Serves all clients** — CLI, VS Code extension, IntelliJ plugin, web UI, Slack/Discord bots
- **Manages agent teams** — teams persist beyond individual CLI sessions; can run background agents overnight
- **Remembers everything** — session history, project context, and learned patterns survive reboots (persisted to disk)

### 2.2 Comparison with OpenClaw

| Aspect | OpenClaw | AceClaw Daemon |
|--------|---------|----------------|
| Process | `openclaw gateway` — single Node.js | `aceclaw daemon` — single JVM (GraalVM native) |
| Protocol | WebSocket (ws://127.0.0.1:18789) | Unix Domain Socket (default) + WebSocket (optional) |
| Clients | 15+ messaging platforms | CLI, IDE plugins, MCP, Web UI, messaging (extensible) |
| Concurrency | Single-threaded event loop | Virtual threads — true parallel agent execution |
| Memory | ~80-120MB | ~30-50MB (daemon idle), ~120MB (active agents) |
| Startup | ~500-800ms | ~50ms (native image) |
| Instance lock | File-based PID lock | File-based PID lock + Unix socket probe |
| Boot system | BOOT.md on startup | BOOT.md + HEARTBEAT.md + cron jobs |
| Proactive | Heartbeat, cron | Heartbeat, cron, memory consolidation, skill refinement, codebase indexing |

---

## 3. Startup Architecture

### 3.1 Three Modes

```
aceclaw                      → CLI Interactive (default)
  - Auto-starts daemon if not running
  - Connects to daemon via Unix Domain Socket
  - Opens an interactive REPL session
  - Disconnecting leaves daemon alive

aceclaw daemon               → Daemon Explicit Start
  - Starts the daemon in foreground (for systemd/launchd)
  - Full infrastructure: Gateway + EventBus + Scheduler + Health + Memory

aceclaw daemon --background  → Daemon Background
  - Forks and daemonizes
  - Writes PID to ~/.aceclaw/aceclaw.pid
  - Logs to ~/.aceclaw/logs/daemon.log

aceclaw <prompt>             → One-shot Mode
  - Auto-starts daemon if not running
  - Sends prompt, waits for response, exits
  - Daemon stays alive

aceclaw daemon stop          → Graceful Shutdown
  - Sends shutdown signal to daemon
  - Waits for in-flight sessions to complete
  - Persists all state

aceclaw daemon status        → Status Check
  - Shows daemon health, active sessions, memory usage
```

### 3.2 Auto-Start Behavior

When a user types `aceclaw` (CLI mode), the startup sequence is:

```
1. Probe Unix socket at ~/.aceclaw/aceclaw.sock
   |
   +-- Socket responds? → Connect as client → Open REPL session
   |
   +-- No socket / no response?
       |
       +-- Check PID file at ~/.aceclaw/aceclaw.pid
       |   |
       |   +-- PID alive? → Socket might be starting → retry (3x, 500ms)
       |   +-- PID dead? → Remove stale PID file
       |
       +-- Start daemon in background (fork)
       +-- Wait for socket ready (max 2s)
       +-- Connect as client → Open REPL session
```

**Total startup time for user**: <100ms (daemon already running) or <2s (cold daemon start).

### 3.3 Instance Locking

Only one daemon per user. Lock mechanism (inspired by OpenClaw's `gateway-lock.ts`):

```java
public class DaemonLock {
    private static final Path LOCK_FILE = ACECLAW_HOME.resolve("aceclaw.lock");
    private static final Path PID_FILE = ACECLAW_HOME.resolve("aceclaw.pid");
    private static final Path SOCKET_PATH = ACECLAW_HOME.resolve("aceclaw.sock");

    public sealed interface LockResult permits
        LockResult.Acquired,
        LockResult.AlreadyRunning,
        LockResult.StaleLock {

        record Acquired(FileLock lock) implements LockResult {}
        record AlreadyRunning(int pid, Instant startedAt) implements LockResult {}
        record StaleLock(int deadPid) implements LockResult {}
    }

    public LockResult acquire() {
        // 1. Try exclusive file lock (atomic, OS-enforced)
        // 2. Write PID + timestamp + socket path
        // 3. On stale detection: verify process alive via /proc/{pid} or kill -0
        // 4. Linux: compare process start times (detect PID reuse)
    }
}
```

---

## 4. Daemon Architecture

### 4.1 Component Hierarchy

```
AceClaw Daemon Process (JVM / GraalVM Native)
|
+-- DaemonLock (single instance enforcement)
|
+-- Gateway (control plane)
|   +-- ConnectionManager
|   |   +-- UnixSocketListener      (CLI clients)
|   |   +-- WebSocketListener        (IDE plugins, Web UI)
|   |   +-- InProcessConnections     (subagents, teammates)
|   |
|   +-- RequestRouter (sealed GatewayRequest dispatch)
|   +-- SessionManager (multi-session support)
|
+-- AgentRuntime
|   +-- AgentLoop (ReAct engine)
|   +-- TaskPlanner (DAG planning)
|   +-- ToolRegistry
|   +-- SubAgentOrchestrator
|   +-- TeamManager
|
+-- InfrastructureLayer
|   +-- EventBus (type-safe, virtual threads)
|   +-- MessageQueue (inter-agent, dual-mode transport)
|   +-- Scheduler (cron, heartbeat, maintenance)
|   +-- HealthMonitor (component health + agent liveness)
|   +-- CircuitBreaker (LLM API resilience)
|
+-- MemorySubsystem
|   +-- ProjectMemory (ACECLAW.md)
|   +-- AutoMemory (self-learning)
|   +-- ConversationStore (session persistence)
|   +-- MemoryConsolidator (idle-time background learning)
|   +-- CodebaseIndexer (incremental codebase indexing)
|
+-- SecurityLayer
|   +-- PermissionPolicy
|   +-- Sandbox
|   +-- AuditLog
|   +-- SecretDetector
|
+-- LifecycleManager
    +-- BootSystem (BOOT.md execution)
    +-- GracefulShutdown (ordered 15-step sequence)
    +-- StateSerializer (persist all state on shutdown)
    +-- SignalHandler (SIGTERM, SIGINT, SIGHUP)
```

### 4.2 Boot Sequence

```java
public class AceClawDaemon {

    public void start(DaemonConfig config) {
        // Phase 1: Lock & Initialize (0-10ms)
        var lock = DaemonLock.acquire();
        if (lock instanceof LockResult.AlreadyRunning r) {
            System.err.println("Daemon already running (PID " + r.pid() + ")");
            System.exit(1);
        }
        SignalHandler.install(this::shutdown);

        // Phase 2: Infrastructure (10-30ms)
        var eventBus = new VirtualThreadEventBus(config.eventBus());
        var scheduler = new VirtualThreadScheduler(config.scheduler());
        var healthMonitor = new HealthMonitor(eventBus);
        var circuitBreaker = new CircuitBreakerRegistry();

        // Phase 3: Memory & Security (30-50ms)
        var memorySubsystem = MemorySubsystem.initialize(config.memory());
        var securityLayer = SecurityLayer.initialize(config.security());
        var auditLog = new StructuredAuditLog(config.audit());

        // Phase 4: Agent Runtime (50-80ms)
        var llmClient = FailoverLLMClient.create(config.llm(), circuitBreaker);
        var toolRegistry = ToolRegistry.defaults(securityLayer);
        var agentRuntime = new AgentRuntime(llmClient, toolRegistry,
            memorySubsystem, securityLayer);

        // Phase 5: Gateway (80-100ms)
        var gateway = new Gateway(config.gateway());
        gateway.registerHandler(new AgentRequestHandler(agentRuntime));
        gateway.registerHandler(new ToolRequestHandler(toolRegistry));
        gateway.registerHandler(new HealthRequestHandler(healthMonitor));
        gateway.registerHandler(new AdminRequestHandler(this));

        // Phase 6: Listeners (100-120ms)
        gateway.listen(new UnixSocketListener(SOCKET_PATH));
        if (config.gateway().webSocketPort() > 0) {
            gateway.listen(new WebSocketListener(config.gateway().webSocketPort()));
        }

        // Phase 7: Scheduled Tasks (120-150ms)
        scheduler.schedule("health-check", config.healthCheckInterval(),
            () -> healthMonitor.performHealthChecks());
        scheduler.schedule("memory-consolidation", Duration.ofHours(1),
            () -> memorySubsystem.consolidate());
        scheduler.schedule("codebase-index", Duration.ofMinutes(30),
            () -> memorySubsystem.indexActiveProjects());

        // Phase 8: Boot Script
        bootSystem.execute(config.bootScript()); // BOOT.md

        // Phase 9: Ready
        eventBus.publish(new SystemEvent.DaemonReady(Instant.now()));
        log.info("AceClaw daemon ready (PID {}, socket {})",
            ProcessHandle.current().pid(), SOCKET_PATH);
    }
}
```

**Total boot time**: ~150ms (native image) — warm enough for auto-start scenarios.

---

## 5. Client-Daemon Communication

### 5.1 Transport: Unix Domain Socket (Primary)

Why UDS over TCP:
- **No network stack overhead** — direct kernel IPC, ~3x faster than localhost TCP
- **File-permission security** — socket file inherits user permissions (no port scanning)
- **No port conflicts** — no need to find/reserve a TCP port
- **Credential passing** — kernel verifies client PID/UID (SO_PEERCRED)

```java
// Daemon side
var serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
serverChannel.bind(new UnixDomainSocketAddress(SOCKET_PATH));

// Client side (aceclaw CLI)
var clientChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
clientChannel.connect(new UnixDomainSocketAddress(SOCKET_PATH));
```

### 5.2 Transport: WebSocket (Secondary, Optional)

For IDE plugins and remote clients:
- Enabled via `aceclaw daemon --websocket --port 18790`
- Authenticated via token (generated at daemon start, stored in `~/.aceclaw/auth-token`)
- TLS optional (for remote access)

### 5.3 Protocol: JSON-RPC 2.0

All client-daemon communication uses JSON-RPC:

```json
// Client -> Daemon: Start a new session
{"jsonrpc": "2.0", "method": "session.create", "params": {"project": "/path/to/project"}, "id": 1}

// Client -> Daemon: Send a prompt
{"jsonrpc": "2.0", "method": "agent.prompt", "params": {"sessionId": "abc", "prompt": "Add JWT auth"}, "id": 2}

// Daemon -> Client: Streaming response (notification)
{"jsonrpc": "2.0", "method": "agent.stream", "params": {"sessionId": "abc", "delta": "Let me "}}
{"jsonrpc": "2.0", "method": "agent.stream", "params": {"sessionId": "abc", "delta": "analyze..."}}

// Daemon -> Client: Tool permission request
{"jsonrpc": "2.0", "method": "permission.request", "params": {"tool": "Bash", "command": "npm install"}, "id": 100}

// Client -> Daemon: Permission response
{"jsonrpc": "2.0", "result": {"approved": true}, "id": 100}

// Daemon -> Client: Session complete
{"jsonrpc": "2.0", "method": "agent.complete", "params": {"sessionId": "abc", "summary": "..."}}
```

### 5.4 RPC Method Categories

| Category | Methods | Description |
|----------|---------|-------------|
| **session** | create, resume, list, destroy, compact | Session lifecycle |
| **agent** | prompt, cancel, stream | Agent interaction |
| **permission** | request, respond, update | Permission flow |
| **tool** | execute, list, status | Direct tool execution |
| **memory** | query, update, consolidate | Memory operations |
| **team** | create, spawn, shutdown, message | Agent team management |
| **task** | create, update, list, get | Task management |
| **plan** | generate, approve, execute, status | Task Planner |
| **health** | status, report | Daemon health |
| **admin** | shutdown, config, logs | Daemon administration |
| **cron** | add, remove, list, run | Scheduled tasks |
| **mcp** | servers, connect, disconnect | MCP server management |

---

## 6. Session Management

### 6.1 Multi-Session Daemon

The daemon supports multiple concurrent sessions (unlike CLI-first where one process = one session):

```java
public class SessionManager {
    private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();

    /**
     * Create a new session for a client connection.
     * Each session has its own:
     * - ConversationContext (message history)
     * - MemoryInjections (project-specific memory)
     * - PermissionPolicy (session-scoped permissions)
     * - Working directory context
     */
    public AgentSession createSession(SessionConfig config, ConnectionHandle client) {
        var session = AgentSession.builder()
            .id(generateSessionId())
            .project(config.projectPath())
            .client(client)
            .memory(memorySubsystem.forProject(config.projectPath()))
            .permissions(PermissionPolicy.forMode(config.permissionMode()))
            .build();

        sessions.put(session.id(), session);
        eventBus.publish(new SessionEvent.Created(session.id()));
        return session;
    }

    /**
     * Resume a previous session (conversation history preserved).
     */
    public AgentSession resumeSession(String sessionId, ConnectionHandle client) {
        var session = sessions.get(sessionId);
        if (session == null) {
            // Try loading from disk
            session = conversationStore.load(sessionId);
        }
        session.reconnect(client);
        return session;
    }
}
```

### 6.2 Session Lifecycle

```
Client connects → session.create / session.resume
    |
    v
Active Session (client sends prompts, receives responses)
    |
    +-- Client disconnects → session goes idle (preserved in daemon memory)
    |   |
    |   +-- Client reconnects → session.resume (instant, no cold start)
    |   +-- Idle timeout (configurable, default 24h) → session archived to disk
    |
    +-- session.destroy → conversation saved to disk, memory freed
```

### 6.3 Background Sessions

Sessions can run without an active client connection:

```
// Agent Teams: teammates run as background sessions
// Cron jobs: scheduled tasks run as ephemeral sessions
// HEARTBEAT.md: periodic check-in sessions

Client disconnects while team is working
    → Team sessions continue in daemon
    → Results available when client reconnects
```

---

## 7. Proactive Agent Capabilities

### 7.1 Heartbeat System (HEARTBEAT.md)

Like OpenClaw, the daemon periodically executes agent tasks defined in `HEARTBEAT.md`:

```java
public class HeartbeatRunner {
    private final Scheduler scheduler;
    private final AgentRuntime agentRuntime;

    public void start(HeartbeatConfig config) {
        scheduler.schedule("heartbeat", config.interval(), () -> {
            // Check active hours (respect quiet hours)
            if (!isWithinActiveHours(config.activeHours())) return;

            // Load HEARTBEAT.md
            Path heartbeatFile = config.projectRoot().resolve(".aceclaw/HEARTBEAT.md");
            String instructions = Files.readString(heartbeatFile);
            if (instructions.isBlank()) return;

            // Execute as isolated session
            var session = sessionManager.createEphemeral(config.projectRoot());
            agentRuntime.execute(session, instructions);

            // Publish results
            eventBus.publish(new HeartbeatEvent(session.id(), session.result()));
        });
    }
}
```

### 7.2 Cron Scheduler

Persistent cron jobs stored in `~/.aceclaw/cron/jobs.json`:

```java
public sealed interface CronSchedule permits
    CronSchedule.Interval,
    CronSchedule.CronExpression,
    CronSchedule.EventTriggered {

    record Interval(Duration every) implements CronSchedule {}
    record CronExpression(String expression) implements CronSchedule {}
    record EventTriggered(String eventPattern) implements CronSchedule {}
}

public record CronJob(
    String id,
    String name,
    CronSchedule schedule,
    String prompt,              // What to tell the agent
    Path projectRoot,           // Working directory
    boolean enabled,
    Instant lastRun,
    CronJobResult lastResult
) {}
```

Example cron jobs:
```json
[
  {
    "id": "daily-test",
    "name": "Run test suite",
    "schedule": {"type": "cron", "expression": "0 9 * * *"},
    "prompt": "Run the full test suite. If any tests fail, analyze the failures and create a summary.",
    "projectRoot": "/Users/alex/myproject",
    "enabled": true
  },
  {
    "id": "dep-check",
    "name": "Dependency audit",
    "schedule": {"type": "interval", "every": "P7D"},
    "prompt": "Check for outdated or vulnerable dependencies. Create a report.",
    "projectRoot": "/Users/alex/myproject",
    "enabled": true
  }
]
```

### 7.3 Background Memory Consolidation

During idle periods, the daemon improves its own intelligence:

```java
public class MemoryConsolidator {

    /**
     * Runs during daemon idle time (no active sessions).
     * Uses low-priority virtual threads to avoid impacting active work.
     */
    public void consolidate() {
        // 1. Merge similar auto-memory entries
        deduplicateMemories();

        // 2. Score and prune low-value memories
        pruneByRelevanceScore();

        // 3. Analyze accumulated patterns -> propose new skills
        var patterns = autoMemory.getByCategory(MemoryCategory.PATTERN);
        skillProposalEngine.analyzeAndPropose(patterns);

        // 4. Refine underperforming skills
        var metrics = skillRegistry.getMetrics();
        skillRefinementEngine.refineUnderperformers(metrics);

        // 5. Incremental codebase indexing (for active projects)
        for (var project : sessionManager.recentProjects()) {
            codebaseIndexer.incrementalIndex(project);
        }
    }
}
```

### 7.4 File Watchers (Future)

Watch project files for changes and react:

```java
public sealed interface WatchTrigger permits
    WatchTrigger.FileChanged,
    WatchTrigger.GitPush,
    WatchTrigger.CIFailed,
    WatchTrigger.DependencyAlert {

    record FileChanged(Path file, WatchEvent.Kind<?> kind) implements WatchTrigger {}
    record GitPush(String branch, String commitHash) implements WatchTrigger {}
    record CIFailed(String pipeline, String failureUrl) implements WatchTrigger {}
    record DependencyAlert(String dependency, String vulnerability) implements WatchTrigger {}
}
```

---

## 8. CLI as Thin Client

### 8.1 CLI Architecture (Revised)

The CLI is now a **thin client** that connects to the daemon:

```java
@Command(name = "aceclaw", description = "AI Coding Agent")
public class AceClawCommand implements Runnable {

    @Command(name = "daemon")
    public static class DaemonCommand {
        @Command(name = "start")
        public void start(@Option(names = "--background") boolean bg,
                          @Option(names = "--websocket") boolean ws,
                          @Option(names = "--port") int port) {
            new AceClawDaemon().start(buildConfig(bg, ws, port));
        }

        @Command(name = "stop")
        public void stop() {
            DaemonClient.connect().send(new AdminRequest.Shutdown());
        }

        @Command(name = "status")
        public void status() {
            var health = DaemonClient.connect().send(new HealthRequest());
            renderHealthReport(health);
        }
    }

    @Override
    public void run() {
        // Default: interactive CLI mode
        var client = ensureDaemonRunning(); // auto-start if needed
        var session = client.createSession(detectProject());
        new TerminalREPL(client, session).run();  // JLine3 REPL
    }

    @Parameters(description = "One-shot prompt")
    String prompt;

    // aceclaw "add auth" → one-shot mode
    public void runOneShot() {
        var client = ensureDaemonRunning();
        var session = client.createSession(detectProject());
        var result = client.prompt(session, prompt);
        System.out.println(result);
    }
}
```

### 8.2 Terminal REPL (Thin Client)

```java
public class TerminalREPL {
    private final DaemonClient client;
    private final AgentSession session;
    private final Terminal terminal;     // JLine3
    private final LineReader reader;     // JLine3

    public void run() {
        while (true) {
            String input = reader.readLine("aceclaw> ");

            if (input.startsWith("/")) {
                handleSlashCommand(input);
                continue;
            }

            // Send prompt to daemon, stream response back
            client.prompt(session.id(), input, delta -> {
                // Real-time streaming to terminal
                terminal.writer().print(delta);
                terminal.writer().flush();
            });
        }
    }
}
```

---

## 9. State Persistence

### 9.1 What Survives Daemon Restart

| Data | Storage | Survives Restart? |
|------|---------|------------------|
| Session conversations | `~/.aceclaw/sessions/{id}.jsonl` | Yes |
| Auto-memory | `~/.aceclaw/memory/` (HMAC-signed) | Yes |
| Project memory | `.aceclaw/ACECLAW.md` | Yes |
| Cron jobs | `~/.aceclaw/cron/jobs.json` | Yes |
| Skill definitions | `.aceclaw/skills/` | Yes |
| Skill metrics | `.aceclaw/skills/*.metrics.json` | Yes |
| Active agent teams | `~/.aceclaw/teams/` | Yes (resumed on restart) |
| In-flight tool executions | N/A | No (re-executed) |
| Event bus subscribers | N/A | No (re-registered on boot) |
| Circuit breaker state | N/A | No (reset to CLOSED) |

### 9.2 Graceful Shutdown Sequence

```
SIGTERM / admin.shutdown received
    |
    v
1. Stop accepting new connections
2. Stop cron scheduler
3. Stop heartbeat runner
4. Broadcast shutdown event to all clients (with reason + restart ETA)
5. Wait for active agent turns to complete (max 30s)
6. Save all session states to disk
7. Persist auto-memory (flush write buffer)
8. Stop agent teams (send ShutdownRequest to all teammates)
9. Close MCP server connections
10. Drain event bus queues
11. Stop health monitor
12. Close all client connections
13. Remove Unix socket file
14. Release PID lock
15. Log final audit entry
```

---

## 10. Platform Integration

### 10.1 macOS (launchd)

```xml
<!-- ~/Library/LaunchAgents/com.aceclaw.daemon.plist -->
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "...">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.aceclaw.daemon</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/local/bin/aceclaw</string>
        <string>daemon</string>
        <string>start</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/Users/{user}/.aceclaw/logs/daemon.log</string>
    <key>StandardErrorPath</key>
    <string>/Users/{user}/.aceclaw/logs/daemon-error.log</string>
</dict>
</plist>
```

### 10.2 Linux (systemd)

```ini
# ~/.config/systemd/user/aceclaw.service
[Unit]
Description=AceClaw AI Coding Agent Daemon
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/aceclaw daemon start
ExecStop=/usr/local/bin/aceclaw daemon stop
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

### 10.3 Install Commands

```bash
# macOS: register as login item
aceclaw daemon install    # Creates launchd plist + loads it

# Linux: register as user service
aceclaw daemon install    # Creates systemd unit + enables it

# Both: uninstall
aceclaw daemon uninstall  # Stops + removes service registration
```

---

## 11. Filesystem Layout

```
~/.aceclaw/
  aceclaw.lock            # Instance lock (PID + timestamp)
  aceclaw.pid             # Daemon PID
  aceclaw.sock            # Unix Domain Socket
  auth-token              # WebSocket authentication token
  config.json             # Global configuration
  ACECLAW.md              # Global user instructions
  logs/
    daemon.log            # Daemon stdout log
    daemon-error.log      # Daemon stderr log
    audit/                # Structured audit logs (JSONL)
  sessions/
    {session-id}.jsonl    # Persisted conversation transcripts
  memory/
    MEMORY.md             # Auto-memory (first 200 lines injected)
    patterns.md           # Learned patterns
    mistakes.md           # Known pitfalls
    strategies.md         # Proven strategies
  cron/
    jobs.json             # Persistent cron job definitions
  teams/
    {team-name}/
      config.json         # Team configuration + members
      inboxes/            # Per-agent message inboxes
  tasks/
    {team-name}/
      {task-id}.json      # Shared task list
  skills/
    {skill-name}/
      SKILL.md            # Skill definition
      metrics.json        # Skill performance metrics
  mcp-servers.json        # MCP server configurations

{project}/.aceclaw/
  ACECLAW.md              # Project-specific instructions
  config.json             # Project-specific settings
  HEARTBEAT.md            # Periodic heartbeat instructions
  BOOT.md                 # Boot-time initialization script
  hooks/
    hooks.json            # Event handlers
  memory/
    MEMORY.md             # Project-specific auto-memory
  skills/                 # Project-specific skills
  commands/               # Project-specific slash commands
```

---

## 12. Roadmap Integration

| Phase | Daemon Scope |
|-------|-------------|
| **Phase 1 (Weeks 1-4)** | **Daemon bootstrap** (lock, PID, signal handling), Unix Domain Socket listener, single-session support, auto-start from CLI, `aceclaw daemon start/stop/status` |
| **Phase 2 (Weeks 5-8)** | Multi-session support, session persistence/resume, cron scheduler, heartbeat runner (HEARTBEAT.md), background memory consolidation, BOOT.md execution |
| **Phase 3 (Weeks 9-12)** | WebSocket listener (IDE integration), JSON-RPC protocol (full method set), MCP server management, skill refinement in idle time, codebase indexing |
| **Phase 4 (Weeks 13-18)** | `aceclaw daemon install` (launchd/systemd), Agent Teams persistence (survive client disconnect), file watchers, remote access (TLS + auth), Web UI backend |

---

## 13. Success Metrics

| Metric | Target |
|--------|--------|
| Daemon boot time (native) | < 150ms |
| CLI-to-daemon connect time | < 10ms (UDS) |
| CLI cold start (daemon not running) | < 2s (auto-start) |
| Daemon idle memory | < 50MB |
| Daemon active memory (3 sessions) | < 200MB |
| Session resume latency | < 50ms |
| Concurrent sessions supported | > 10 |
| Uptime between restarts | > 7 days |
| Graceful shutdown time | < 30s |
