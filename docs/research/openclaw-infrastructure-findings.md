# OpenClaw Infrastructure & Architecture Findings

> Research conducted 2026-02-16 from [github.com/openclaw/openclaw](https://github.com/openclaw/openclaw)
> OpenClaw is a TypeScript-based personal AI assistant (145k+ GitHub stars) created by Peter Steinberger.

## Executive Summary

OpenClaw has a mature infrastructure layer that Chelava's current design is largely missing. The project centers on a **Gateway** - a single long-lived Node.js process that acts as the entire control plane for sessions, channels, tools, and events. Below are the 12 key infrastructure patterns found, with code references.

---

## 1. Gateway (WebSocket Control Plane)

**Source**: `src/gateway/` (~100+ files)

### Architecture
The Gateway is the heart of OpenClaw. Running `openclaw gateway` starts a single long-lived Node.js process that IS the entire system - channel connections, session state, agent loop, model calls, tool execution, memory persistence.

### Key Components
- **`server.impl.ts`** - Main server orchestrator. Creates HTTP/HTTPS servers with TLS support, WebSocket server (`wss`), client connection manager, and broadcasting mechanisms.
- **`server-ws-runtime.ts`** - WebSocket connection handler with authentication, rate limiting, method routing, and client state tracking.
- **`server-broadcast.ts`** - Scope-based event distribution with permission filtering (`operator.admin`, `operator.approvals`, `operator.pairing`). Handles slow consumers by dropping or disconnecting them.
- **`server-startup.ts`** - Initializes sidecars: browser control, Gmail watcher, hooks, channels, plugins, memory backend, restart sentinel.
- **`server-close.ts`** - Graceful shutdown: stops Bonjour, Tailscale, canvas host, channels, plugins, Gmail watcher, cron, heartbeat; broadcasts shutdown reason to clients with code 1012; clears all timers and resources.

### WebSocket Protocol (`src/gateway/protocol/`)
- **60+ RPC methods** defined in schema: agent CRUD, node operations, sessions, config, wizards, chat, skills, cron jobs, device/exec, polling, wake, logs, models.
- JSON-framed messages with sequence numbers for ordering.
- AJV validation with `allErrors: true` for comprehensive error reporting.
- Frame types: `RequestFrame`, `ResponseFrame`, `EventFrame`, `GatewayFrame`.

### Default endpoint: `ws://127.0.0.1:18789`

### What Chelava Needs
A central Gateway process that:
- Manages WebSocket connections for CLI, UI, and IDE clients
- Routes RPC calls to internal handlers
- Broadcasts events with scope-based permissions
- Handles authentication and rate limiting at the connection level

---

## 2. Heartbeat / Health Check System

**Source**: `src/infra/heartbeat-runner.ts`, `src/infra/heartbeat-events.ts`, `src/infra/heartbeat-active-hours.ts`, `src/infra/heartbeat-visibility.ts`, `src/infra/heartbeat-wake.ts`

### How It Works
The heartbeat is a **periodic agent health-check and notification system** that monitors agent state and sends proactive alerts/status updates.

### Architecture
- **HeartbeatAgentState** map tracks each agent's interval, last execution, and next scheduled time.
- Timer-based scheduler calculates minimum delay across all agents and sets a timeout.
- Configurable active hours (quiet hours) via `isWithinActiveHours()`.
- Checks in-flight request queue size before firing (prevents overlap).

### Execution Flow
1. Validate heartbeats enabled for agent
2. Check quiet hours restrictions
3. Verify no in-flight requests via `getQueueSize()`
4. Read `HEARTBEAT.md` file (skip if empty/comments only)
5. Resolve delivery targets and visibility settings
6. Generate prompt (standard, exec-event, or cron-event)
7. Call LLM for response
8. Deduplicate (skip identical messages within 24h)
9. Normalize response, suppress ack-only replies

### Event System
- Emits `HeartbeatEventPayload` with statuses: `"sent"`, `"ok-empty"`, `"ok-token"`, `"skipped"`, `"failed"`
- Pub/sub via `emitHeartbeatEvent()` / `onHeartbeatEvent()`
- Maps to UI indicators: `"ok"`, `"alert"`, `"error"`

### Error Resilience
If `runOnce()` throws, the runner advances the schedule anyway - subsequent heartbeats keep firing.

### What Chelava Needs
- A `HeartbeatService` that periodically checks agent liveness
- Health status events with pub/sub distribution
- Configurable intervals and quiet hours
- Duplicate suppression logic

---

## 3. Health Check Endpoint

**Source**: `src/gateway/server-methods/health.ts`, `src/gateway/server/health-state.ts`

### Architecture
- **Cached health snapshots** with configurable refresh intervals
- Background async refresh - serves stale cache while computing fresh data
- `probe=true` parameter bypasses cache for on-demand checks
- Error responses use `UNAVAILABLE` error code
- Health version counter for change tracking
- `broadcastHealthUpdate` callback for push-based health notifications

### What Chelava Needs
- `/health` HTTP endpoint with cached snapshots
- Background refresh mechanism
- Probe mode for on-demand deep checks

---

## 4. Cron / Scheduler Service

**Source**: `src/cron/` (~30+ files)

### Architecture
The `CronService` class wraps an ops module and provides full job lifecycle management.

### Schedule Types (`types.ts`)
Three scheduling modes:
- **`"at"`** - Specific time (one-shot)
- **`"every"`** - Interval-based with optional anchor (e.g., "every 4h anchored at 9am")
- **`"cron"`** - Standard cron expression with timezone support

### Job Management
- `add(input)` / `update(id, patch)` / `remove(id)` - Full CRUD
- `run(id, mode)` - Execute with `"due"` (conditional) or `"force"` (immediate) modes
- `list()` with option to include disabled jobs
- `status()` for service state introspection
- `wake()` with `"now"` or `"next-heartbeat"` modes

### Persistence (`store.ts`)
- **File-based JSON5 store** at `{CONFIG_DIR}/cron/jobs.json`
- **Atomic writes** via temp file + rename pattern (prevents corruption)
- PID + random hex in temp filename for concurrent safety
- Automatic `.bak` backup on each write
- Version-tracked store format (currently v1)

### Job State Tracking
Each job maintains: next/last run times, execution status, error counts, duration metrics, and backoff parameters.

### Session Reaper (`session-reaper.ts`)
Built-in GC for ephemeral cron sessions:
- Targets pattern `...:cron:<jobId>:run:<uuid>`
- Default 24h retention, configurable via duration strings
- Self-throttles to minimum 5-minute sweep intervals
- File-lock aware to prevent deadlocks

### Delivery
Results can be delivered via: `"none"`, `"announce"` (to channel), or `"webhook"`.

### Duplicate Prevention
Timer-based scheduling with `prevents-duplicate-timers` logic and `rearm-timer-when-running` pattern.

### What Chelava Needs
- `CronService` with three schedule types (at/every/cron)
- File-based persistent job store with atomic writes
- Session reaper for GC of ephemeral sessions
- Execution modes (due/force)
- Delivery mechanisms (announce/webhook/none)

---

## 5. Event Bus / Pub-Sub System

**Source**: `src/infra/agent-events.ts`, `src/infra/heartbeat-events.ts`, `src/infra/diagnostic-events.ts`, `src/infra/system-events.ts`

### Architecture
OpenClaw uses a **multi-channel in-memory pub/sub** system (NOT a separate message broker). Each event domain has its own emitter/listener pair.

### Agent Events (`agent-events.ts`)
- `registerAgentRunContext(runId, metadata)` - stores session key, verbosity per run
- `emitAgentEvent(event)` - increments monotonic sequence counter, enriches with session key + timestamp, broadcasts to all listeners
- `onAgentEvent(listener)` - returns unsubscribe function
- Event streams: `"lifecycle"`, `"tool"`, `"assistant"`, `"error"`
- Error isolation: one listener's failure doesn't affect others

### Diagnostic Events (`diagnostic-events.ts`)
- 13 event types: model usage, webhooks, messages, sessions, queues, runs, heartbeats, tool loops
- Global sequence counter + timestamp enrichment
- `isDiagnosticsEnabled()` gating for performance
- Observer pattern with `emitDiagnosticEvent()` / `onDiagnosticEvent()`

### System Events (`system-events.ts`)
- **Lightweight in-memory queue** (intentionally no persistence)
- Session-scoped with explicit key requirement
- Max 20 events per session, FIFO eviction
- Deduplication: skips consecutive identical messages
- Context key tracking for change detection

### What Chelava Needs
- `EventBus` abstraction with domain-specific channels (agent, diagnostic, system)
- Listener registration with unsubscribe returns
- Error isolation between listeners
- Monotonic sequence counters for ordering
- Session-scoped event queues

---

## 6. Process Management & Supervisor

**Source**: `src/process/` (supervisor/, child-process-bridge.ts, command-queue.ts, kill-tree.ts, lanes.ts, restart-recovery.ts, exec.ts)

### Process Supervisor (`supervisor/supervisor.ts`)
- **In-memory registry** of active process runs
- Lifecycle: initialization -> spawning -> running -> exiting -> finalized
- Two execution modes: PTY (pseudo-terminal) and child process adapters
- Unique run IDs with metadata (session, backend, scope)
- **Timeouts**: overall max duration + no-output/inactivity timeout
- `touchOutput()` resets inactivity timer on data streams
- `cancel(runId)` via SIGKILL, `cancelScope(key)` for batch cancellation
- `replaceExistingScope` option for auto-cancel-before-spawn
- "Settled" flag prevents duplicate finalization
- **Deliberate no-op** for orphan reconciliation (in-memory only, no persistence)

### Child Process Bridge (`child-process-bridge.ts`)
- Bidirectional signal forwarding (SIGTERM, SIGINT, SIGHUP, SIGQUIT)
- Platform-specific signal handling (Windows vs Unix)
- Auto-detach on child exit/error
- `detach()` method for manual cleanup

### Process Tree Killing (`kill-tree.ts`)
- **Windows**: `taskkill /F /T /PID` (force + tree)
- **Unix**: First tries process group kill (`-pid` negative), falls back to direct kill

### Command Queue (`command-queue.ts`)
- **Lane-based task queue** for serialized execution with optional parallelism
- Lanes: `Main`, `Cron`, `Subagent`, `Nested`
- Configurable concurrency per lane
- Generation-based invalidation for stale tasks during restarts
- Wait time monitoring with `warnAfterMs` threshold (default 2s)
- Queue introspection: `getQueueSize()`, `getActiveTaskCount()`, `waitForActiveTasks()`

### Restart Recovery (`restart-recovery.ts`)
- State machine hook: first iteration = fresh start, subsequent = recovery restart
- Enables different codepaths for initial vs. recovery startup

### What Chelava Needs
- `ProcessSupervisor` with run registry, timeout management, scope-based cancellation
- `ChildProcessBridge` for signal forwarding
- `ProcessTreeKiller` with cross-platform support
- `CommandQueue` with lane-based serialization
- Restart recovery hooks

---

## 7. Service Discovery

**Source**: `src/infra/bonjour-discovery.ts`, `src/infra/bonjour.ts`, `src/gateway/server-discovery.ts`

### Architecture
Multi-layer discovery:

1. **Bonjour/mDNS** (local network):
   - Service type: `_openclaw-gw._tcp`
   - macOS: `dns-sd -B` (browse) + `dns-sd -L` (resolve)
   - Linux: `avahi-browse -rt`
   - TXT records contain: display name, SSH ports, TLS fingerprints

2. **Tailnet DNS** (wide area):
   - Queries Tailscale nameservers when local discovery fails
   - Probes multiple IPs concurrently
   - PTR, SRV, TXT DNS records

3. **Environment variables** fallback:
   - `OPENCLAW_TAILNET_DNS` - explicit DNS override
   - `OPENCLAW_CLI_PATH` - CLI executable path

### What Chelava Needs
- Local service discovery (mDNS/Bonjour or equivalent)
- Environment-based fallback configuration
- Multi-strategy resolution with timeout budgets

---

## 8. Configuration Hot-Reload

**Source**: `src/gateway/config-reload.ts`, `src/gateway/server-runtime-config.ts`, `src/gateway/server-reload-handlers.ts`

### Architecture
- **File watching** via `chokidar` with 300ms debounce
- **Recursive config diffing** via `diffConfigPaths()` to identify changed keys
- **Reload plan builder** categorizes changes:
  - **Restart required**: gateway, discovery, plugins
  - **Hot reload**: hooks, cron, browser control, channels
  - **No-op**: meta, identity, logging

### Modes
- `off` - Disabled
- `restart` - Always restart gateway
- `hot` - Hot reload when possible, warn if restart needed
- `hybrid` - Hot reload + restart only when necessary

### Runtime Config Resolution
Multi-source config merging:
1. Direct parameters (highest priority)
2. Config file values
3. Environment variables (lowest priority)

### What Chelava Needs
- `ConfigReloader` with file watching and debounce
- Diff-based change detection
- Categorized reload plans (restart vs hot-reload vs no-op)
- Multiple reload modes

---

## 9. Graceful Shutdown

**Source**: `src/gateway/server-close.ts`

### Shutdown Sequence
1. Stop Bonjour discovery
2. Stop Tailscale connectivity
3. Stop canvas host processes
4. Stop all channel plugins
5. Halt Gmail watcher
6. Stop cron scheduler
7. Stop heartbeat runner
8. Clear all interval timers (node presence, tick, health checks, deduplication)
9. Execute subscription cleanup callbacks (with error suppression)
10. Broadcast shutdown reason + restart timing to all WebSocket clients (code 1012)
11. Close all WebSocket connections
12. Shut down HTTP servers (close idle connections first)
13. Clear chat run state
14. Stop config reloader
15. Stop browser control

### Key Properties
- Each step wrapped in try-catch (error-tolerant)
- Configurable restart messaging (reason + expected duration)
- Multi-server support (primary + additional HTTP servers)
- Graceful degradation on partial failures

### What Chelava Needs
- Ordered shutdown sequence with dependency awareness
- Error-tolerant cleanup (each step isolated)
- Client notification with shutdown reason
- Resource cleanup verification

---

## 10. Logging & Monitoring

**Source**: `src/logging/` (logger.ts, subsystem.ts, redact.ts, levels.ts, console.ts, diagnostic.ts, timestamps.ts)

### Structured Logging
- Built on **tslog** (TypeScript logger)
- JSON-line format with ISO timestamps
- Levels: `silent`, `trace`, `debug`, `info`, `warn`, `error`, `fatal`
- Child loggers with custom bindings and level overrides
- Pino compatibility adapter for third-party libraries

### Subsystem Organization
- Forward-slash delimited paths (e.g., `"agent/embedded"`)
- Parent-child logger hierarchy
- Color-coded subsystems (hash-based + manual overrides)
- Independent console vs. file log level controls
- Selective subsystem filtering

### Transports
1. **File transport**: JSON lines with auto-directory creation, 24h rolling retention
2. **External transports**: Plugin-registered via `registerLogTransport()`, failure-suppressed
3. **Console transport**: Configurable formatting via `ConsoleStyle`

### Log Redaction (`redact.ts`)
- 16+ regex patterns for sensitive data (API keys, tokens, PEM blocks)
- Masking: first 6 + last 4 chars visible, middle replaced with ellipsis
- Service-specific patterns: OpenAI `sk-`, GitHub `ghp_`, Slack `xox-`
- PEM blocks show header/footer only with `...redacted...`
- Configurable via settings file with custom patterns

### Diagnostic Events
- 13 event types for runtime monitoring
- Gated by `isDiagnosticsEnabled()` for performance
- Global sequence counter for ordering

### What Chelava Needs
- Structured JSON logging with subsystem hierarchy
- Multiple transports (file, console, external)
- Automatic sensitive data redaction
- Diagnostic event system for runtime monitoring
- Rolling log retention

---

## 11. Error Recovery & Fault Tolerance

**Source**: `src/infra/retry.ts`, `src/infra/backoff.ts`, `src/infra/unhandled-rejections.ts`

### Retry Logic (`retry.ts`)
- Simple mode: N retries with exponential backoff (`delay = initialMs * 2^attempt`)
- Advanced mode with:
  - Custom `shouldRetry` callback (selective retry by error type)
  - Server-provided `retryAfterMs` callback
  - Jitter: `delay * (1 +/- jitter)` to prevent thundering herd
  - `onRetry` callback for notifications
- Defaults: 3 attempts, 300ms min, 30s max, zero jitter

### Backoff Strategy (`backoff.ts`)
- Exponential: `base = initialMs * factor^(attempt-1)`
- Jitter: `base * jitter * Math.random()`
- Cap: `Math.min(maxMs, computed)`
- `sleepWithAbort` for cancellable waits via AbortSignal

### Unhandled Rejection Handler (`unhandled-rejections.ts`)
Error categorization with different responses:
1. **AbortErrors** - Log as warning, don't crash (intentional cancellations)
2. **Fatal errors** - Memory exhaustion, script timeout -> immediate exit
3. **Config errors** - Missing API keys -> exit with guidance
4. **Transient network errors** - Connection reset, DNS failure, timeout -> warn only, expect recovery
5. **Other** - Default: exit with formatted error

Additional features:
- Plugin-registered custom handlers via `registerUnhandledRejectionHandler()`
- Cause chain inspection (recursive + AggregateError support)
- Network issues don't crash gateway

### What Chelava Needs
- `RetryService` with exponential backoff + jitter
- Configurable retry policies per operation type
- Categorized error handler (fatal vs transient vs config)
- Custom rejection handler registration for plugins
- Cause chain inspection

---

## 12. Gateway Lock & Instance Management

**Source**: `src/infra/gateway-lock.ts`

### Architecture
Prevents multiple gateway instances from running simultaneously.

- **File-based lock** at `gateway.{sha1(configPath)}.lock`
- Lock payload: PID, timestamp, config path, Linux process start time
- Exclusive creation flag `"wx"` for atomic acquisition
- **Stale lock detection**:
  - Check if PID still alive
  - Linux: compare process start times (detects PID reuse)
  - Read `/proc/{pid}/cmdline` to verify it's actually a gateway process
  - Auto-remove locks older than 30s from dead processes
- 5-second polling timeout for contested locks
- Bypass via `OPENCLAW_ALLOW_MULTI_GATEWAY=1` or test environments

### What Chelava Needs
- File-based instance locking
- Stale lock detection with PID validation
- Configurable timeout for lock acquisition

---

## 13. Maintenance Timers

**Source**: `src/gateway/server-maintenance.ts`

### Periodic Tasks
1. **Tick interval** - Broadcasts keepalive "tick" events with timestamps
2. **Health refresh** - Periodically refreshes cached health snapshot (with initial prime before clients connect)
3. **Dedup cache cleanup** - Multiple cleanup functions:
   - Remove expired dedup entries past TTL
   - Trim dedup map when exceeding max size (remove oldest)
   - Purge agent run sequences beyond 10,000 limit
   - Abort expired chat run controllers
   - Delete aborted run records older than 1 hour

### What Chelava Needs
- `MaintenanceScheduler` with configurable periodic tasks
- Keepalive tick broadcasts
- Cache/state cleanup cycles
- Resource limit enforcement

---

## 14. Boot System

**Source**: `src/gateway/boot.ts`

### How It Works
1. Load `BOOT.md` from workspace directory
2. Generate a unique boot session ID (timestamp + random suffix)
3. Snapshot current session state
4. Execute agent with boot prompt: "Follow BOOT.md instructions exactly"
5. Restore session state after boot (isolated - won't corrupt active sessions)
6. Return status: `ran`, `missing`, `empty`, or failure

### What Chelava Needs
- Boot-time initialization hook system
- Session isolation for boot operations
- Configurable boot scripts

---

## Summary: Missing from Chelava

| Component | Priority | OpenClaw Implementation | Chelava Gap |
|-----------|----------|------------------------|-------------|
| **Gateway (WebSocket)** | Critical | Full WS control plane, 60+ RPC methods | No WebSocket server |
| **Event Bus** | Critical | Multi-channel pub/sub with isolation | No event distribution |
| **Process Supervisor** | Critical | Registry, timeouts, scoped cancellation | Basic process management |
| **Heartbeat/Health** | High | Periodic checks, dedup, active hours | No liveness monitoring |
| **Cron/Scheduler** | High | 3 schedule types, persistent store, GC | No scheduled tasks |
| **Graceful Shutdown** | High | 15-step ordered sequence | No shutdown orchestration |
| **Error Recovery** | High | Retry+backoff+jitter, error categorization | Basic error handling |
| **Structured Logging** | High | Subsystems, transports, redaction | Basic logging |
| **Config Hot-Reload** | Medium | File watch, diff, categorized reload | No hot reload |
| **Gateway Lock** | Medium | File lock, stale detection | No instance locking |
| **Service Discovery** | Medium | Bonjour/mDNS + Tailnet DNS | No discovery |
| **Maintenance Timers** | Medium | Keepalive, cache cleanup, limits | No maintenance |
| **Boot System** | Low | BOOT.md execution on startup | No boot hooks |

---

## Key Architectural Takeaways

1. **Single Process Architecture**: OpenClaw runs everything in ONE Node.js process (gateway). This simplifies IPC but limits horizontal scaling. For Chelava (JVM-based), consider whether a monolithic or modular approach is better.

2. **In-Memory Event Bus**: No external message broker - all pub/sub is in-process `Set<listener>`. This is fast but doesn't survive restarts. Chelava could use this for a Java equivalent (or use a lightweight event bus like Guava EventBus or Spring Events).

3. **File-Based Persistence**: Cron jobs, sessions, config all stored as JSON files. No database. Atomic writes via temp+rename pattern. Chelava should decide on persistence strategy (file vs embedded DB like SQLite/H2).

4. **Lane-Based Concurrency**: Command execution uses named lanes (Main, Cron, Subagent, Nested) with configurable parallelism per lane. This prevents interference between workflows.

5. **Error Isolation Everywhere**: Every cleanup step, every listener callback, every maintenance task is wrapped in try-catch. One failure never cascades to take down the system.

6. **Composable Security**: Auth supports 4 modes (none, token, password, trusted-proxy) with rate limiting, constant-time comparison, and Tailscale integration. Log redaction is built into the logging layer.
