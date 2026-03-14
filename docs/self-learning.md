# AceClaw Self-Learning Pipeline

> Version 2.0 | 2026-03-13

## Overview

AceClaw learns from its own behavior across three layers:

1. **Per-turn detectors** capture tool failures, recoveries, and recurring local patterns.
2. **Session-close retrospectives** summarize what happened in the finished session and append a historical snapshot.
3. **Deferred maintenance** consolidates memory, mines cross-session patterns, and detects trends on background triggers.

The result is a learning loop that stays cheap on the hot path while still building long-term signals for adaptive skills and historical analysis.

---

## Architecture

```
                         ┌─────────────────────┐
                         │ StreamingAgentLoop  │
                         │   (ReAct loop)      │
                         └─────────┬───────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                     ▼
   ┌──────────────────┐  ┌────────────────┐  ┌──────────────────────┐
   │ ToolMetrics       │  │ Turn           │  │ Conversation History │
   │ Collector         │  │ (messages,     │  │ (full session)       │
   │                   │  │  tool results) │  │                      │
   └────────┬─────────┘  └───────┬────────┘  └──────────┬───────────┘
            │                     │                       │
            │          ┌──────────┼───────────────────────┘
            │          ▼          ▼
            │  ┌────────────────────────────────┐
            │  │   SelfImprovementEngine                  │
            │  │ ErrorDetector + PatternDetector            │
            │  │              + FailureSignalDetector       │
            │  └──────────────┬────────────────────────────┘
            │                 │
            │                 ▼
            │          ┌──────────────────────┐
            │          │ SessionEndExtractor   │
            │          │ SessionAnalyzer       │
            │          └──────────┬────────────┘
            │                     │
            │                     ▼
            │          ┌──────────────────────┐
            │          │ HistoricalLogIndex    │
            │          │ (session snapshots)   │
            │          └──────────┬────────────┘
            │                     │
            └─────────────────────┼─────────────────────────────┐
                                  ▼                             │
                         ┌─────────────────┐                    │
                         │ AutoMemoryStore │                    │
                         │  (JSONL + HMAC) │                    │
                         └────────┬────────┘                    │
                                  ▼                             │
                      ┌─────────────────────────┐               │
                      │ LearningMaintenance     │◀──────────────┘
                      │ Scheduler               │
                      │ (time/session/size/idle)│
                      └────────┬────────────────┘
                               ▼
        ┌──────────────────────────────────────────────────────────────┐
        │ MemoryConsolidator → CrossSessionPatternMiner → TrendDetector│
        └──────────────────────────────────────────────────────────────┘
```

**Data flow:** The agent loop produces raw signals. Per-turn detectors and session-close retrospectives persist immediate learnings and historical snapshots. Heavier maintenance work then runs asynchronously through the learning maintenance scheduler instead of blocking session teardown.

## Current Learning Loop

The current end-to-end loop is:

1. **Turn-time detection**: `ErrorDetector` and `PatternDetector` emit typed insights.
2. **Outcome tracking**: `SkillOutcomeTracker` records success, failure, and user correction for invoked skills.
3. **Session-close retrospective**: `SessionEndExtractor` and `SessionAnalyzer` summarize the finished session.
4. **Historical indexing**: `HistoricalLogIndex` stores normalized snapshots for later mining.
5. **Deferred maintenance**: `LearningMaintenanceScheduler` triggers `MemoryConsolidator`, `CrossSessionPatternMiner`, and `TrendDetector`.
6. **Skill adaptation**: metrics and memory feedback feed `SkillRefinementEngine`, which can refine, rollback, or deprecate skills.

---

## Insight Type Hierarchy

All self-learning outputs converge on a single sealed interface:

```java
public sealed interface Insight
    permits Insight.ErrorInsight, Insight.SuccessInsight,
        Insight.PatternInsight, Insight.RecoveryRecipe, Insight.FailureInsight {

    String description();
    List<String> tags();
    MemoryEntry.Category targetCategory();
    double confidence();   // [0.0, 1.0]
}
```

The compiler enforces exhaustive handling — every `switch` over `Insight` must cover all five variants.

### ErrorInsight

Produced when a tool fails and is subsequently retried successfully.

| Field | Type | Description |
|-------|------|-------------|
| `toolName` | `String` | The tool that failed |
| `errorMessage` | `String` | Truncated error output (max 500 chars) |
| `resolution` | `String` | How the error was resolved |
| `confidence` | `double` | 0.4 base + 0.2 per cross-session match (max 1.0) |

