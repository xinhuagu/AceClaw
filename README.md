<h1 align="center">AceClaw</h1>

<p align="center">Self-learning agent harness for long-running coding work</p>

<p align="center">
  <a href="https://github.com/xinhuagu/AceClaw/actions/workflows/ci.yml"><img src="https://github.com/xinhuagu/AceClaw/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk" alt="Java 21">
  <img src="https://img.shields.io/badge/GraalVM-Native_Image-blue?logo=oracle" alt="GraalVM">
  <img src="https://img.shields.io/badge/Gradle-8.14-02303A?logo=gradle&logoColor=white" alt="Gradle 8.14">
</p>

AceClaw is a persistent JVM daemon built for workflows that run for hours, not seconds. It is an **agent harness**: the layer that turns LLM calls into a long-running loop that can reason, act, observe, recover, and remember.

What makes AceClaw different is that it does not stop at memory. It treats runtime behavior as learning data. Tool failures, recoveries, repeated workflows, validation results, and human reviews are all turned into durable signals that can change future behavior.

Inspired by [Claude Code](https://docs.anthropic.com/en/docs/claude-code/overview) and [OpenClaw](https://github.com/openclaw), AceClaw is built around three ideas:

1. **Self-learning by default** — The harness learns from what the agent actually does, not only from what it writes down.
2. **Long-running execution** — A persistent daemon keeps context, state, and maintenance loops alive across sessions.
3. **Safe local control** — UDS-only communication, sealed permissions, signed memory, and strict workspace isolation keep the system inspectable.

## Why AceClaw

Most agent projects are strongest at one of these two things:

- **Context management** — storing notes, loading rules, and keeping the prompt usable.
- **Behavior improvement** — turning runtime experience into better future behavior.

AceClaw is built around the second problem.

OpenClaw is useful here as a contrast. OpenClaw is strong at explicit memory, gateway orchestration, and making context operational. AceClaw starts one layer later. It asks:

> What in the agent's behavior should become reusable knowledge?

That leads to a different default architecture.

![AceClaw daemon architecture](docs/img/aceclaw_daemon_architecture.png)

Original diagrams used for the current docs live in [`docs/img/`](docs/img).

## At A Glance

| Question | OpenClaw-style default | AceClaw default |
|----------|-------------------------|-----------------|
| What is the main unit of reuse? | Retrieved context | Reused behavior |
| What gets stored first? | Notes and explicit memory | Explanations, validations, trends, and governed signals |
| What changes the next run? | Better prompt context | Better prompt context plus runtime adaptation |
| What is the hard problem? | Context management | Learning effectiveness |
| What is the loop centered on? | Full execution pipeline and context handling | ReAct behavior as a learning substrate |
| Operator role | Inspect what goes in | Inspect, validate, suppress, and pin learned signals |

## Security First

AceClaw defends across five dimensions:

- **Zero network surface** — Daemon communicates only via Unix Domain Socket. No HTTP, no REST, no WebSocket.
- **Sealed permissions** — 4-level hierarchy (`READ`/`WRITE`/`EXECUTE`/`DANGEROUS`) modeled as a sealed interface with compiler-enforced exhaustiveness. Sub-agents receive filtered tool registries to prevent privilege escalation.
- **Signed memory** — Every persisted memory entry is HMAC-SHA256 signed with constant-time verification. Tampered entries are rejected on load.
- **Content boundaries** — System prompt budget (150K char cap), tool result truncation (30K cap), and 8-tier priority ordering ensure human-authored content always outranks agent-generated memory.
- **Data protection** — POSIX 600 on signing keys, SHA-256 hashed workspace paths, size governance with automatic consolidation.

See [Security Details](#security-details) for the full breakdown and [Security Roadmap](#security-roadmap) for planned hardening.

## Self-Learning

AceClaw now has a full self-learning loop in `main`.

It learns in three layers:

1. **Turn-time learning** — detect failures, recoveries, repeated tool sequences, and skill outcomes while the agent is working.
2. **Session-close learning** — write a retrospective summary, append historical snapshots, and persist lightweight signals immediately.
3. **Deferred maintenance** — consolidate memory, mine cross-session patterns, detect trends, rebuild stale indexes, and recover interrupted maintenance work in the background.

Then it adds a governance layer on top:

- **Explainability** — every adaptive action can be traced to a trigger and evidence.
- **Validation semantics** — learned behavior is tagged as provisional, useful, pass, hold, reject, or rollback.
- **Observability** — `/learning` shows maintenance runs, recent actions, validations, and reviews.
- **Noise control** — low-value or stale signals can be pruned, merged, decayed, or suppressed.
- **Runtime skill governance** — repeated workflows can become session-scoped runtime skills, but only with promotion, suppression, expiration, and durable-draft rules.
- **Human review** — operators can list reviewable signals and mark them `useful`, `pin`, `suppress`, `low_value`, or `incorrect`.

The result is not just "memory." It is a system that tries to turn runtime experience into reusable knowledge.

See [Self-Learning Pipeline](docs/self-learning.md) for the full architecture and operator workflow.

## Long-Term Memory

8-tier persistent memory hierarchy with HMAC-SHA256 signing, hybrid TF-IDF search, and 3-pass consolidation:

```
T1: Soul (identity)  →  T2: Managed Policy (enterprise)  →  T3: Workspace (ACECLAW.md)
T4: User Memory      →  T5: Local Memory (gitignored)     →  T6: Auto-Memory (JSONL+HMAC)
T7: Markdown Memory  →  T8: Daily Journal
```

- **HMAC-SHA256 integrity** — Every entry is signed. Mutable fields excluded from payload so reads don't invalidate signatures.
- **21 memory categories** — From `CODEBASE_INSIGHT` and `ERROR_RECOVERY` to `USER_FEEDBACK` and `ANTI_PATTERN`.
- **3-pass consolidation** — Dedup, similarity merge (>80% threshold), age prune (90 days, zero access). Triggered by the learning maintenance scheduler after session-close extraction and indexing.
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
  ├─ Self-Learning        → detectors, retrospectives, historical index, trends, validation, runtime skills, human review
  ├─ Context Compactor    → 3-phase (prune → summarize → memory flush)
  ├─ Scheduler            → persistent cron jobs, heartbeat runner, learning maintenance
  ├─ Hook System          → BOOT.md startup, command hooks
  └─ LLM Client Factory   → 8 providers, extended thinking, prompt caching
```

### Self-Learning Flow

```
Turn execution
  → detectors + metrics
  → session retrospective + historical snapshot
  → deferred maintenance
  → explanations + validations
  → runtime skills / refinement / candidate transitions
  → human review and suppression
```

For the source diagrams behind the current architecture work, see:

- [`docs/img/aceclaw_daemon_architecture.png`](docs/img/aceclaw_daemon_architecture.png)
- [`docs/img/openclaw_gateway_architecture.png`](docs/img/openclaw_gateway_architecture.png)
- [`docs/img/context_compaction_openclaw_vs_aceclaw.png`](docs/img/context_compaction_openclaw_vs_aceclaw.png)

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

## Operator Commands

The CLI now exposes a small operator surface for learning:

```text
/learning
/learning signals
/learning reviews
/learning review <action> <targetType> <targetId> [note]
```

Use it to inspect what the system recently learned, review adaptive signals, and suppress or pin behavior that should not be treated as reusable knowledge.

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
- **3-pass memory consolidation** — prevents memory pollution and unbounded growth; now runs via deferred learning maintenance rather than inline session teardown.

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
- [x] Historical learning: session retrospectives, historical index, cross-session pattern mining, trend detection
- [x] Adaptive skills: metrics, skill memory feedback, refinement, rollback
- [x] Self-learning phase 2: explainability, validation semantics, observability, noise control, runtime skill governance, recovery, human review
- [x] Candidate pipeline: injected-candidate outcome writeback, clock-injected gates, stale cleanup, score decay
- [x] Hook system: BOOT.md startup execution, command hooks, persistent cron, heartbeat runner
- [x] Task planner: complexity estimation, LLM plan generation, sequential execution
- [ ] Security hardening: content sandboxing, trust levels, encryption at rest
- [ ] Agent teams: virtual thread teammates, shared tasks, inter-agent messaging

## Tech Stack

Java 21 (preview features) · Gradle 8.14 · Picocli 4.7.6 · JLine3 3.27.1 · Jackson 2.18.2 · GraalVM Native Image · JUnit 5

## License

TBD
