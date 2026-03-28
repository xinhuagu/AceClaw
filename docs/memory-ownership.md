# Memory Ownership Model

This document defines the memory ownership model for multi-session AceClaw. It classifies every persistent store by scope, documents concurrency guarantees, and identifies follow-up work.

See also: [Multi-Session Model](multi-session.md) for the session/workspace architecture.

## Scope Definitions

| Scope | Lifetime | Shared By | Location |
|-------|----------|-----------|----------|
| **Session-local** | One TUI session | Nothing — fully isolated | `~/.aceclaw/history/{sessionId}.*`, `~/.aceclaw/sessions/`, `~/.aceclaw/checkpoints/` |
| **Workspace-scoped** | Project lifetime | All sessions targeting the same project directory | `~/.aceclaw/workspaces/{hash}/`, `{projectRoot}/.aceclaw/` |
| **Global/user-scoped** | User lifetime | All sessions across all workspaces | `~/.aceclaw/memory/`, `~/.aceclaw/index/`, `~/.aceclaw/skills/` |

## Store Classification

### Session-Local Stores

These stores are fully isolated per session. No multi-session concerns.

| Store | Module | Persistence | Notes |
|-------|--------|-------------|-------|
| `SessionHistoryStore` | daemon | `~/.aceclaw/history/{sessionId}.jsonl` | Conversation messages + snapshot |
| `FilePlanCheckpointStore` | daemon | `~/.aceclaw/checkpoints/{planId}.checkpoint.json` | Plan resume state |
| `ResumeCheckpointStore` | cli | `~/.aceclaw/sessions/{sessionId}-{taskId}.checkpoint.json` | Task resume state |
| `AgentSession` (in-memory) | daemon | Not persisted | Conversation messages, active flag |
| `ErrorDetector` | daemon | Via HistoricalLogIndex | Analyzes current session errors |
| `PatternDetector` | daemon | Via HistoricalLogIndex | Detects patterns in current session |
| `SessionEndExtractor` | daemon | Feeds → AutoMemoryStore | Heuristic extraction at session end |

### Workspace-Scoped Stores

These stores are shared by all sessions targeting the same workspace. Concurrent access is serialized.

| Store | Module | Persistence | Concurrency |
|-------|--------|-------------|-------------|
| `MarkdownMemoryStore` | memory | `~/.aceclaw/workspaces/{hash}/memory/MEMORY.md` + topic files | `ReentrantLock` |
| `DailyJournal` | memory | `~/.aceclaw/workspaces/{hash}/memory/journal/YYYY-MM-DD.md` | `ReentrantLock` |
| `CorrectionRulePromoter` | memory | Writes to workspace `ACECLAW.md` | Via MarkdownMemoryStore lock |
| `LearningExplanationStore` | daemon | `{projectRoot}/.aceclaw/metrics/learning-explanations.jsonl` | `FileChannel.lock()` |
| `LearningValidationStore` | daemon | `{projectRoot}/.aceclaw/metrics/learning-validations.jsonl` | `FileChannel.lock()` |
| `LearningSignalReviewStore` | daemon | `{projectRoot}/.aceclaw/metrics/learning-signal-reviews.jsonl` | `ReentrantLock` + `FileChannel.lock()` |
| `SkillDraftGenerator` | daemon | `{projectRoot}/.aceclaw/skills-drafts/{name}/SKILL.md` | Atomic write (tmp→rename) |
| `SessionSkillPacker` | daemon | `{projectRoot}/.aceclaw/skills-drafts/{name}/SKILL.md` | Atomic write (tmp→rename) |

**Workspace path resolution**: `WorkspacePaths` computes a deterministic hash (`SHA-256`, truncated to 12 hex chars) of the canonical workspace path. All sessions in the same project directory share the same workspace directory.

### Global/User-Scoped Stores

These stores are shared across all workspaces. Most use file-level locking for safety.

| Store | Module | Persistence | Concurrency |
|-------|--------|-------------|-------------|
| `AutoMemoryStore` | memory | `~/.aceclaw/memory/global.jsonl` + per-project files | `ReentrantLock` |
| `CandidateStore` | memory | `~/.aceclaw/memory/candidates.jsonl` | `ReentrantLock`, full rewrite |
| `HistoricalLogIndex` | memory | `~/.aceclaw/index/*.jsonl` | `FileChannel.lock()` on append |
| `SkillMetricsStore` | daemon | `~/.aceclaw/skills/{name}/metrics.json` | Atomic write |
| `SkillRefinementEngine` | daemon | `~/.aceclaw/skills/{name}/` | Atomic write |