Target category: `ERROR_RECOVERY`

### SuccessInsight

Produced when a multi-tool sequence completes a task successfully.

| Field | Type | Description |
|-------|------|-------------|
| `toolSequence` | `List<String>` | Ordered list of tool names (immutable) |
| `taskDescription` | `String` | What the sequence accomplished |
| `confidence` | `double` | Confidence score in [0.0, 1.0] |

Target category: `SUCCESSFUL_STRATEGY`

### PatternInsight

Produced when recurring behavioral patterns are detected across sessions.

| Field | Type | Description |
|-------|------|-------------|
| `patternType` | `PatternType` | One of 4 pattern types (see below) |
| `description` | `String` | Human-readable pattern description |
| `frequency` | `int` | How many times the pattern was observed |
| `confidence` | `double` | Confidence score in [0.0, 1.0] |
| `evidence` | `List<String>` | Traces like `"session:abc turn 5: grep->read->edit"` |

Target category varies by `PatternType`:

| PatternType | Category |
|-------------|----------|
| `REPEATED_TOOL_SEQUENCE` | `PATTERN` |
| `ERROR_CORRECTION` | `ERROR_RECOVERY` |
| `USER_PREFERENCE` | `PREFERENCE` |
| `WORKFLOW` | `PATTERN` |

### RecoveryRecipe

Produced when multi-step error-correction sequences are detected. Captures a reusable recovery procedure for a specific error pattern.

| Field | Type | Description |
|-------|------|-------------|
| `triggerPattern` | `String` | The error pattern that triggers this recipe |
| `steps` | `List<RecoveryStep>` | Ordered recovery steps (must not be empty) |
| `toolName` | `String` | The primary tool involved (for indexing) |
| `confidence` | `double` | Confidence score in [0.0, 1.0] |

Each `RecoveryStep` contains:

| Field | Type | Description |
|-------|------|-------------|
| `description` | `String` | Human-readable description of the step |
| `toolName` | `String` | Tool to use for this step (nullable) |
| `parameterHint` | `String` | Hint about parameters (nullable) |

Target category: `RECOVERY_RECIPE`

### FailureInsight

Produced by `FailureSignalDetector` from normalized runtime failure signals in tool results.

| Field | Type | Description |
|-------|------|-------------|
| `type` | `FailureType` | Normalized failure taxonomy (7 values — see below) |
| `source` | `String` | Signal origin (e.g. `"permission-gate"`, `"tool"`, `"background-task"`, `"sub-agent"`) |
| `toolOrAgent` | `String` | Tool or sub-agent name associated with the failure |
| `reason` | `String` | Human-readable reason (sanitized, max 400 chars) |
| `retryable` | `boolean` | Whether retry is likely useful |
| `timestamp` | `Instant` | Event timestamp |
| `confidence` | `double` | Confidence score in [0.0, 1.0] (0.85–0.95 depending on type) |

**FailureType values:** `PERMISSION_DENIED`, `PERMISSION_PENDING_TIMEOUT`, `TIMEOUT`, `DEPENDENCY_MISSING`, `CAPABILITY_MISMATCH`, `BROKEN`, `CANCELLED`

Target category: `FAILURE_SIGNAL`

---

## Detection Strategies

### 1. Error-Correction Detection (`ErrorDetector`)

**Location:** `aceclaw-daemon` module

Analyzes a single turn's messages to find error→fix pairs:

1. Collects all `ToolUse` and `ToolResult` blocks, preserving message order.
2. Identifies `ToolResult` blocks where `isError() == true`.
3. For each error, finds the earliest subsequent success for the **same tool name**.
4. Produces an `ErrorInsight` with the error message and resolution.
5. Cross-session boosting: queries `AutoMemoryStore` for prior `ERROR_RECOVERY` entries matching the tool name. Each match adds +0.2 to confidence (capped at 1.0).

**Confidence calculation:**
```
confidence = BASE (0.4) + cross_session_matches * BOOST (0.2)
           = min(result, 1.0)
```

A first-time error correction starts at 0.4 confidence. If the same tool has failed and recovered in 3 prior sessions, confidence reaches 1.0.

### 2. Repeated Sequence Detection

**PatternType:** `REPEATED_TOOL_SEQUENCE`

