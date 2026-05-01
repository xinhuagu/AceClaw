# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# One-line install (downloads pre-built release, no build tools needed)
curl -fsSL https://raw.githubusercontent.com/xinhuagu/AceClaw/main/install.sh | sh

# Update to latest release
aceclaw-update

# Full build (all 13 modules)
./gradlew clean build

# Build + install CLI distribution (includes startup scripts)
./gradlew :aceclaw-cli:installDist

# Run tests (all modules)
./gradlew test

# Run a single test class
./gradlew :aceclaw-daemon:test --tests "dev.aceclaw.daemon.DaemonIntegrationTest"

# Run a single test method
./gradlew :aceclaw-daemon:test --tests "dev.aceclaw.daemon.DaemonIntegrationTest.testHealthStatus"

# Run the CLI (auto-starts daemon)
./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli

# Development: rebuild + restart daemon + auto-benchmark on feature branches
./dev.sh [--check | --baseline | --auto | --no-bench] [provider]

# Quick restart: rebuild + restart daemon, no benchmarks ever
./restart.sh [provider]

# Multi-session: open another TUI window (non-destructive, never restarts daemon)
./tui.sh [provider]

# Daemon management
./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli daemon start   # foreground
./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli daemon stop
./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli daemon status
```

## Java Version & Preview Features

Java 21 with `--enable-preview` required everywhere (compile, test, runtime). This enables `StructuredTaskScope` for parallel tool execution and unnamed pattern variables. The root `build.gradle.kts` configures this globally. The CLI's `build.gradle.kts` also sets `applicationDefaultJvmArgs = listOf("--enable-preview")` for the generated startup scripts.

## Architecture Overview

AceClaw is a **daemon-first AI coding agent**. The CLI is a thin client that connects to a persistent JVM daemon via Unix Domain Socket. The optional React dashboard connects via a loopback-only WebSocket bridge for runtime visualization. Multiple CLI/TUI windows can connect to one daemon simultaneously, each with its own independent session. One active TUI per workspace is enforced by `WorkspaceAttachmentRegistry`. See `docs/multi-session.md` and `docs/visual-harness.md` for details.

```
CLI (Picocli + JLine3)            Dashboard (React + Vite)
  ↕ JSON-RPC 2.0 over UDS           ↕ JSON-RPC notifications over WS
  (~/.aceclaw/aceclaw.sock)         (127.0.0.1:3141, opt-in, allowed-origins)
                  \                /
                   v              v
Daemon (persistent JVM)
  ├── RequestRouter → dispatches methods to handlers
  ├── WebSocketBridge → fans daemon events out to connected dashboards (off by default)
  ├── EventMultiplexer → tees per-request notifications to both CLI sink and WS bridge
  ├── WorkspaceAttachmentRegistry → one live TUI per workspace
  ├── StreamingAgentHandler → runs ReAct loop with permission checks + task planner
  ├── StreamingAgentLoop → LLM call → tool execution cycle (max 25 iterations)
  ├── Task Planner → complexity estimation → LLM plan generation → sequential execution
  ├── PermissionManager → READ auto-approved, WRITE/EXECUTE need user approval
  │     (per-session scoping — CLI and dashboard race, first response wins)
  ├── ToolRegistry → 6 tools (read_file, write_file, edit_file, bash, glob, grep)
  ├── CronScheduler → triggers cron jobs as cron-as-session runs (each fire = a turn,
  │     broadcast over WS so dashboard can drill into them)
  ├── SelfImprovementEngine → post-turn learning (ErrorDetector + PatternDetector)
  └── AnthropicClient → Claude API (supports both API key and OAuth token auth)
```

### Module Dependency Graph

```
aceclaw-bom          (version constraints, java-platform)
aceclaw-core         (LlmClient, Tool, AgentLoop, StreamingAgentLoop, ContentBlock, StreamEvent, TaskPlanner)
  ↑
  ├── aceclaw-llm    (AnthropicClient, AnthropicMapper, AnthropicStreamSession)
  ├── aceclaw-tools  (ReadFileTool, WriteFileTool, EditFileTool, BashExecTool, GlobSearchTool, GrepSearchTool)
  └── aceclaw-security (PermissionManager, PermissionDecision sealed interface, DefaultPermissionPolicy)
        ↑
aceclaw-daemon       (AceClawDaemon, UdsListener, RequestRouter, ConnectionBridge, SessionManager, StreamingAgentHandler)
  ↑
