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

AceClaw is the Java implementation of [OpenClaw](https://github.com/openclaw) — built from the ground up with security as a foundational principle, not an afterthought. The daemon exposes zero network surface, permissions are modeled as sealed types with compiler-enforced exhaustiveness, and every memory entry is cryptographically signed.

## Security First

AceClaw defends across [five dimensions](#security-details):

- **Zero network surface** — Daemon communicates only via Unix Domain Socket. No HTTP, no REST, no WebSocket.
- **Sealed permissions** — 4-level hierarchy (`READ`/`WRITE`/`EXECUTE`/`DANGEROUS`) modeled as a sealed interface with compiler-enforced exhaustiveness. Sub-agents receive filtered tool registries to prevent privilege escalation.
- **Signed memory** — Every persisted memory entry is HMAC-SHA256 signed with constant-time verification. Tampered entries are rejected on load.
- **Content boundaries** — System prompt budget (150K char cap), tool result truncation (30K cap), and 8-tier priority ordering ensure human-authored content always outranks agent-generated memory.

See [Security Details](#security-details) for the full 5-dimension breakdown and [Security Roadmap](#security-roadmap) for planned hardening.

## Self-Learning

AceClaw learns from its own behavior — no LLM calls required. Every tool execution, error recovery, and user correction is analyzed by heuristic detectors that produce type-safe insights.

- **Automatic pattern detection** — `ErrorDetector` matches tool failures to subsequent retries. `SessionEndExtractor` captures user corrections, preferences, and feedback via regex-based passes. All detection is zero-cost (no API calls).
- **Cross-session accumulation** — Insights start at 0.4 confidence and gain +0.2 per session where the same pattern recurs. Only patterns reaching 0.7 confidence are persisted, preventing one-off flukes from polluting memory.
- **Strategy evolution** — Errors become `ErrorInsight`s, recurring sequences become `SuccessInsight`s, and unresolved errors become anti-patterns. A closed feedback loop: detect → persist → recall → avoid.
- **Type-safe insight hierarchy** — `Insight` is a sealed interface (`ErrorInsight | SuccessInsight | PatternInsight`). The compiler enforces exhaustive handling — adding a new insight type is a compile error until all consumers are updated.
- **Tool execution metrics** — `ToolMetricsCollector` tracks per-tool success rate, error count, and average latency using lock-free atomic counters. Session-scoped, zero synchronization overhead.

See [Self-Learning Pipeline](docs/self-learning.md) for the full architecture, detection strategies, and persistence rules.

## Long-Term Memory

8-tier persistent memory hierarchy with HMAC-SHA256 signing, hybrid TF-IDF search, and 3-pass consolidation:

```
T1: Soul (identity)  →  T2: Managed Policy (enterprise)  →  T3: Workspace (ACECLAW.md)
T4: User Memory      →  T5: Local Memory (gitignored)     →  T6: Auto-Memory (JSONL+HMAC)
T7: Markdown Memory  →  T8: Daily Journal
```

- **HMAC-SHA256 integrity** — Every memory entry is signed. Mutable fields (`accessCount`, `lastAccessedAt`) are excluded from the signable payload so reads don't invalidate signatures.
- **21 memory categories** — From `CODEBASE_INSIGHT` and `ERROR_RECOVERY` to `USER_FEEDBACK` and `ANTI_PATTERN`. Self-learning insights map directly to categories.
- **3-pass consolidation** — Dedup, similarity merge (>80% threshold), age prune (90 days, zero access). Runs automatically at session end.
- **Workspace isolation** — SHA-256 hashed paths under `~/.aceclaw/workspaces/`. No cross-project leakage.

See [Memory System Design](docs/memory-system-design.md) for the full architecture, tier hierarchy, and comparison with Claude Code and OpenClaw.

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
  ├─ Permission Manager   → sealed 4-level gate (READ/WRITE/EXECUTE/DANGEROUS)
  ├─ Tool Registry        → 12 native tools + MCP (filtered per sub-agent)
  ├─ Memory System        → 8-tier hierarchy, HMAC-signed, hybrid search
  ├─ Self-Learning        → ErrorDetector, ToolMetrics, SessionEndExtractor
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
| `aceclaw-daemon` | Daemon process, UDS listener, streaming handler, [self-learning detectors](docs/self-learning.md) |
| `aceclaw-cli` | CLI entry point, REPL, daemon lifecycle |

## Security Details

AceClaw defends across five dimensions:

### Architecture Isolation

- **Zero network surface** — Daemon listens ONLY on Unix Domain Socket (`~/.aceclaw/aceclaw.sock`). No HTTP, no REST, no WebSocket.
- **Single entry point** — CLI → UDS → Daemon. There is no other way in.
- **Signal isolation** — Daemon runs in a separate process group (`setsid` on Linux, `trap` on macOS), so CLI signals (Ctrl+C) don't kill the daemon or corrupt session state.
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
- Sub-agents receive **filtered tool registries** — restricted tool sets prevent privilege escalation through nesting.

### Memory Integrity

The `aceclaw-memory` module treats every persisted memory as a signed document:

- **HMAC-SHA256** per entry with constant-time verification — tampered entries are rejected, not silently loaded.
- **POSIX 600** on the signing key file — only the owning user can read it.
- **SHA-256 hashed workspace paths** — workspace isolation without leaking directory names.
- **Mutable fields excluded from HMAC** — `accessCount` and `lastAccessedAt` are intentionally outside the signable payload so reads don't invalidate signatures.
- **3-pass memory consolidation** (dedup → similarity merge → age prune) — prevents memory pollution and unbounded growth.

### Content Boundaries

Prompt injection is an industry-wide unsolved problem — no AI agent has a complete programmatic defense. AceClaw applies architectural mitigations to limit blast radius:

- **System prompt budget** — 150K total character cap + 20K per-tier cap. Tiers exceeding budget are truncated (70% head / 20% tail / 10% marker), lowest-priority tiers first. Prevents memory tiers from consuming the context window.
- **8-tier priority ordering** — Higher-priority tiers (Soul, Managed Policy) override lower tiers. Human-authored content always outranks agent-generated memory.
- **Tool result truncation** — Tool outputs capped at 30K characters (40/60 head/tail split) to limit injection surface from external commands.
- **Managed Policy tier** — Reserved slot (priority 90) for enterprise-managed rules that the agent must follow, loaded from `~/.aceclaw/managed-policy.md`.

> **Planned:** Trust-level content sandboxing (wrapping tiers with trust metadata), taint tracking for agent-generated memories, and memory quarantine for untrusted entries. See [Security Roadmap](#security-roadmap).

### Data Protection

HMAC-SHA256 provides **integrity** (tamper detection), not **confidentiality** — memory files are stored as plaintext JSONL on disk:

- **File-level permissions** — Signing key (`~/.aceclaw/memory/memory.key`) restricted to POSIX 600 (owner-only read).
- **Size governance** — MarkdownMemoryStore enforces 50KB per file, 500KB per workspace. Memory consolidation archives entries older than 90 days with zero access.
- **No encryption at rest** — Memory content is not encrypted. For sensitive environments, rely on OS-level disk encryption (FileVault, LUKS) until native encryption is implemented.

> **Planned:** AES-256 encryption at rest, per-entry key wrapping, key rotation without re-encryption. See [Security Roadmap](#security-roadmap).

## Security Roadmap

AceClaw's security posture is iterative. The following items are designed but not yet implemented:

| Phase | Feature | Purpose |
|-------|---------|---------|
| **S1** | Trust-level content sandboxing | Wrap memory tiers with trust metadata (`trusted` / `user` / `project` / `agent`), differentiated preambles per trust level |
| **S1** | SOUL.md override protection | Prevent workspace-level SOUL.md from overriding global identity without explicit opt-in |
| **S1** | Memory write rate limiting | Cap agent-generated memory entries per session to prevent memory flooding |
| **S2** | Memory write visibility | Surface auto-extracted memories to user for review before persistence |
| **S2** | Encryption at rest (AES-256) | Encrypt memory content on disk; HMAC alone provides integrity, not confidentiality |
| **S3** | Provenance tracking | Record origin chain (session, tool, user action) for each memory entry |
| **S3** | Memory quarantine | Isolate untrusted entries for review before promotion to active memory |
| **S3** | Audit trail | Structured log of agent actions, memory mutations, and permission decisions for compliance |

## Roadmap

- [x] Daemon-first architecture, streaming ReAct loop, 12 tools
- [x] Extended thinking, retry, prompt caching, context compaction
- [x] Multi-provider (7 providers), HMAC-signed memory, MCP integration
- [x] 8-tier memory hierarchy, hybrid search, daily journal, workspace isolation
- [x] Markdown memory (MEMORY.md), path-based rules, memory consolidation
- [x] Sub-agents: depth-1 delegation, filtered tool registries, task lifecycle
- [x] Self-learning: insight hierarchy, error detection, tool metrics (#13-#14)
- [ ] Self-learning: sequence/preference detectors, self-improvement engine (#15-#18)
- [ ] Security hardening: content sandboxing, trust levels, encryption at rest
- [ ] Agent teams: virtual thread teammates, shared tasks, inter-agent messaging
- [ ] Hook system: PreToolUse/PostToolUse lifecycle events

## Tech Stack

Java 21 (preview features) · Gradle 8.14 · Picocli 4.7.6 · JLine3 3.27.1 · Jackson 2.18.2 · GraalVM Native Image · JUnit 5

## License

TBD