Detects when the same 3+ tool sequence appears 3+ times across sessions. Example: `grep → read_file → edit_file` appearing in 5 different sessions suggests a stable workflow worth remembering.

### 3. User Preference Detection

**PatternType:** `USER_PREFERENCE`

Detects when the user corrects agent behavior 2+ times in the same way. The `SessionEndExtractor` identifies corrections via regex patterns (`"^no,"`, `"wrong"`, `"actually"`, `"should be"`) and explicit preferences (`"always"`, `"never"`, `"from now on"`).

### 4. Workflow Detection

**PatternType:** `WORKFLOW`

Detects multi-step operations performed 3+ times with similar structure. Higher-level than repeated sequences — captures intent patterns rather than exact tool chains.

---

## Tool Metrics Collection

The `ToolMetricsCollector` tracks per-tool execution statistics in real time using lock-free atomic counters (`ConcurrentHashMap` + `AtomicInteger`/`AtomicLong`):

| Metric | Type | Description |
|--------|------|-------------|
| `totalInvocations` | `int` | Total calls to this tool |
| `successCount` | `int` | Calls that returned `isError=false` |
| `errorCount` | `int` | Calls that returned `isError=true` or threw exceptions |
| `totalExecutionMs` | `long` | Cumulative wall-clock execution time |
| `lastUsed` | `Instant` | Timestamp of most recent invocation |

**Derived metrics:**
- `avgExecutionMs()` — average latency per invocation
- `successRate()` — success count / total invocations (0.0–1.0)

**Integration:** Wired into `StreamingAgentLoop` via `AgentLoopConfig.metricsCollector()`. After every tool execution — success or exception — metrics are recorded automatically. The collector is session-scoped (one instance per `AgentSession`).

```
StreamingAgentLoop
  └─ after tool execution → metricsCollector.record(toolName, success, durationMs)

AgentLoopConfig
  └─ metricsCollector: ToolMetricsCollector  (nullable — null = disabled)
```

---

## SelfImprovementEngine

The `SelfImprovementEngine` is the facade that orchestrates all detectors into a unified post-turn learning pipeline. Located in `aceclaw-daemon`, it runs asynchronously on a virtual thread after each agent turn.

**Pipeline:**
```
Agent Turn
  ├── ErrorDetector          → ErrorInsights
  ├── PatternDetector        → PatternInsights
  └── FailureSignalDetector  → FailureInsights
        ↓
  SelfImprovementEngine (deduplicate + filter)
        ↓
  AutoMemoryStore.add() (persist high-confidence insights)
```

**Key behaviors:**

| Step | Description |
|------|-------------|
| **Analyze** | Runs all three detectors, collects all insights |
| **Deduplicate** | Groups by category + Jaccard similarity (>= 0.7), keeps highest-confidence |
| **Filter** | Only insights with `confidence >= 0.7` pass to persistence |
| **Memory dedup** | Checks existing `AutoMemoryStore` entries before writing (avoids duplicates) |
| **Persist** | Writes to AutoMemoryStore with `self-improve:{sessionId}` source tag |

**Integration points:**
- `StreamingAgentHandler` — holds engine reference via `setSelfImprovementEngine()`, fires async virtual thread after each turn completes
- `AceClawDaemon.wireAgentHandler()` — creates `ErrorDetector`, `PatternDetector`, and `SelfImprovementEngine`, wires into handler when `memoryStore` is available

**Failure isolation:** All exceptions are caught and logged — the engine never propagates errors to the agent session or blocks the response to the user.

---

## Strategy Refinement

The `DetectedPattern` record bridges raw detection into the `Insight` hierarchy:

```java
public record DetectedPattern(
    PatternType type,
    String description,
    int frequency,
    double confidence,
    List<String> evidence
) {
    public Insight.PatternInsight toInsight() { ... }
}
```

Refinement strategies applied to accumulated insights:

| Strategy | Input | Output |
|----------|-------|--------|
| **Error consolidation** | Multiple `ErrorInsight`s for the same tool | Merged entry with boosted confidence |
| **Sequence optimization** | Repeated `SuccessInsight` tool sequences | Canonical workflow pattern |
| **Anti-pattern generation** | Recurring errors without resolution | `ANTI_PATTERN` memory entry |
| **Preference strengthening** | Same user correction seen multiple times | Higher-confidence `PREFERENCE` entry |

---

## Persistence Rules

### Confidence Threshold