aceclaw-cli          (AceClawMain, DaemonClient, DaemonStarter, TerminalRepl)
```

Modules `aceclaw-sdk`, `aceclaw-infra` (event hierarchy), `aceclaw-memory`, `aceclaw-mcp`, `aceclaw-server`, `aceclaw-test` exist or serve as supporting/placeholder modules.

### Streaming Protocol

The `agent.prompt` method uses a bidirectional streaming protocol over JSON-RPC 2.0:

1. Client sends `{jsonrpc, method: "agent.prompt", params: {sessionId, prompt}, id}`
2. Daemon streams notifications: `stream.text` (token deltas), `stream.tool_use` (tool invocations), `permission.request` (approval needed), `stream.error`, `stream.plan_created`, `stream.plan_step_started`, `stream.plan_step_completed`, `stream.plan_completed`
3. Client responds to permission requests with `permission.response` notifications
4. Daemon sends final JSON-RPC response with `{result: {response, stopReason, usage}, id}`

The `StreamContext` interface enables this bidirectional flow — handlers can `sendNotification()` and `readMessage()` during request processing.

### Key Sealed Type Hierarchies

- `ContentBlock`: `Text | ToolUse | ToolResult`
- `StreamEvent`: `MessageStart | ContentBlockStart | TextDelta | ToolUseDelta | ContentBlockStop | MessageDelta | StreamComplete | StreamError`
- `PermissionDecision`: `Approved | Denied | NeedsUserApproval`
- `DaemonLock.LockResult`: `Acquired | AlreadyRunning | StaleLock`
- `ConversationMessage`: `User | Assistant | System`
- `PlanStatus`: `Draft | Executing | Completed | Failed`
- `AceClawEvent`: `AgentEvent | ToolEvent | SessionEvent | HealthEvent | SystemEvent | SchedulerEvent | PlanEvent`

Use exhaustive pattern matching (`switch`) on these — the compiler enforces completeness.

### Authentication

`AnthropicClient` supports two auth modes:
- **API key** (`sk-ant-api03-*`): sent via `x-api-key` header
- **OAuth token** (`sk-ant-oat01-*`): sent via `Authorization: Bearer` header with required beta flags (`claude-code-20250219,oauth-2025-04-20`) and identity headers (`user-agent: claude-cli/2.1.2 (external, cli)`, `x-app: cli`). Auto-refreshes expired tokens using `https://console.anthropic.com/api/oauth/token`.

Config loaded from: `~/.aceclaw/config.json` → `{project}/.aceclaw/config.json` → env vars (`ANTHROPIC_API_KEY`, `ACECLAW_MODEL`, `ACECLAW_LOG_LEVEL`). OAuth refresh tokens auto-discovered from Claude CLI credentials (`~/.claude/.credentials`).

## Build Conventions

- All subprojects use `java-library` plugin (not `java`) — needed for `api()` dependency declarations
- BOM platform must be applied to `annotationProcessor` config too: `annotationProcessor(platform(project(":aceclaw-bom")))`
- Picocli requires `annotationProcessor("info.picocli:picocli-codegen")` in aceclaw-cli
- GraalVM Native Image configured in aceclaw-cli for single-binary distribution

## Testing

Integration tests in `aceclaw-daemon` use `MockLlmClient` (queue-based programmable mock) for full-stack E2E testing without real API calls. Tests cover the entire path: UDS socket → ConnectionBridge → RequestRouter → StreamingAgentHandler → AgentLoop → Tools + Permissions.

## Code Style

- Do not use Chinese in any code or comments
- Use Java records for immutable data types
- Use sealed interfaces with pattern matching for type hierarchies
- `Process.getInputStream()` not `Process.inputStream()` (Java 21 API)

### Defensive Coding (Clean Code)

Every public API boundary must be null-safe and bounds-safe:

- **Record constructors**: Always null-guard `List` fields — `signals = signals != null ? List.copyOf(signals) : List.of()`
- **Method parameters**: Use `Objects.requireNonNull(param, "param")` on parameters used in `.equals()` or passed to downstream calls
- **String truncation**: Always check length before `substring()` — `s.length() > max ? s.substring(0, max) + "..." : s`. Never assume the string is long enough
- **Nullable return values**: When calling methods that may return null (e.g. `response.text()`, `Jackson readTree("")`), check for null before using. Throw a meaningful exception instead of letting NPE propagate
- **Exception wrapping**: When a method declares a specific exception type (e.g. `throws LlmException`), wrap unexpected exceptions (e.g. `IllegalArgumentException`) into the declared type with `new LlmException("message", cause)` — don't let undeclared exceptions leak
- **Collection parameters**: If a method receives a `List` that it calls `.stream()` on, guard against null — `var safe = list != null ? list : List.of()`
