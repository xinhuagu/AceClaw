# Chelava

A high-performance AI coding agent built on Java 21 with daemon-first architecture.

Chelava turns your device into an intelligent coding companion — a persistent system service that understands your codebase, executes tools, and streams responses in real time.

## Why Chelava?

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
./gradlew :chelava-cli:installDist
```

### Configure

Set your API key via environment variable:

```bash
export ANTHROPIC_API_KEY="sk-ant-api03-..."
```

Or create `~/.chelava/config.json`:

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
./chelava-cli/build/install/chelava-cli/bin/chelava-cli

# Daemon management
chelava daemon start    # start in foreground
chelava daemon stop     # graceful shutdown
chelava daemon status   # health check
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
| `chelava-bom` | Dependency version management (Bill of Materials) |
| `chelava-core` | LLM abstractions, Agent loop, Tool interface |
| `chelava-llm` | Anthropic Claude API client (API key + OAuth) |
| `chelava-tools` | Built-in tools: read_file, write_file, edit_file, bash, glob, grep |
| `chelava-security` | Permission system with sealed decision types |
| `chelava-daemon` | Daemon process, UDS listener, session management, streaming handler |
| `chelava-cli` | CLI entry point, REPL, daemon lifecycle commands |

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
| `~/.chelava/config.json` | Lowest | Global user config |
| `{project}/.chelava/config.json` | Medium | Project overrides |
| Environment variables | Highest | `ANTHROPIC_API_KEY`, `CHELAVA_MODEL`, `CHELAVA_LOG_LEVEL` |

## Tech Stack

- **Language**: Java 21 with preview features (StructuredTaskScope, unnamed patterns)
- **Build**: Gradle 8.14 with Kotlin DSL
- **CLI**: Picocli 4.7.6 + JLine3 3.27.1
- **Serialization**: Jackson 2.18.2
- **Native**: GraalVM Native Image
- **Testing**: JUnit 5 + AssertJ

## License

TBD
