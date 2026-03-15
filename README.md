<h1 align="center">AceClaw</h1>

<p align="center">A self-learning agent harness for long-running work</p>

<p align="center">
  <a href="https://github.com/xinhuagu/AceClaw/actions/workflows/ci.yml"><img src="https://github.com/xinhuagu/AceClaw/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk" alt="Java 21">
  <img src="https://img.shields.io/badge/GraalVM-Native_Image-blue?logo=oracle" alt="GraalVM">
  <img src="https://img.shields.io/badge/Gradle-8.14-02303A?logo=gradle&logoColor=white" alt="Gradle 8.14">
</p>

> **AceClaw exists because long-running tasks demand learning.**
>
> When an agent runs for minutes or hours, context is not enough. It must absorb experience while it works, reuse what succeeds, and govern what it learns so it does not become noisy or unsafe.
> The goal is to make an agent behave more like an experienced engineering system over time.

An **agent harness** is the orchestration layer that turns LLMs into persistent, self-correcting workers — the loop that reasons, acts, observes, recovers, and remembers. Most harnesses treat each session as a blank slate. **AceClaw doesn't.** It is built for long-running execution, where repeated failures, recoveries, tool sequences, and user corrections must become reusable knowledge instead of disappearing at the end of the session.

<p align="center">
  <img src="docs/img/aceclaw_daemon_architecture.drawio.png" alt="AceClaw Self-Learning Daemon Architecture" width="600">
</p>

AceClaw is a persistent JVM daemon built for workflows that run for hours, not seconds. Pure Java 21, zero network attack surface, built from scratch around one idea:

**Memory helps an agent remember. Self-learning helps an agent improve.**

That is the spirit of AceClaw, and it drives four key differentiators:

1. **Plan → Execute → Replan** — Most agent harnesses use a flat ReAct loop (think → act → observe, one step at a time). AceClaw generates an **explicit task plan** before execution, runs it step by step with per-step budgets, and **adaptively replans** when steps fail. Plans are streamed to the user in real time, checkpointed to disk for crash recovery, and resumable across sessions. This gives AceClaw a structural advantage in long-running tasks — the agent has a visible roadmap instead of hoping the model stays on track turn by turn.
2. **Self-Learning** — Zero-cost heuristic detectors, session retrospectives, historical indexing, cross-session pattern mining, and trend detection turn agent behavior into durable learning signals. The agent evolves its own strategies without extra LLM calls in the hot path.
3. **Security** — UDS-only communication, sealed 4-level permissions, HMAC-signed memory
4. **Long-Term Memory** — 8-tier hierarchy, hybrid search, automated consolidation

**What makes this architecture different:**

- **Daemon-first, not CLI-first** — The JVM daemon persists across sessions. No cold start, no re-parsing config, no re-loading memory. The CLI is a thin JSON-RPC client over Unix Domain Socket.
- **Behavior-centric, not memory-centric** — Most agent memory systems store facts. AceClaw observes *behavior* — error-recovery sequences, tool usage patterns, user corrections — and distills them into typed, confidence-scored insights. The agent doesn't just remember what happened; it learns *how it should act differently next time*.
- **Closed feedback loop** — Detectors emit typed insights → insights accumulate confidence across sessions → high-confidence insights get persisted → persisted memory is injected back into the next run. Repeated corrections auto-promote from auto-memory (Tier 6) to workspace rules (Tier 3).
- **Everything is sealed** — `Insight` (5 permits), `PermissionDecision` (3 permits), `MemoryTier` (8 permits), `StreamEvent`, `ContentBlock` — the compiler enforces exhaustive handling everywhere. Adding a new variant is a compile error until all switches are updated.

## Plan → Execute → Replan
<sub>Supported by research: <a href="https://doi.org/10.1145/3706598.3714875">Plan-Then-Execute (CHI 2025)</a></sub>