Only insights with `confidence >= 0.7` are persisted to long-term memory. Lower-confidence insights remain session-local unless reinforced by later detections.

### Cross-Session Accumulation

Each time a pattern is re-detected across sessions, its confidence increases:
- **Base confidence** for a first-time detection: 0.4
- **Cross-session boost** per matching prior memory: +0.2
- **Maximum confidence**: 1.0

This means a pattern must be observed in at least 2 sessions before reaching the 0.7 persistence threshold, preventing one-off flukes from polluting long-term memory.

### Debounce

Memory entries are deduplicated during deferred 3-pass consolidation:
1. **Dedup** — Exact content matches are merged (higher confidence wins).
2. **Similarity merge** — Entries with >80% text similarity are consolidated.
3. **Age prune** — Entries older than 90 days with zero access are archived.

### Storage Format

All persisted insights become `MemoryEntry` records stored in HMAC-SHA256 signed JSONL files under `~/.aceclaw/workspaces/{hash}/memory/`. Each entry includes:
- Category (from `Insight.targetCategory()`)
- Tags (from `Insight.tags()`)
- Content (from `Insight.description()`)
- Confidence score
- Timestamp and access counters

See [Memory System Design](memory-system-design.md) for the full storage architecture.

---

## Session-End Extraction

The `SessionEndExtractor` complements the per-turn detectors by scanning the full conversation history at session close. The `SessionAnalyzer` then produces a compact retrospective summary and normalized historical snapshot. Five regex-based extraction passes still feed the memory path:

| Pass | Pattern | Category |
|------|---------|----------|
| 1 | User corrections (`"no,"`, `"wrong"`, `"actually"`) | `CORRECTION` |
| 2 | Explicit preferences (`"always"`, `"never"`, `"from now on"`) | `PREFERENCE` |
| 3 | Modified files (3+ `write_file`/`edit_file` calls) | `CODEBASE_INSIGHT` |
| 4 | Error recovery / success phrases (`"fixed by"`, `"build succeeded"`) | `ERROR_RECOVERY` / `SUCCESSFUL_STRATEGY` |
| 5 | User feedback (`"great"`, `"that's right"` / `"that's wrong"`) | `USER_FEEDBACK` |

All extracted entries are capped at 200 characters and tagged for searchability.

## Deferred Learning Maintenance

After session-close extraction and indexing complete, the learning maintenance scheduler runs heavier background passes using four triggers:

- **Time-based**: every 6 hours
- **Session-count**: every 10 closed sessions
- **Size-based**: when memory backing files exceed 50 KB
- **Idle-based**: after 5 minutes with no active sessions

The maintenance pipeline currently runs six steps in order:

1. **Memory consolidation** — 3-pass dedup/merge/prune via `MemoryConsolidator`
2. **Correction rule promotion** — `CorrectionRulePromoter` groups similar CORRECTION/MISTAKE entries (Jaccard >= 0.50), promotes groups with 2+ occurrences to `.aceclaw/ACECLAW.md` rules (Tier 6 auto-memory → Tier 3 workspace memory)
3. **Historical index rebuild** — `HistoricalIndexRebuilder` rebuilds workspace-scoped historical index entries from persisted session snapshots when stale
4. **Cross-session pattern mining** — `CrossSessionPatternMiner` finds frequent error chains, stable workflows, converging strategies, and degradation signals
5. **Trend detection** — `TrendDetector` identifies rising/falling/stable trends across sessions
6. **Candidate pipeline bridge** — `LearningMaintenanceCandidateBridge` converts mining results and trends into candidate observations for the learning candidate pipeline

This keeps session teardown fast while still preserving a self-maintaining memory system.

---

## Correction Rule Auto-Promotion

The `CorrectionRulePromoter` closes the self-learning loop by detecting when the agent makes the same mistake 2+ times across sessions and automatically elevating those corrections from Tier 6 (auto-memory) to Tier 3 (workspace memory in `.aceclaw/ACECLAW.md`).

**Lifecycle:**
1. Scan auto-memory for `CORRECTION` and `MISTAKE` category entries
2. Group similar entries using Jaccard token similarity (threshold >= 0.50)
3. For groups with 2+ entries, generate a rule with a deterministic SHA-256 fingerprint
4. Skip rules whose fingerprint is already present in ACECLAW.md (idempotent)
5. Append new rules under the `## Auto-Promoted Rules` section in `.aceclaw/ACECLAW.md`

