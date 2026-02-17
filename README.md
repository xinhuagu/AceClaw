# AceClaw

A high-performance AI coding agent built on Java 21 with daemon-first architecture.

AceClaw turns your device into an intelligent coding companion — a persistent system service that understands your codebase, executes tools, and streams responses in real time.

## Why AceClaw?

- **Instant startup**: GraalVM native image delivers sub-50ms startup
- **True parallelism**: Virtual threads (Project Loom) enable concurrent tool execution
- **Type safety**: Sealed interfaces, records, and exhaustive pattern matching
- **Single binary**: No runtime dependencies — one native binary to deploy
- **Daemon-first**: Persistent JVM process with Unix Domain Socket IPC, instant reconnection

## Quick Start

### Prerequisites

- Java 21+ (GraalVM recommended)
- Anthropic API key or Claude Pro/Max subscription

### Build

```bash
./gradlew clean build
./gradlew :aceclaw-cli:installDist
```

### Configure

Set your API key via environment variable:

```bash
export ANTHROPIC_API_KEY="sk-ant-api03-..."
```

Or create `~/.aceclaw/config.json`:

```json
{
    "apiKey": "sk-ant-api03-...",
    "model": "claude-sonnet-4-5-20250929"
}
```

Claude Pro/Max subscribers can use their OAuth token instead (obtained via `claude setup-token`).

### Run

```bash
# Start and enter interactive REPL (auto-starts daemon)
./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli

# Daemon management
aceclaw daemon start    # start in foreground
aceclaw daemon stop     # graceful shutdown
aceclaw daemon status   # health check
```

## Architecture

```
┌──────────────────────┐
│   CLI (thin client)  │  Picocli + JLine3 REPL
└─────────┬────────────┘
          │ JSON-RPC 2.0 over Unix Domain Socket
┌─────────▼────────────┐
│   Daemon (persistent) │
│  ├─ Request Router    │  Method dispatch
│  ├─ Session Manager   │  Per-project sessions
│  ├─ Agent Handler     │  Streaming ReAct loop
│  ├─ Permission Mgr    │  Tool access control
│  ├─ Tool Registry     │  6 built-in tools
│  └─ LLM Client       │  Anthropic Claude API
└──────────────────────┘
```

### Modules

| Module | Purpose |
|--------|---------|
| `aceclaw-bom` | Dependency version management (Bill of Materials) |
| `aceclaw-core` | LLM abstractions, Agent loop, Tool interface |
| `aceclaw-llm` | Anthropic Claude API client (API key + OAuth) |
| `aceclaw-tools` | Built-in tools: read_file, write_file, edit_file, bash, glob, grep |
| `aceclaw-security` | Permission system with sealed decision types |
| `aceclaw-daemon` | Daemon process, UDS listener, session management, streaming handler |
| `aceclaw-cli` | CLI entry point, REPL, daemon lifecycle commands |

### Built-in Tools

| Tool | Permission | Description |
|------|-----------|-------------|
| `read_file` | Auto-approved | Read files with line numbers, offset/limit |
| `glob` | Auto-approved | Find files by glob pattern |
| `grep` | Auto-approved | Search file contents with regex |
| `write_file` | Requires approval | Write new files |
| `edit_file` | Requires approval | Edit files (find/replace) |
| `bash` | Requires approval | Execute shell commands |

### Permission Model

- **READ** operations are auto-approved (safe, read-only)
- **WRITE** and **EXECUTE** operations prompt for user approval
- Users can approve once or "always" for the session
- All permission decisions flow through the bidirectional streaming protocol

## Configuration

| Source | Precedence | Example |
|--------|-----------|---------|
| `~/.aceclaw/config.json` | Lowest | Global user config |
| `{project}/.aceclaw/config.json` | Medium | Project overrides |
| Environment variables | Highest | `ANTHROPIC_API_KEY`, `ACECLAW_MODEL`, `ACECLAW_LOG_LEVEL` |

## Tech Stack

- **Language**: Java 21 with preview features (StructuredTaskScope, unnamed patterns)
- **Build**: Gradle 8.14 with Kotlin DSL
- **CLI**: Picocli 4.7.6 + JLine3 3.27.1
- **Serialization**: Jackson 2.18.2
- **Native**: GraalVM Native Image
- **Testing**: JUnit 5 + AssertJ

## License

TBD