## Promotion Paths

Data flows from session-local to durable scopes through defined pipelines:

```
Session-Local                  Workspace-Scoped              Global
─────────────                  ────────────────              ──────
AgentSession messages
  → SessionEndExtractor ──────→ AutoMemoryStore (project)
  → ErrorDetector ─────────────────────────────────────────→ HistoricalLogIndex
  → PatternDetector ───────────────────────────────────────→ HistoricalLogIndex

                               AutoMemoryStore (project)
                                 → MemoryConsolidator ────→ (dedup/prune in place)
                                 → CrossSessionPatternMiner → AutoMemoryStore
                                 → StrategyRefiner ───────→ AutoMemoryStore
                                 → TrendDetector ─────────→ AutoMemoryStore
                                 → CorrectionRulePromoter → MarkdownMemoryStore

                               CandidateStore ────────────→ (global candidates)
                                 → SkillDraftGenerator ──→ workspace skill drafts
                                 → SkillRefinementEngine → workspace/user skills
```

**Key rule**: Session-local insights become durable memory only through the `SelfImprovementEngine` pipeline, which runs after session close. This avoids mid-conversation writes to shared stores from concurrent sessions.

## Concurrency Guarantees

All stores are safe for multi-session concurrent access:

- **Session-local**: Fully isolated by session ID — no contention possible.
- **Workspace-scoped**: Serialized by `ReentrantLock` or `FileChannel.lock()`. Multiple sessions in the same workspace can safely write concurrently.
- **Global**: Serialized by `ReentrantLock` or `FileChannel.lock()`. The learning pipeline runs after session close, reducing contention.

### Known Limitation: CandidateStore Full-File Rewrite

`CandidateStore` rewrites the entire `candidates.jsonl` file on state transitions. If two sessions transition candidates simultaneously, one write may be lost. This is mitigated by:
- The `ReentrantLock` serializing in-process writes
- State transitions being infrequent (triggered by learning maintenance, not on every turn)
- The candidate state machine being idempotent (re-applying a transition is safe)

**Follow-up**: Consider per-workspace candidate partitioning or CAS-based updates if contention becomes an issue.

## File System Layout

```
~/.aceclaw/
├── memory/                              [GLOBAL]
│   ├── memory.key                       HMAC signing key
│   ├── global.jsonl                     Cross-project memories
│   ├── candidates.jsonl                 Learning candidates
│   ├── candidate-transitions.jsonl      State transition audit
│   └── archived.jsonl                   Pruned entries
│
├── index/                               [GLOBAL]
│   ├── tool_invocations.jsonl           Tool usage aggregates
│   ├── errors.jsonl                     Error occurrences
│   └── patterns.jsonl                   Recurring patterns
│
├── workspaces/{hash}/                   [WORKSPACE]
│   ├── workspace-path.txt               Human reference
│   └── memory/
│       ├── MEMORY.md                    Main workspace memory
│       ├── ACECLAW.md                   Promoted rules
│       ├── {topic}.md                   Topic files
│       └── journal/YYYY-MM-DD.md        Daily activity
│
├── history/                             [SESSION]
│   ├── {sessionId}.jsonl                Conversation history
│   └── {sessionId}.snapshot.json        Learning snapshot
│
├── sessions/                            [SESSION]
│   └── {sessionId}-{taskId}.checkpoint.json
│
├── checkpoints/                         [SESSION]
│   └── {planId}.checkpoint.json
│
├── skills/{name}/                       [GLOBAL — user skills]
│   ├── SKILL.md
│   ├── metrics.json
│   └── versions/{v}.SKILL.md
│
{projectRoot}/.aceclaw/
├── metrics/                             [WORKSPACE]
│   ├── learning-explanations.jsonl
│   ├── learning-validations.jsonl
│   └── learning-signal-reviews.jsonl
├── skills/{name}/                       [WORKSPACE — project skills]
└── skills-drafts/{name}/SKILL.md        [WORKSPACE — drafts]
```

## Follow-Up Code Tasks

1. **CandidateStore partitioning** — Consider per-workspace candidate files to eliminate global rewrite contention (#347-1)
2. **SkillMetricsStore race** — Concurrent sessions updating the same skill's `metrics.json` may race. Consider load-once-per-session with async persist (#347-2)
3. **AutoMemoryStore project file migration** — Legacy `project-{hashCode}.jsonl` format still co-exists with `WorkspacePaths`-based routing. Complete migration to workspace-scoped paths (#347-3)