**Key constants:**

| Constant | Value | Description |
|----------|-------|-------------|
| `MIN_OCCURRENCES` | 2 | Minimum similar corrections before promoting |
| `SIMILARITY_THRESHOLD` | 0.50 | Jaccard similarity for grouping (looser than consolidation's 0.80) |
| `SECTION_MARKER` | `## Auto-Promoted Rules` | Section header in ACECLAW.md |
| `FINGERPRINT_PREFIX` | `<!-- rule-fingerprint: ` | HTML comment prefix for dedup across runs |

**Output format in ACECLAW.md:**
```markdown
## Auto-Promoted Rules

> Rules below were auto-promoted from repeated corrections.
> They indicate mistakes made 2+ times across sessions.

<!-- rule-fingerprint: a1b2c3d4 -->
### JAVA / API
**Process.getInputStream() not inputStream()**

_Sources: 3 corrections across sessions. Tags: java, api_
```

**Triggered by:** The deferred learning maintenance scheduler (step 2 of the 6-step pipeline).

---

## Failure Signal Detection

The `FailureSignalDetector` produces normalized runtime failure signals (`FailureInsight`) from structured tool results. It is the third detector in the `SelfImprovementEngine` pipeline, alongside `ErrorDetector` and `PatternDetector`.

**Classification:** Each failed tool result is classified into one of 7 `FailureType` values using keyword matching on the error content:

| FailureType | Trigger Keywords | Retryable | Base Confidence |
|-------------|-----------------|-----------|-----------------|
| `PERMISSION_DENIED` | "permission denied" | No | 0.95 |
| `PERMISSION_PENDING_TIMEOUT` | "permission pending timeout" | Yes | 0.95 |
| `DEPENDENCY_MISSING` | "no module named", "command not found", "not installed" | Yes | 0.90 |
| `CAPABILITY_MISMATCH` | "unsupported", "invalid format", "cannot parse" | Yes | 0.90 |
| `TIMEOUT` | "timed out", "timeout" | Yes | 0.85 |
| `BROKEN` | "status: failed", "sub-agent error", "broken pipe" | Yes | 0.85 |
| `CANCELLED` | "cancelled", "canceled" | Yes | 0.85 |

**Source mapping:** The `source` field is derived from the tool name — `"permission-gate"` for permission failures, `"background-task"` for `task_output`, `"sub-agent"` for `task`, and `"tool"` for everything else.

**Integration:** Wired into `SelfImprovementEngine` via the 3-arg and higher constructors. Failure signals that don't match any classification are silently dropped (null return from `classify()`).

---

## Key Advantages

| Property | Benefit |
|----------|---------|
| **Zero LLM cost** | All detection is heuristic — regex patterns, order-based matching, atomic counters. No API calls for learning. |
| **Type-safe insights** | Sealed `Insight` interface (5 permits) with compiler-enforced exhaustiveness. Adding a new insight type is a compile error until all handlers are updated. |
| **Cross-session accumulation** | Confidence grows over sessions. First-time patterns start below threshold; only reinforced patterns persist. |
| **Automatic strategy evolution** | Errors become insights, insights become anti-patterns, anti-patterns prevent future errors — a closed feedback loop. |
| **Thread-safe metrics** | Lock-free `ConcurrentHashMap` + atomic counters. No synchronization overhead during the ReAct loop. |
| **Tamper-resistant storage** | All persisted insights are HMAC-SHA256 signed. Corrupted entries are rejected on load. |

---

## Related Issues

| Issue | Title | Status |
|-------|-------|--------|
| #13 | Insight type hierarchy + DetectedPattern + ToolMetricsCollector | Done (PR #19) |
| #14 | ErrorDetector for error-correction patterns | Done (PR #20) |
| #15 | PatternDetector for recurring tool sequences and workflows | Done (PR #21) |
| #16 | SelfImprovementEngine orchestrator + daemon integration | Done (PR #22) |
| #17 | StrategyRefinement — anti-pattern generation and preference strengthening | Done (PR #23) |
| #18 | E2E integration test for self-learning pipeline | Done (PR #24) |
| #25 | Self-Learning Gaps (P0-P3): charset, RecoveryRecipe, strategy injection, ErrorClass | Done (PR #26) |
| #259 | CorrectionRulePromoter — auto-promote repeated corrections to ACECLAW.md rules | Done |
