<p align="center">
  <img src="docs/img/img.png" alt="AceClaw Logo" width="200"/>
</p>

<h1 align="center">AceClaw</h1>

<p align="center">Security-first autonomous AI agent built on Java 21 — daemon isolation, sealed permissions, signed memory.</p>

<p align="center">
  <a href="https://github.com/xinhuagu/AceClaw/actions/workflows/ci.yml"><img src="https://github.com/xinhuagu/AceClaw/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk" alt="Java 21">
  <img src="https://img.shields.io/badge/GraalVM-Native_Image-blue?logo=oracle" alt="GraalVM">
  <img src="https://img.shields.io/badge/Gradle-8.14-02303A?logo=gradle&logoColor=white" alt="Gradle 8.14">
</p>

AceClaw is the Java implementation of [OpenClaw](https://github.com/openclaw) — built from the ground up with security as a foundational principle, not an afterthought. Every layer enforces isolation: the daemon exposes zero network surface, every tool invocation passes through a sealed permission gate, and every memory entry is cryptographically signed.

## Security First

AceClaw defends across three dimensions:

### Architecture Isolation

- **Zero network surface** — Daemon listens ONLY on Unix Domain Socket (`~/.aceclaw/aceclaw.sock`). No HTTP, no REST, no WebSocket.
- **Single entry point** — CLI → UDS → Daemon. There is no other way in.
- **Process group isolation** — Daemon runs in a separate process group (`setsid`), so CLI signals (Ctrl+C) don't kill the daemon or corrupt session state.
- **Session-scoped state** — Each session has isolated conversation history; no cross-session data leakage.

### Sealed Permission Model

The `aceclaw-security` module enforces a 4-level permission hierarchy with compiler-verified exhaustiveness:

```
READ        → auto-approved (file reads, glob, grep)
WRITE       → user approval required (file writes, edits)
EXECUTE     → user approval required (bash commands)
DANGEROUS   → always prompt, never remembered
```

- `PermissionDecision` is a **sealed interface** — `Approved | Denied | NeedsUserApproval`. The compiler enforces exhaustive handling; you cannot forget a case.
- Session-level approval tracking (remember-once, not forever).
- Sub-agents inherit parent permissions but receive **filtered tool registries** — no privilege escalation through nesting.

### Memory Integrity

The `aceclaw-memory` module treats every persisted memory as a signed document:

- **HMAC-SHA256** per entry with constant-time verification — tampered entries are rejected, not silently loaded.
- **POSIX 600** on the signing key file — only the owning user can read it.
- **SHA-256 hashed workspace paths** — workspace isolation without leaking directory names.
- **Mutable fields excluded from HMAC** — `accessCount` and `lastAccessedAt` are intentionally outside the signable payload so reads don't invalidate signatures.
- **3-pass memory consolidation** (dedup → similarity merge → age prune) — prevents memory pollution and unbounded growth.

## AceClaw vs OpenClaw

| Capability | OpenClaw | AceClaw |
|------------|----------|---------|
| **Language** | TypeScript/Node.js | Java 21 (GraalVM native) |
| **Agent Loop** | External (Pi framework) | Self-implemented ReAct loop |
| **Architecture** | Single process | Daemon-first (persistent JVM + thin CLI) |
| **Concurrency** | Node.js async | Virtual threads (Project Loom) |
| **Memory** | 5-tier (T0-T4), MEMORY.md + daily logs, vector+BM25 search | 8-tier hierarchy, HMAC-signed JSONL, TF-IDF hybrid search, memory consolidation |
| **Security** | SHA-256 dedup, no signing | 3-layer defense: UDS isolation, sealed permission gate, HMAC-signed memory |
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
  │ JSON-RPC 2.0 over UDS only ← zero network surface
Daemon (persistent JVM, separate process group)
  ├─ Request Router       → method dispatch
  ├─ Session Manager      → per-project sessions (isolated state)
  ├─ Streaming Agent Loop → ReAct loop (max 25 iterations)
  ├─ Permission Manager   → sealed 4-level gate (READ/WRITE/EXECUTE/DANGEROUS)
  ├─ Tool Registry        → 12 native tools + MCP (filtered per sub-agent)
  ├─ Memory System        → 8-tier hierarchy, HMAC-signed, hybrid search
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
| `aceclaw-memory` | [8-tier memory hierarchy](docs/memory-system-design.md), hybrid search, consolidation, path-based rules, HMAC integrity |
| `aceclaw-mcp` | MCP client integration for external tools |
| `aceclaw-daemon` | Daemon process, UDS listener, streaming handler |
| `aceclaw-cli` | CLI entry point, REPL, daemon lifecycle |

## Memory System

AceClaw implements an [8-tier persistent memory hierarchy](docs/memory-system-design.md) with cryptographic integrity, memory consolidation, and path-based conditional rules — combining the best of Claude Code and OpenClaw while adding enterprise-grade signing and self-maintenance.

### Memory Comparison

| Capability | Claude Code | OpenClaw | AceClaw |
|------------|-------------|----------|---------|
| **Cross-session memory** | MEMORY.md (flat file) | MEMORY.md + daily logs | HMAC-signed JSONL + daily journal |
| **Memory tiers** | 1 (auto-memory only) | 5 (T0 Working → T4 Foundational) | 8 (Soul → Policy → Workspace → User → Local → Auto → Markdown → Journal) |
| **Structured files** | CLAUDE.md (single) | 8+ files (AGENTS/SOUL/TOOLS/IDENTITY/USER/HEARTBEAT.md) | ACECLAW.md + SOUL.md + managed-policy.md |
| **Categories** | Unstructured text | File-based (identity, tools, user, etc.) | 21 typed categories per entry |
| **Search** | None (full injection) | Hybrid: 70% vector + 30% BM25 (SQLite) | Hybrid: TF-IDF + recency decay + frequency boost |
| **Integrity** | None | SHA-256 hash (dedup, no signing) | HMAC-SHA256 per entry, constant-time verification |
| **Key protection** | N/A | N/A | POSIX 600 on signing key |
| **Workspace isolation** | Per-project directory | Per-agent SQLite (known cross-contamination) | SHA-256 hashed workspace paths |
| **Activity log** | None | Session .jsonl + daily logs | Append-only daily journal (500-line cap) |
| **Context compaction** | Summarize-only | Silent pre-flush + auto-compaction | 3-phase (prune → summarize → memory flush) |
| **Memory in prompt** | Appended at end | Conditional injection + lazy loading | Tiered assembly with priority ordering |

### 8-Tier Hierarchy

```
Priority 100  Soul           ← SOUL.md (immutable core identity)
Priority  90  Managed Policy ← Organization policies (enterprise)
Priority  80  Workspace      ← Project ACECLAW.md instructions
Priority  70  User           ← Global ~/.aceclaw/ACECLAW.md
Priority  65  Local          ← ACECLAW.local.md (per-developer, gitignored)
Priority  60  Auto-Memory    ← Learned insights (21 categories, HMAC-signed)
Priority  55  Markdown       ← MEMORY.md + topic files (persistent notes)
Priority  50  Daily Journal  ← Append-only activity log (today + yesterday)
```

### 21 Memory Categories

Mistake · Pattern · Preference · Codebase Insight · Strategy · Workflow · Environment · Relationship · Terminology · Constraint · Decision · Tool Usage · Communication · Context · Correction · Bookmark · Session Summary · Error Recovery · Successful Strategy · Anti-Pattern · User Feedback

### Hybrid Search

Memories are ranked by a weighted combination, not just recency:

```
score = 0.50 × TF-IDF relevance
      + 0.35 × recency (7-day half-life exponential decay)
      + 0.15 × tag frequency boost (log(1 + matching tags))
```

## Roadmap

- [x] Daemon-first architecture, streaming ReAct loop, 12 tools
- [x] Extended thinking, retry, prompt caching, context compaction
- [x] Multi-provider (7 providers), HMAC-signed memory, MCP integration
- [x] 8-tier memory hierarchy, hybrid search, daily journal, workspace isolation
- [x] Markdown memory (MEMORY.md), path-based rules, memory consolidation
- [ ] Self-learning: skill system, self-improvement loop, summary learning
- [x] Sub-agents: depth-1 delegation, filtered tool registries, task lifecycle
- [ ] Agent teams: virtual thread teammates, shared tasks, inter-agent messaging
- [ ] Hook system: PreToolUse/PostToolUse lifecycle events

## Tech Stack

Java 21 (preview features) · Gradle 8.14 · Picocli 4.7.6 · JLine3 3.27.1 · Jackson 2.18.2 · GraalVM Native Image · JUnit 5

## License

TBD
