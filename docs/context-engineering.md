# AceClaw Context Engineering

> Version 1.0 | 2026-03-17

AceClaw actively manages what enters the context window so long-running sessions stay effective. This document covers the full pipeline: system prompt budgeting, request-aware assembly, request-time pruning, context compaction, candidate injection, and observability.

---

## Architecture Overview

```
User query
    ↓
RequestFocus (extract symbols, files, plan signals)
    ↓
SystemPromptLoader.inspectRequest()
    ├── MemoryTierLoader.loadAll()          [8-tier hierarchy]
    ├── RuleEngine.loadRules()              [path-based rules]
    ├── CandidatePromptAssembler            [promoted candidates]
    ├── applyRequestFocusPriority()         [dynamic priority boost]
    ├── ContextAssemblyPlan.build()         [budget enforcement]
    │   └── TierTruncator.applyBudget()    [70/20/10 truncation]
    └── ContextEstimator.estimateTokens()   [per-section cost]
    ↓
Assembled system prompt
    ↓
StreamingAgentLoop (per LLM call)
    ├── MessageCompactor.pruneForRequest()  [transient pruning]
    ├── checkAndCompact()                   [3-phase compaction]
    └── buildRequest()                      [final LLM request]
```

---

## 1. System Prompt Budget

### SystemPromptBudget

Configuration record that caps system prompt size.

| Field | Default | Description |
|-------|---------|-------------|
| `maxPerTierChars` | 20,000 | Max characters per individual memory tier |
| `maxTotalChars` | 150,000 | Max total characters across all tiers + base prompt |

For smaller context windows, `forContextWindow(contextTokens, maxOutput)` scales the budget to ~25% of the effective window (context minus output), clamped between 1K–150K total and 1K–20K per tier.

### TierTruncator

Enforces the budget in two passes:

1. **Pass 1**: Truncate individual tiers exceeding `maxPerTierChars`
2. **Pass 2**: If total exceeds `maxTotalChars`, truncate lowest-priority tiers first

**Truncation split**: 70% head + 20% tail + 10% marker (`<!-- [TRUNCATED] Original: N chars -->`). This preserves both core instructions at the start and recent additions at the end.

**Protected tiers**: Soul (priority 100) and Managed Policy (priority 90) are never truncated.

---

## 2. Context Assembly

### ContextAssemblyPlan

Generic section-based system prompt assembler. Maintains insertion order while allowing priority-driven truncation.

**Key methods:**
- `addSection(key, content, priority, protectedSection)` — builder pattern
- `build(SystemPromptBudget)` → `Result` containing the final prompt, truncated section keys, and per-section metadata

**Result includes per-section details:**

| Field | Description |
|-------|-------------|
| `key` | Section identifier (e.g., "memory:soul", "rules:test-conventions") |
| `priority` | Numeric priority (higher = harder to truncate) |
| `originalChars` | Size before budget enforcement |
| `finalChars` | Size after truncation |
| `included` | Whether the section survived budget enforcement |
| `truncated` | Whether the section was truncated |

---

## 3. Request-Aware Priority (RequestFocus)

`RequestFocus` extracts signals from each user query to dynamically boost relevant context sections.

### Extraction

```java
public record RequestFocus(
    String querySummary,           // normalized query (max 160 chars)
    List<String> activeFilePaths,  // files in focus (max 6)
    List<String> activeSymbols,    // Java symbols from backticks/CamelCase (max 8)
    List<String> planSignals       // inferred action signals
)
```

**Symbol extraction**: Identifies backtick-quoted code (`` `StreamingAgentLoop` ``) and CamelCase identifiers. Validates as Java identifiers, optionally dotted (e.g., `AppService.validate`, max 6 segments).

**Plan signals** (keyword-inferred):
- `"continue current execution"` — continue, resume, next step
- `"planning context"` — plan, next steps, steps to
- `"code change requested"` — fix, implement, edit, update, refactor
- `"verification requested"` — test, verify, review

