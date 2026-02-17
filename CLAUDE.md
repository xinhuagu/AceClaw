# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Full build (all 13 modules)
./gradlew clean build

# Build + install CLI distribution (includes startup scripts)
./gradlew :chelava-cli:installDist

# Run tests (all modules)
./gradlew test

# Run a single test class
./gradlew :chelava-daemon:test --tests "dev.chelava.daemon.DaemonIntegrationTest"

# Run a single test method
./gradlew :chelava-daemon:test --tests "dev.chelava.daemon.DaemonIntegrationTest.testHealthStatus"

# Run the CLI (auto-starts daemon)
./chelava-cli/build/install/chelava-cli/bin/chelava-cli

# Daemon management
./chelava-cli/build/install/chelava-cli/bin/chelava-cli daemon start   # foreground
./chelava-cli/build/install/chelava-cli/bin/chelava-cli daemon stop
./chelava-cli/build/install/chelava-cli/bin/chelava-cli daemon status
```

## Java Version & Preview Features

Java 21 with `--enable-preview` required everywhere (compile, test, runtime). This enables `StructuredTaskScope` for parallel tool execution and unnamed pattern variables. The root `build.gradle.kts` configures this globally. The CLI's `build.gradle.kts` also sets `applicationDefaultJvmArgs = listOf("--enable-preview")` for the generated startup scripts.

## Architecture Overview

Chelava is a **daemon-first AI coding agent**. The CLI is a thin client that connects to a persistent JVM daemon via Unix Domain Socket.

```
CLI (Picocli + JLine3)
  ↕ JSON-RPC 2.0 over UDS (~/.chelava/chelava.sock)
Daemon (persistent JVM)
  ├── RequestRouter → dispatches methods to handlers
  ├── StreamingAgentHandler → runs ReAct loop with permission checks
  ├── StreamingAgentLoop → LLM call → tool execution cycle (max 25 iterations)
  ├── PermissionManager → READ auto-approved, WRITE/EXECUTE need user approval
  ├── ToolRegistry → 6 tools (read_file, write_file, edit_file, bash, glob, grep)
  └── AnthropicClient → Claude API (supports both API key and OAuth token auth)
```

### Module Dependency Graph

```
chelava-bom          (version constraints, java-platform)
chelava-core         (LlmClient, Tool, AgentLoop, StreamingAgentLoop, ContentBlock, StreamEvent)
  ↑
  ├── chelava-llm    (AnthropicClient, AnthropicMapper, AnthropicStreamSession)
  ├── chelava-tools  (ReadFileTool, WriteFileTool, EditFileTool, BashExecTool, GlobSearchTool, GrepSearchTool)
  └── chelava-security (PermissionManager, PermissionDecision sealed interface, DefaultPermissionPolicy)
        ↑
chelava-daemon       (ChelavaDaemon, UdsListener, RequestRouter, ConnectionBridge, SessionManager, StreamingAgentHandler)
  ↑
chelava-cli          (ChelavaMain, DaemonClient, DaemonStarter, TerminalRepl)
```

Modules `chelava-sdk`, `chelava-infra`, `chelava-memory`, `chelava-mcp`, `chelava-server`, `chelava-test` exist as placeholders for future work.

### Streaming Protocol

The `agent.prompt` method uses a bidirectional streaming protocol over JSON-RPC 2.0:

1. Client sends `{jsonrpc, method: "agent.prompt", params: {sessionId, prompt}, id}`
2. Daemon streams notifications: `stream.text` (token deltas), `stream.tool_use` (tool invocations), `permission.request` (approval needed), `stream.error`
3. Client responds to permission requests with `permission.response` notifications
4. Daemon sends final JSON-RPC response with `{result: {response, stopReason, usage}, id}`

The `StreamContext` interface enables this bidirectional flow — handlers can `sendNotification()` and `readMessage()` during request processing.

### Key Sealed Type Hierarchies

- `ContentBlock`: `Text | ToolUse | ToolResult`
- `StreamEvent`: `MessageStart | ContentBlockStart | TextDelta | ToolUseDelta | ContentBlockStop | MessageDelta | StreamComplete | StreamError`
- `PermissionDecision`: `Approved | Denied | NeedsUserApproval`
- `DaemonLock.LockResult`: `Acquired | AlreadyRunning | StaleLock`
- `ConversationMessage`: `User | Assistant | System`

Use exhaustive pattern matching (`switch`) on these — the compiler enforces completeness.

### Authentication

`AnthropicClient` supports two auth modes:
- **API key** (`sk-ant-api03-*`): sent via `x-api-key` header
- **OAuth token** (`sk-ant-oat01-*`): sent via `Authorization: Bearer` header with required beta flags (`claude-code-20250219,oauth-2025-04-20`) and identity headers (`user-agent: claude-cli/2.1.2 (external, cli)`, `x-app: cli`). Auto-refreshes expired tokens using `https://console.anthropic.com/api/oauth/token`.

Config loaded from: `~/.chelava/config.json` → `{project}/.chelava/config.json` → env vars (`ANTHROPIC_API_KEY`, `CHELAVA_MODEL`, `CHELAVA_LOG_LEVEL`). OAuth refresh tokens auto-discovered from Claude CLI credentials (`~/.claude/.credentials`).

## Build Conventions

- All subprojects use `java-library` plugin (not `java`) — needed for `api()` dependency declarations
- BOM platform must be applied to `annotationProcessor` config too: `annotationProcessor(platform(project(":chelava-bom")))`
- Picocli requires `annotationProcessor("info.picocli:picocli-codegen")` in chelava-cli
- GraalVM Native Image configured in chelava-cli for single-binary distribution

## Testing

Integration tests in `chelava-daemon` use `MockLlmClient` (queue-based programmable mock) for full-stack E2E testing without real API calls. Tests cover the entire path: UDS socket → ConnectionBridge → RequestRouter → StreamingAgentHandler → AgentLoop → Tools + Permissions.

## Code Style

- Do not use Chinese in any code or comments
- Use Java records for immutable data types
- Use sealed interfaces with pattern matching for type hierarchies
- `Process.getInputStream()` not `Process.inputStream()` (Java 21 API)
