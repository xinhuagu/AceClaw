# AceClaw

An enterprise-grade, general-purpose autonomous AI agent built on Java 21 with daemon-first architecture.

AceClaw is the Java implementation of [OpenClaw](https://github.com/openclaw) — designed for enterprise environments where security, extensibility, and self-improvement matter. It turns your device into an intelligent autonomous companion that understands context, executes tools, learns from interactions, and streams responses in real time.

## Why AceClaw?

- **General-purpose**: Not limited to coding — handles any domain through extensible tools and skills
- **Self-learning**: Persistent memory with HMAC integrity, pattern detection, and autonomous skill generation
- **Multi-provider**: Supports Anthropic, OpenAI, Groq, Together, Mistral, GitHub Copilot, and Ollama
- **Security-first**: Sealed permission model, HMAC-signed memory, permission-gated tool execution
- **Instant startup**: GraalVM native image delivers sub-50ms startup
- **True parallelism**: Virtual threads (Project Loom) enable concurrent tool execution
- **Type safety**: Sealed interfaces, records, and exhaustive pattern matching
- **Daemon-first**: Persistent JVM process with Unix Domain Socket IPC, instant reconnection

## Quick Start

### Prerequisites

- Java 21+ (GraalVM recommended)
- API key for your preferred LLM provider

### Build

```bash
./gradlew clean build
./gradlew :aceclaw-cli:installDist
```

### Configure

Set your API key via environment variable:

```bash
# Anthropic (default)
export ANTHROPIC_API_KEY="sk-ant-api03-..."

# Or use OpenAI, Groq, Together, Mistral, Ollama...
export ACECLAW_PROVIDER="openai"
export OPENAI_API_KEY="sk-..."
```

Or create `~/.aceclaw/config.json`:

```json
{
    "apiKey": "sk-ant-api03-...",
    "provider": "anthropic",
    "model": "claude-sonnet-4-5-20250929"
}
```

Claude Pro/Max subscribers can use their OAuth token instead (auto-discovered from `~/.claude/.credentials`).

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
┌─────────▼────────────────────────────────────┐
│   Daemon (persistent JVM)                     │
│  ├─ Request Router      Method dispatch       │
│  ├─ Session Manager     Per-project sessions  │
│  ├─ Agent Handler       Streaming ReAct loop  │
│  ├─ Permission Manager  Tool access control   │
│  ├─ Tool Registry       Native + MCP tools    │
│  ├─ Memory System       HMAC-signed, 4-type   │
│  ├─ Context Compactor   3-phase hybrid        │
│  └─ LLM Client Factory  Multi-provider        │
└──────────────────────────────────────────────┘
```

### Modules

| Module | Purpose |
|--------|---------|
| `aceclaw-bom` | Dependency version management (Bill of Materials) |
| `aceclaw-core` | LLM abstractions, Agent loop, Tool interface, Context compaction |
| `aceclaw-llm` | Multi-provider LLM clients (Anthropic, OpenAI-compatible) |
| `aceclaw-tools` | Built-in tools: file ops, bash, glob, grep, web, browser |
| `aceclaw-security` | Permission system with sealed decision types |
| `aceclaw-memory` | Auto-memory with HMAC integrity verification |
| `aceclaw-mcp` | MCP (Model Context Protocol) client integration |
| `aceclaw-daemon` | Daemon process, UDS listener, session management, streaming handler |
| `aceclaw-cli` | CLI entry point, REPL, daemon lifecycle commands |

### Built-in Tools

| Tool | Permission | Description |
|------|-----------|-------------|
| `read_file` | Auto-approved | Read files with line numbers, offset/limit |
| `write_file` | Requires approval | Write new files |
| `edit_file` | Requires approval | Edit files (find/replace) |
| `bash` | Requires approval | Execute shell commands with timeout |
| `glob` | Auto-approved | Find files by glob pattern |
| `grep` | Auto-approved | Search file contents with regex |
| `list_directory` | Auto-approved | List directory contents |
| `web_fetch` | Auto-approved | Fetch and process web content |
| `web_search` | Auto-approved | Search the web |
| `browser` | Requires approval | Browser automation |
| `screen_capture` | Auto-approved | Capture screen content |
| `applescript` | Requires approval | macOS automation via AppleScript |

### Permission Model

- **READ** operations are auto-approved (safe, read-only)
- **WRITE** and **EXECUTE** operations prompt for user approval
- Users can approve once or "always" for the session
- All permission decisions flow through the bidirectional streaming protocol

## Key Features

### Extended Thinking
Leverages Claude's extended thinking for complex reasoning tasks. Configurable budget tokens with automatic capability detection per provider.

### Context Compaction
3-phase hybrid system to manage long conversations:
1. **Memory Flush**: Extract key facts to persistent memory before compacting
2. **Prune**: Replace old tool results with stubs, clear thinking blocks (free, no LLM call)
3. **Summarize**: LLM-generated summary when pruning isn't enough

### Multi-Provider Support
| Provider | Protocol | Extended Thinking | Prompt Caching |
|----------|----------|:-:|:-:|
| Anthropic | Native | Yes | Yes |
| OpenAI | OpenAI-compat | No | No |
| Groq | OpenAI-compat | No | No |
| Together | OpenAI-compat | No | No |
| Mistral | OpenAI-compat | No | No |
| GitHub Copilot | OpenAI-compat | No | No |
| Ollama | OpenAI-compat | No | No |

### HMAC-Signed Memory
Auto-memory entries are signed with HMAC-SHA256 to prevent tampering. Corrupted or unsigned entries are automatically detected and skipped on load.

### MCP Integration
Connect to external MCP (Model Context Protocol) servers for additional tools and capabilities. Configured per-project via `.aceclaw/config.json`.

## Configuration

| Source | Precedence | Example |
|--------|-----------|---------|
| `~/.aceclaw/config.json` | Lowest | Global user config |
| `{project}/.aceclaw/config.json` | Medium | Project overrides |
| Environment variables | Highest | `ANTHROPIC_API_KEY`, `ACECLAW_PROVIDER`, `ACECLAW_MODEL` |

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ANTHROPIC_API_KEY` | Anthropic API key | - |
| `OPENAI_API_KEY` | OpenAI/compatible API key (fallback) | - |
| `ACECLAW_PROVIDER` | LLM provider name | `anthropic` |
| `ACECLAW_MODEL` | Model identifier | `claude-sonnet-4-5-20250929` |
| `ACECLAW_BASE_URL` | Custom API endpoint | Provider default |
| `ACECLAW_LOG_LEVEL` | Log verbosity | `INFO` |

## Roadmap

### Completed
- [x] Daemon-first architecture with UDS IPC
- [x] Streaming ReAct agent loop (max 25 iterations)
- [x] 12 built-in tools with permission gating
- [x] Extended thinking with configurable budget
- [x] Retry with exponential backoff
- [x] Tool result truncation (30K char cap)
- [x] Prompt caching (Anthropic)
- [x] 3-phase context compaction
- [x] Multi-provider support (7 providers)
- [x] HMAC-signed auto-memory
- [x] MCP client integration

### In Progress
- [ ] Self-learning architecture (4-type memory, skill system, self-improvement loop)
- [ ] Sub-agent infrastructure (depth-1 delegation, custom agent definitions)
- [ ] Agent teams (virtual thread teammates, shared task list, inter-agent messaging)
- [ ] Hook system (PreToolUse/PostToolUse lifecycle events)
- [ ] System prompt enrichment (135+ dynamic fragments)

See `research/aceclaw-architecture-plan.md` for the detailed 6-phase implementation plan.

## Tech Stack

- **Language**: Java 21 with preview features (StructuredTaskScope, unnamed patterns)
- **Build**: Gradle 8.14 with Kotlin DSL
- **CLI**: Picocli 4.7.6 + JLine3 3.27.1
- **Serialization**: Jackson 2.18.2
- **Native**: GraalVM Native Image
- **Testing**: JUnit 5 + AssertJ

## License

TBD
