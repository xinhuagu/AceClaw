# AceClaw

Enterprise-grade, general-purpose autonomous AI agent built on Java 21.

AceClaw is the Java implementation of [OpenClaw](https://github.com/openclaw) — built for enterprise environments where security, self-improvement, and extensibility matter.

## AceClaw vs OpenClaw

| Capability | OpenClaw | AceClaw |
|------------|----------|---------|
| **Language** | TypeScript/Node.js | Java 21 (GraalVM native) |
| **Agent Loop** | External (Pi framework) | Self-implemented ReAct loop |
| **Architecture** | Single process | Daemon-first (persistent JVM + thin CLI) |
| **Concurrency** | Node.js async | Virtual threads (Project Loom) |
| **Memory** | None (no cross-session learning) | HMAC-signed auto-memory, 4-type hierarchy |
| **Security** | Breached within 48h of launch | Sealed permission model, HMAC integrity, gated tools |
| **LLM Providers** | Pi SDK (multi-provider) | 7 providers (Anthropic, OpenAI, Groq, Together, Mistral, Copilot, Ollama) |
| **Tools** | 50+ via community | 12 built-in + MCP extensibility |
| **Skills** | 700+ community (SKILL.md) | Planned: adaptive skills with effectiveness metrics |
| **Agent Teams** | Not supported | Planned: in-process virtual thread teammates |
| **Type Safety** | TypeScript | Sealed interfaces + exhaustive pattern matching |
| **Startup** | ~500ms (Node.js) | Sub-50ms (GraalVM native image) |

## Quick Start

```bash
# Build
./gradlew clean build && ./gradlew :aceclaw-cli:installDist

# Configure
export ANTHROPIC_API_KEY="sk-ant-api03-..."

# Run (auto-starts daemon)
./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli
```

Multi-provider support:
```bash
export ACECLAW_PROVIDER="openai"   # or groq, together, mistral, ollama
export OPENAI_API_KEY="sk-..."
```

## Architecture

```
CLI (Picocli + JLine3)
  │ JSON-RPC 2.0 over Unix Domain Socket
Daemon (persistent JVM)
  ├─ Request Router       → method dispatch
  ├─ Session Manager      → per-project sessions
  ├─ Streaming Agent Loop → ReAct loop (max 25 iterations)
  ├─ Permission Manager   → READ auto-approved, WRITE/EXECUTE gated
  ├─ Tool Registry        → 12 native tools + MCP
  ├─ Memory System        → HMAC-signed auto-memory
  ├─ Context Compactor    → 3-phase (prune → summarize → memory flush)
  └─ LLM Client Factory   → 7 providers, extended thinking, prompt caching
```

### Modules

| Module | Purpose |
|--------|---------|
| `aceclaw-core` | LLM abstractions, agent loop, tool interface, context compaction |
| `aceclaw-llm` | Anthropic + OpenAI-compatible LLM clients |
| `aceclaw-tools` | 12 built-in tools (file ops, bash, glob, grep, web, browser) |
| `aceclaw-security` | Sealed permission model (AutoAllow / PromptOnce / AlwaysAsk / Deny) |
| `aceclaw-memory` | Auto-memory with HMAC-SHA256 integrity |
| `aceclaw-mcp` | MCP client integration for external tools |
| `aceclaw-daemon` | Daemon process, UDS listener, streaming handler |
| `aceclaw-cli` | CLI entry point, REPL, daemon lifecycle |

## Roadmap

- [x] Daemon-first architecture, streaming ReAct loop, 12 tools
- [x] Extended thinking, retry, prompt caching, context compaction
- [x] Multi-provider (7 providers), HMAC-signed memory, MCP integration
- [ ] Self-learning: 4-type memory hierarchy, skill system, self-improvement loop
- [ ] Sub-agents: depth-1 delegation, custom agent definitions
- [ ] Agent teams: virtual thread teammates, shared tasks, inter-agent messaging
- [ ] Hook system: PreToolUse/PostToolUse lifecycle events

## Tech Stack

Java 21 (preview features) · Gradle 8.14 · Picocli 4.7.6 · JLine3 3.27.1 · Jackson 2.18.2 · GraalVM Native Image · JUnit 5

## License

TBD