### Priority Boosting

`applyRequestFocusPriority()` boosts section priorities by up to **12 points** (MAX_FOCUS_BOOST) based on RequestFocus signals:

| Section type | Boost condition | Points |
|-------------|-----------------|--------|
| Memory | Symbol match in content | +16 |
| Auto-memory | Always (learned signals) | +8 |
| Markdown memory | Active file mentioned | +10 |
| Journal | Continuation signal | +12 |
| Rules | Active file matches glob | +4 |
| Candidates | Symbol match | +6 |
| Git context | Continuation signal | +6 |

This means a section about `StreamingAgentLoop` gets boosted when the user asks about that class, making it survive truncation even under budget pressure.

---

## 4. Request-Time Pruning

### MessageCompactor.pruneForRequest()

Lightweight pre-flight pruning before each LLM call. Phase 1 only — no LLM calls, no session history mutation.

```java
public RequestPruneResult pruneForRequest(
    List<Message> messages,
    String systemPrompt,
    List<ToolDefinition> tools
)
```

**Behavior:**
1. Estimates full context with `ContextEstimator.estimateFullContext()`
2. If below the compaction trigger threshold → returns original messages unchanged
3. Otherwise, applies Phase 1 pruning:
   - Keeps last N turns intact (protected)
   - Replaces old tool results with 200-char stubs + `[content pruned...]` marker
   - Removes old thinking blocks entirely
4. Returns `RequestPruneResult` with pruned messages and token estimates

**Key property**: The pruned messages are **transient** — they are used only for the immediate LLM request. Session history (`allMessages`) is never modified. This is critical because compaction decisions must operate on the full conversation, not a transiently pruned view.

**Integration in StreamingAgentLoop:**
```
1. Run pruneForRequest() → get transient pruned messages + token estimate
2. Feed pruned token estimate to checkAndCompact() → compaction only fires if pruning alone is insufficient
3. If compaction ran, use full allMessages (now compacted); otherwise use pruned messages for the LLM call
```

---

## 5. Context Compaction (3-Phase)

When context pressure reaches 85% of the effective window, `MessageCompactor` runs a multi-phase compaction:

### Phase 0: Memory Flush (Heuristic)

Extracts key items from conversation before pruning:
- Modified files (from `write_file`, `edit_file` tool uses)
- Bash commands (>10 chars, excluding `cd`/`ls`)
- Errors (from `ToolResult` with `isError=true`)

These flow to `AutoMemoryStore` via the `StreamingAgentHandler`.

### Phase 1: Prune (Free)

- Replace old tool results with 200-char stubs
- Clear old thinking blocks
- Protect last 5 turns

Target: 60% of effective window. If Phase 1 achieves this, Phase 2 is skipped.

### Phase 2: Summarize (LLM Call)

- Structured prompt asks the LLM to summarize the conversation
- Extracts `<summary>` tags from the response
- Replaces old messages with summary + continuation instruction
- `[COMPACTED]` marker prevents re-summarizing summaries

### Token Tracking

`ContextEstimator` uses a ~4 chars/token heuristic but prefers actual `usage.inputTokens` from API responses when available. Key methods:

| Method | Purpose |
|--------|---------|
| `estimateTokens(String)` | Basic string → token estimate |
| `estimateFullContext(prompt, tools, messages)` | Total context cost |
| `checkBudget(...)` | Pre-flight validation with `BudgetCheck` result |

**Overhead constants**: MESSAGE_OVERHEAD=4, TOOL_USE_OVERHEAD=20, TOOL_RESULT_OVERHEAD=10, TOOL_DEF_OVERHEAD=10.

---

## 6. Candidate Injection

### CandidateStore

Manages learning candidates through a state machine lifecycle:

```
SHADOW → PROMOTED → IN_USE → ARCHIVED
           ↓
        DEMOTED (cooldown) → SHADOW (retry)
```

