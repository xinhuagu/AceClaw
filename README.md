<h1 align="center">AceClaw</h1>

<p align="center">Autonomous AI agent for enterprise deployment, powered by Java 21</p>

<p align="center">
  <a href="https://github.com/xinhuagu/AceClaw/actions/workflows/ci.yml"><img src="https://github.com/xinhuagu/AceClaw/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk" alt="Java 21">
  <img src="https://img.shields.io/badge/GraalVM-Native_Image-blue?logo=oracle" alt="GraalVM">
  <img src="https://img.shields.io/badge/Gradle-8.14-02303A?logo=gradle&logoColor=white" alt="Gradle 8.14">
</p>

An enterprise-grade **autonomous AI agent** — pure Java 21, daemon-first architecture, zero network attack surface. Inspired by [Claude Code](https://docs.anthropic.com/en/docs/claude-code/overview) and [OpenClaw](https://github.com/openclaw), built from scratch with three key enhancements:

1. **Security** — UDS-only communication, sealed 4-level permissions, HMAC-signed memory
2. **Self-Learning** — Heuristic pattern detection, cross-session insight accumulation, strategy evolution
3. **Long-Term Memory** — 8-tier hierarchy, hybrid search, automated consolidation

## Security First

AceClaw defends across five dimensions:

- **Zero network surface** — Daemon communicates only via Unix Domain Socket. No HTTP, no REST, no WebSocket.
- **Sealed permissions** — 4-level hierarchy (`READ`/`WRITE`/`EXECUTE`/`DANGEROUS`) modeled as a sealed interface with compiler-enforced exhaustiveness. Sub-agents receive filtered tool registries to prevent privilege escalation.
- **Signed memory** — Every persisted memory entry is HMAC-SHA256 signed with constant-time verification. Tampered entries are rejected on load.
- **Content boundaries** — System prompt budget (150K char cap), tool result truncation (30K cap), and 8-tier priority ordering ensure human-authored content always outranks agent-generated memory.
- **Data protection** — POSIX 600 on signing keys, SHA-256 hashed workspace paths, size governance with automatic consolidation.

See [Security Details](#security-details) for the full breakdown and [Security Roadmap](#security-roadmap) for planned hardening.

## Self-Learning

AceClaw learns from its own behavior — no LLM calls required. Every tool execution, error recovery, and user correction is analyzed by heuristic detectors that produce type-safe insights.

- **Automatic pattern detection** — `ErrorDetector` matches tool failures to subsequent retries. `SessionEndExtractor` captures user corrections and preferences via regex-based passes. All detection is zero-cost (no API calls).
- **Cross-session accumulation** — Insights start at 0.4 confidence and gain +0.2 per recurrence. Only patterns reaching 0.7 confidence are persisted.
- **Strategy evolution** — Errors become `ErrorInsight`s, recurring sequences become `SuccessInsight`s, unresolved errors become anti-patterns. A closed feedback loop: detect → persist → recall → avoid.
- **Type-safe insight hierarchy** — `Insight` is a sealed interface (`ErrorInsight | SuccessInsight | PatternInsight`). The compiler enforces exhaustive handling.
- **Baseline evaluation** — Continuous-learning KPIs and collection workflow are documented in `docs/continuous-learning-plan.md` with report templates and sample output.

See [Self-Learning Pipeline](docs/self-learning.md) for the full architecture.

## Long-Term Memory

8-tier persistent memory hierarchy with HMAC-SHA256 signing, hybrid TF-IDF search, and 3-pass consolidation:

```
T1: Soul (identity)  →  T2: Managed Policy (enterprise)  →  T3: Workspace (ACECLAW.md)
T4: User Memory      →  T5: Local Memory (gitignored)     →  T6: Auto-Memory (JSONL+HMAC)
T7: Markdown Memory  →  T8: Daily Journal
```

- **HMAC-SHA256 integrity** — Every entry is signed. Mutable fields excluded from payload so reads don't invalidate signatures.
- **21 memory categories** — From `CODEBASE_INSIGHT` and `ERROR_RECOVERY` to `USER_FEEDBACK` and `ANTI_PATTERN`.
- **3-pass consolidation** — Dedup, similarity merge (>80% threshold), age prune (90 days, zero access). Runs automatically at session end.
- **Workspace isolation** — SHA-256 hashed paths under `~/.aceclaw/workspaces/`. No cross-project leakage.

See [Memory System Design](docs/memory-system-design.md) for the full architecture.

## Quick Start

```bash
# Build
./gradlew clean build && ./gradlew :aceclaw-cli:installDist

# Configure
export ANTHROPIC_API_KEY="sk-ant-api03-..."

# Run (auto-starts daemon)
./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli
```

Multi-provider support — see [Provider Configuration](docs/provider-configuration.md) for full setup:
```bash
# GitHub Copilot (use your subscription — no separate API key needed)
./dev.sh copilot

# OpenAI Codex OAuth (reuse ~/.codex/auth.json)
aceclaw models auth login --provider openai-codex
./dev.sh openai-codex
# Note: in openai-codex mode, AceClaw follows Codex backend rules
# (stream=true, store=false, no temperature/max_output_tokens).

# Ollama (local, offline)
./dev.sh ollama

# Or any OpenAI-compatible provider
export ACECLAW_PROVIDER="openai"   # or groq, together, mistral
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
  ├─ Task Planner         → complexity estimation, LLM plan generation, sequential execution
  ├─ Permission Manager   → sealed 4-level gate (READ/WRITE/EXECUTE/DANGEROUS)
  ├─ Tool Registry        → 12 native tools + MCP (filtered per sub-agent)
  ├─ Memory System        → 8-tier hierarchy, HMAC-signed, hybrid search
  ├─ Self-Learning        → ErrorDetector, ToolMetrics, SessionEndExtractor
  ├─ Context Compactor    → 3-phase (prune → summarize → memory flush)
  ├─ Scheduler            → persistent cron jobs, heartbeat runner
  ├─ Hook System          → BOOT.md startup, command hooks
  └─ LLM Client Factory   → 8 providers, extended thinking, prompt caching
```

### Modules

| Module | Purpose |
|--------|---------|
| `aceclaw-core` | LLM abstractions, agent loop, tool interface, context compaction, task planner |
| `aceclaw-llm` | Anthropic + OpenAI-compatible LLM clients |
| `aceclaw-tools` | 12 built-in tools (file ops, bash, glob, grep, web search, web fetch) |
| `aceclaw-security` | Sealed permission model (AutoAllow / PromptOnce / AlwaysAsk / Deny) |
| `aceclaw-memory` | [8-tier memory hierarchy](docs/memory-system-design.md), hybrid search, consolidation, HMAC integrity |
| `aceclaw-mcp` | MCP client integration for external tools |
| `aceclaw-daemon` | Daemon process, UDS listener, streaming handler, [self-learning detectors](docs/self-learning.md) |
| `aceclaw-cli` | CLI entry point, REPL, daemon lifecycle |

## Security Details

### Architecture Isolation

- **Zero network surface** — Daemon listens ONLY on Unix Domain Socket (`~/.aceclaw/aceclaw.sock`). No HTTP, no REST, no WebSocket.
- **Single entry point** — CLI → UDS → Daemon. There is no other way in.
- **Signal isolation** — Daemon runs in a separate process group, so CLI signals (Ctrl+C) don't kill the daemon or corrupt session state.
- **Session-scoped state** — Each session has isolated conversation history; no cross-session data leakage.

### Sealed Permission Model

```
READ        → auto-approved (file reads, glob, grep)
WRITE       → user approval required (file writes, edits)
EXECUTE     → user approval required (bash commands)
DANGEROUS   → always prompt, never remembered
```

- `PermissionDecision` is a **sealed interface** — `Approved | Denied | NeedsUserApproval`. The compiler enforces exhaustive handling.
- Sub-agents receive **filtered tool registries** — restricted tool sets prevent privilege escalation through nesting.

### Memory Integrity

- **HMAC-SHA256** per entry with constant-time verification — tampered entries are rejected.
- **POSIX 600** on the signing key file — only the owning user can read it.
- **SHA-256 hashed workspace paths** — workspace isolation without leaking directory names.
- **3-pass memory consolidation** — prevents memory pollution and unbounded growth.

### Content Boundaries

- **System prompt budget** — 150K total character cap + 20K per-tier cap. Tiers exceeding budget are truncated (70% head / 20% tail / 10% marker).
- **8-tier priority ordering** — Human-authored content always outranks agent-generated memory.
- **Tool result truncation** — Tool outputs capped at 30K characters to limit injection surface.
- **Managed Policy tier** — Reserved slot for enterprise-managed rules loaded from `~/.aceclaw/managed-policy.md`.

## Security Roadmap

| Phase | Feature | Purpose |
|-------|---------|---------|
| **S1** | Trust-level content sandboxing | Wrap memory tiers with trust metadata, differentiated preambles per trust level |
| **S1** | SOUL.md override protection | Prevent workspace-level SOUL.md from overriding global identity |
| **S1** | Memory write rate limiting | Cap agent-generated memory entries per session |
| **S2** | Memory write visibility | Surface auto-extracted memories to user for review before persistence |
| **S2** | Encryption at rest (AES-256) | Encrypt memory content on disk |
| **S3** | Provenance tracking | Record origin chain for each memory entry |
| **S3** | Memory quarantine | Isolate untrusted entries for review |
| **S3** | Audit trail | Structured log of agent actions and permission decisions |

## Roadmap

- [x] Daemon-first architecture, streaming ReAct loop, 12 tools
- [x] Extended thinking, retry, prompt caching, context compaction
- [x] Multi-provider (8 providers), HMAC-signed memory, MCP integration
- [x] 8-tier memory hierarchy, hybrid search, daily journal, workspace isolation
- [x] Sub-agents: depth-1 delegation, filtered tool registries, task lifecycle
- [x] Self-learning: insight hierarchy, error/pattern detection, self-improvement engine
- [x] Candidate pipeline: injected-candidate outcome writeback, clock-injected gates, stale cleanup, score decay
- [x] Hook system: BOOT.md startup execution, command hooks, persistent cron, heartbeat runner
- [x] Task planner: complexity estimation, LLM plan generation, sequential execution
- [ ] Security hardening: content sandboxing, trust levels, encryption at rest
- [ ] Agent teams: virtual thread teammates, shared tasks, inter-agent messaging

## Tech Stack

Java 21 (preview features) · Gradle 8.14 · Picocli 4.7.6 · JLine3 3.27.1 · Jackson 2.18.2 · GraalVM Native Image · JUnit 5

## License

TBD
