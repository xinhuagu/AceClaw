# AceClaw Self-Learning Pipeline

> Version 1.0 | 2026-02-19

## Overview

AceClaw learns from its own behavior. Every tool execution, error recovery, and user correction is analyzed by zero-cost heuristic detectors that produce type-safe insights — no LLM calls required. These insights accumulate across sessions, refine agent strategies, and persist as HMAC-signed memories.

This document covers the architecture defined by issues #13–#18.

---

## Architecture

```
                         ┌─────────────────────┐
                         │   StreamingAgentLoop │
                         │  (ReAct loop cycle)  │
                         └─────────┬───────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                     ▼
   ┌──────────────────┐  ┌────────────────┐  ┌──────────────────────┐
   │ ToolMetrics       │  │ Turn           │  │ Conversation History │
   │ Collector         │  │ (messages,     │  │ (full session)       │
   │ (per-tool stats)  │  │  tool results) │  │                      │
   └────────┬─────────┘  └───────┬────────┘  └──────────┬───────────┘
            │                     │                       │
            ▼                     ▼                       ▼
   ┌──────────────────┐  ┌────────────────┐  ┌──────────────────────┐
   │ Metrics Snapshot  │  │ ErrorDetector  │  │ SessionEndExtractor  │
   │ (success rate,    │  │ (error→fix     │  │ (corrections, prefs, │
   │  avg latency)     │  │  pattern match)│  │  feedback, files)    │
   └────────┬─────────┘  └───────┬────────┘  └──────────┬───────────┘
            │                     │                       │
            │              ┌──────┴──────┐                │
            │              ▼             ▼                │
            │     ErrorInsight    PatternInsight          │
            │                                             │
            └──────────────┬──────────────────────────────┘
                           ▼
                  ┌─────────────────┐
                  │  AutoMemoryStore │
                  │ (HMAC-signed    │
                  │  JSONL storage) │
                  └────────┬────────┘
                           ▼
                  ┌─────────────────┐
                  │ Memory          │
                  │ Consolidator    │
                  │ (dedup, merge,  │
                  │  age-prune)     │
                  └─────────────────┘
```

**Data flow:** The agent loop produces raw signals (tool metrics, turn messages, conversation history). Detectors analyze those signals into typed insights. Insights are persisted as signed memory entries and consolidated at session end.

---

## Insight Type Hierarchy

All self-learning outputs converge on a single sealed interface:

```java
public sealed interface Insight
    permits Insight.ErrorInsight, Insight.SuccessInsight, Insight.PatternInsight {

    String description();
    List<String> tags();
    MemoryEntry.Category targetCategory();
    double confidence();   // [0.0, 1.0]
}
```

The compiler enforces exhaustive handling — every `switch` over `Insight` must cover all three variants.

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

Only insights with `confidence >= 0.7` are persisted to long-term memory. Lower-confidence insights are retained in the working set for the current session but discarded at session end unless reinforced.

### Cross-Session Accumulation

Each time a pattern is re-detected across sessions, its confidence increases:
- **Base confidence** for a first-time detection: 0.4
- **Cross-session boost** per matching prior memory: +0.2
- **Maximum confidence**: 1.0

This means a pattern must be observed in at least 2 sessions before reaching the 0.7 persistence threshold, preventing one-off flukes from polluting long-term memory.

### Debounce

Memory entries are deduplicated during the 3-pass consolidation:
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

The `SessionEndExtractor` complements the per-turn detectors by scanning the full conversation history at session close. Five regex-based passes extract:

| Pass | Pattern | Category |
|------|---------|----------|
| 1 | User corrections (`"no,"`, `"wrong"`, `"actually"`) | `CORRECTION` |
| 2 | Explicit preferences (`"always"`, `"never"`, `"from now on"`) | `PREFERENCE` |
| 3 | Modified files (3+ `write_file`/`edit_file` calls) | `CODEBASE_INSIGHT` |
| 4 | Error recovery / success phrases (`"fixed by"`, `"build succeeded"`) | `ERROR_RECOVERY` / `SUCCESSFUL_STRATEGY` |
| 5 | User feedback (`"great"`, `"that's right"` / `"that's wrong"`) | `USER_FEEDBACK` |

All extracted entries are capped at 200 characters and tagged for searchability.

---

## Key Advantages

| Property | Benefit |
|----------|---------|
| **Zero LLM cost** | All detection is heuristic — regex patterns, order-based matching, atomic counters. No API calls for learning. |
| **Type-safe insights** | Sealed `Insight` interface with compiler-enforced exhaustiveness. Adding a new insight type is a compile error until all handlers are updated. |
| **Cross-session accumulation** | Confidence grows over sessions. First-time patterns start below threshold; only reinforced patterns persist. |
| **Automatic strategy evolution** | Errors become insights, insights become anti-patterns, anti-patterns prevent future errors — a closed feedback loop. |
| **Thread-safe metrics** | Lock-free `ConcurrentHashMap` + atomic counters. No synchronization overhead during the ReAct loop. |
| **Tamper-resistant storage** | All persisted insights are HMAC-SHA256 signed. Corrupted entries are rejected on load. |

---

## Related Issues

| Issue | Title |
|-------|-------|
| #13 | Insight type hierarchy + DetectedPattern + ToolMetricsCollector |
| #14 | ErrorDetector for error-correction patterns |
| #15 | SequenceDetector for repeated tool sequences |
| #16 | PreferenceDetector for user corrections |
| #17 | SelfImprovementEngine orchestrator |
| #18 | Wire self-improvement into AgentSession lifecycle |