| State | Description |
|-------|-------------|
| `SHADOW` | Newly observed, accumulating evidence |
| `PROMOTED` | Passed promotion gates, injected into system prompt |
| `DEMOTED` | Failed or regressed, cooling down before retry |

**Key behaviors:**
- `upsert(CandidateObservation)` — merges with existing candidates via Jaccard similarity (≥0.50, 30-day window)
- `evaluateAll()` — automatic promotion/demotion based on `CandidateStateMachine` gates
- **Score decay**: exponential with 30-day half-life and 7-day grace period
- **Retention**: automatic removal after 90 days of inactivity
- **Integrity**: HMAC-SHA256 signed; tampered entries skipped on load

Promoted candidates are assembled into the system prompt by `CandidatePromptAssembler` and receive RequestFocus priority boosts based on symbol/file/plan signal matches.

---

## 7. Observability

### context.inspect RPC

`ContextRpcHelper` registers the `context.inspect` JSON-RPC method. It returns:

```json
{
  "sessionId": "...",
  "totalChars": 42000,
  "estimatedTokens": 10500,
  "contextWindowTokens": 200000,
  "systemPromptSharePct": 5.25,
  "requestFocus": {
    "querySummary": "fix the streaming bug",
    "activeFilePaths": ["src/agent/StreamingAgentLoop.java"],
    "activeSymbols": ["StreamingAgentLoop"],
    "planSignals": ["code change requested"]
  },
  "budget": { "maxPerTierChars": 20000, "maxTotalChars": 150000 },
  "truncatedSectionKeys": [],
  "sections": [
    {
      "key": "memory:soul",
      "sourceType": "memory",
      "scopeType": "always-on",
      "inclusionReason": "8-tier memory hierarchy",
      "priority": 100,
      "protected": true,
      "originalChars": 1200,
      "finalChars": 1200,
      "included": true,
      "truncated": false,
      "evidence": ["tier=Soul", "priority=100"]
    }
  ]
}
```

### /context CLI Command

The `/context` command in `TerminalRepl` renders the inspection data:

```
/context list          # Summary: prompt size, budget, pressure, per-section costs
/context detail <key>  # Full content of a specific section with metadata
```

**List view includes:**
- System prompt size and context window share percentage
- Live context occupation and pressure level (color-coded)
- Compaction statistics (count, phases reached, tokens saved)
- Injection cost breakdown by type (rules, candidates, skills, learned signals, memory, core)
- Per-section cost table

### Context Monitor

`ContextMonitor` in the CLI tracks live context metrics across turns:

| Method | Description |
|--------|-------------|
| `recordTurnComplete()` | Updates metrics after each turn |
| `currentContextTokens()` | Live context occupation |
| `pressureLevel()` | Status indicator (low/medium/high/critical) |
| `peakContextTokens()` | Session peak |
| `compactionCount()` | Total compactions triggered |

---

## Key Design Decisions

1. **Character budgets, not token budgets** — Neither Claude Code nor OpenClaw uses precise token accounting for system prompt assembly. Character caps with ~4 chars/token heuristic are sufficient and avoid API dependencies.

2. **Transient pruning before persistent compaction** — `pruneForRequest()` produces a disposable pruned copy. Session history stays intact for compaction decisions. This prevents double-counting and ensures compaction only fires when truly needed.

3. **Priority-driven truncation** — Human-authored tiers (Soul, Policy) survive budget pressure; agent-generated tiers (Auto-Memory, Journal) are truncated first.

4. **Request-aware boosting** — Static priority ordering is overridden per-request based on query signals. A section about `ErrorDetector` gets boosted when the user asks about error detection, even if its base priority is low.

5. **Observable by default** — Every section carries metadata (source type, scope, inclusion reason, evidence) that can be inspected via `/context`. No black-box prompt assembly.