Most AI coding agents ([Claude Code](https://docs.anthropic.com/en/docs/claude-code/overview), [OpenClaw](https://github.com/openclaw), [Codex CLI](https://github.com/openai/codex)) rely on a flat **ReAct loop** — the model reasons and acts one step at a time. While effective for short tasks, this approach offers no explicit plan visibility, no structured failure recovery, and no crash-safe checkpointing for long-running work.

AceClaw takes a fundamentally different approach: it **layers an explicit planning pipeline on top of ReAct**. Each individual step is still executed by the same ReAct loop (reason → act → observe), which remains the best mechanism for single-step tool use. The difference is that AceClaw wraps those steps in a higher-order plan that provides direction, budget control, and structured recovery — something a flat ReAct loop cannot do on its own.

```
Task → Complexity Estimator → Plan Generation (LLM) → Sequential Execution → Adaptive Replan
                                     │                        │                      │
                                     ▼                        ▼                      ▼
                              Structured JSON plan     Per-step budgets        On failure: LLM
                              streamed to user         + wall-clock limits     regenerates remaining
                                                                               steps or escalates
```

| Component | What it does |
|-----------|-------------|
| `ComplexityEstimator` | Scores task complexity; only triggers planning above a configurable threshold |
| `LLMTaskPlanner` | Generates a structured JSON plan with ordered, named steps |
| `SequentialPlanExecutor` | Executes steps one by one with per-step and total wall-clock budgets |
| `AdaptiveReplanner` | When a step fails, makes a dedicated LLM call to regenerate remaining steps or escalate |
| `PlanCheckpoint` | Persists progress to disk; resumes from the last completed step after crash or restart |

**Why this matters for long tasks:**

- **Visibility** — The user sees "Step 3/7: Refactor authentication module" in real time, not a stream of opaque tool calls.
- **Structured recovery** — When step N fails, the replanner receives full context (completed steps, failure reason, remaining plan) and produces either a revised plan or a graceful escalation. No guessing.
- **Crash safety** — `FilePlanCheckpointStore` writes plan state to disk. `ResumeRouter` detects incomplete plans on next session start and picks up where it left off.
- **Budget control** — Each step has its own iteration and wall-clock budget, preventing any single step from consuming the entire session.

## Security First

AceClaw defends across five dimensions:

- **Zero network surface** — Daemon communicates only via Unix Domain Socket. No HTTP, no REST, no WebSocket.
- **Sealed permissions** — 4-level hierarchy (`READ`/`WRITE`/`EXECUTE`/`DANGEROUS`) modeled as a sealed interface with compiler-enforced exhaustiveness. Sub-agents receive filtered tool registries to prevent privilege escalation.
- **Signed memory** — Every persisted memory entry is HMAC-SHA256 signed with constant-time verification. Tampered entries are rejected on load.
- **Content boundaries** — System prompt budget (150K char cap), tool result truncation (30K cap), and 8-tier priority ordering ensure human-authored content always outranks agent-generated memory.
- **Data protection** — POSIX 600 on signing keys, SHA-256 hashed workspace paths, size governance with automatic consolidation.

See the [Security Details](docs/security.md) for the full breakdown.

## Self-Learning

AceClaw learns from its own behavior — no LLM calls required. Every tool execution, error recovery, and user correction is analyzed by heuristic detectors that produce type-safe insights.

- **Automatic pattern detection** — `ErrorDetector` matches tool failures to subsequent retries. `SessionEndExtractor` captures user corrections and preferences via regex-based passes. `SessionAnalyzer`, `HistoricalLogIndex`, `CrossSessionPatternMiner`, and `TrendDetector` extend that learning across sessions.
- **Cross-session accumulation** — Insights start at 0.4 confidence and gain +0.2 per recurrence. Only patterns reaching 0.7 confidence are persisted.
- **Strategy evolution** — Errors become `ErrorInsight`s, recurring sequences become `SuccessInsight`s, unresolved errors become anti-patterns, and underperforming skills are refined or rolled back. A closed feedback loop: detect → persist → recall → refine.
- **Type-safe insight hierarchy** — `Insight` is a sealed interface (`ErrorInsight | SuccessInsight | PatternInsight | RecoveryRecipe | FailureInsight`). The compiler enforces exhaustive handling.
- **Deferred maintenance scheduler** — Session end performs extraction and indexing immediately; heavier consolidation, pattern mining, and trend detection run through background maintenance triggers (time, session count, size, idle).
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
- **23 memory categories** — From `CODEBASE_INSIGHT` and `ERROR_RECOVERY` to `RECOVERY_RECIPE` and `FAILURE_SIGNAL`.
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

## Tech Stack

Java 21 (preview features) · Gradle 8.14 · Picocli 4.7.6 · JLine3 3.27.1 · Jackson 2.18.2 · GraalVM Native Image · JUnit 5

## License

[Apache License 2.0](LICENSE)
