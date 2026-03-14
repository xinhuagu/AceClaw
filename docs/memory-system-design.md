# AceClaw Memory System Design

> Version 2.2 | 2026-03-14

## Architecture Overview

![Memory System Architecture](img/memory-architecture.png)

The AceClaw memory system enables **cross-session learning** through an 8-tier hierarchical memory model, HMAC-signed persistent storage, hybrid search, markdown memory files, path-based conditional rules, memory consolidation, and multiple automated extraction pipelines. The agent accumulates knowledge over time — mistakes to avoid, codebase patterns, user preferences, error recovery strategies — and applies them automatically in future sessions.

---

## 1. Design Principles

| Principle | Description |
|-----------|-------------|
| **Human controls policy, agent controls knowledge** | Tiers 1-5 are human-authored (policy, project rules, preferences, local overrides). Tiers 6-8 are agent-authored (learned insights, persistent notes, activity logs). |
| **Tamper detection, not encryption** | Memories are HMAC-SHA256 signed. Tampered entries are silently skipped on load. Users can inspect JSONL files freely. |
| **Workspace isolation** | Each project gets its own memory directory via SHA-256 hash. No cross-project leakage. |
| **Concise over exhaustive** | System prompt injection is capped. Auto-memory entries are 1-2 sentences. MEMORY.md injects first 200 lines. The journal caps at 500 lines/day. |
| **No LLM calls for basic ops** | Memory save, search, load, consolidation, session-end extraction, historical indexing, and trend detection are all pure-Java heuristic operations. Only context compaction and skill refinement use LLM calls. |
| **Self-maintaining** | Session close performs extraction and indexing immediately; heavier consolidation and historical maintenance run via the background learning maintenance scheduler. |

---

## 2. 8-Tier Memory Hierarchy

The memory system is organized as a **sealed interface hierarchy** (`MemoryTier`) with 8 tiers loaded in priority order:

```
Priority 100  ┌──────────────────────────┐  Immutable core identity
  (highest)   │   T1: Soul               │  SOUL.md
              ├──────────────────────────┤
Priority  90  │   T2: Managed Policy     │  Organization-managed (enterprise)
              ├──────────────────────────┤
Priority  80  │   T3: Workspace          │  {project}/ACECLAW.md
              ├──────────────────────────┤
Priority  70  │   T4: User Memory        │  ~/.aceclaw/ACECLAW.md
              ├──────────────────────────┤
Priority  65  │   T5: Local Memory       │  ACECLAW.local.md (gitignored)
              ├──────────────────────────┤
Priority  60  │   T6: Auto-Memory        │  JSONL + HMAC (agent-written)
              ├──────────────────────────┤
Priority  55  │   T7: Markdown Memory    │  MEMORY.md + topic files
              ├──────────────────────────┤
Priority  50  │   T8: Daily Journal      │  journal/YYYY-MM-DD.md
  (lowest)    └──────────────────────────┘  Append-only activity log
```

### Tier Details

| Tier | Source File | Scope | Author | Loaded |
|------|-----------|-------|--------|--------|
| **Soul** | `SOUL.md` (workspace `.aceclaw/` or global `~/.aceclaw/`) | Identity | Human | Always (if exists) |
| **Managed Policy** | `~/.aceclaw/managed-policy.md` | Organization | IT Admin | Always (if exists) |
| **Workspace Memory** | `{project}/ACECLAW.md` + `{project}/.aceclaw/ACECLAW.md` | Project team | Human | Always (if exists) |
| **User Memory** | `~/.aceclaw/ACECLAW.md` | Personal global | Human | Always (if exists) |
| **Local Memory** | `{project}/ACECLAW.local.md` | Per-developer | Human | Always (if exists, gitignored) |
| **Auto-Memory** | `~/.aceclaw/memory/{project-hash}.jsonl` + `global.jsonl` | Per-project | Agent | Always |
| **Markdown Memory** | `~/.aceclaw/workspaces/{hash}/memory/MEMORY.md` + topic files | Per-project | Agent | First 200 lines of MEMORY.md |
| **Daily Journal** | `~/.aceclaw/workspaces/{hash}/memory/journal/YYYY-MM-DD.md` | Per-project | Agent + System | Today + yesterday |

### Implementation: `MemoryTier.java`

```java
public sealed interface MemoryTier {
    String displayName();
    int priority();

    record Soul()            implements MemoryTier { ... }  // priority 100
    record ManagedPolicy()   implements MemoryTier { ... }  // priority 90
    record WorkspaceMemory() implements MemoryTier { ... }  // priority 80
    record UserMemory()      implements MemoryTier { ... }  // priority 70
    record LocalMemory()     implements MemoryTier { ... }  // priority 65
    record AutoMemory()      implements MemoryTier { ... }  // priority 60
    record MarkdownMemory()  implements MemoryTier { ... }  // priority 55
    record Journal()         implements MemoryTier { ... }  // priority 50
}
```

---

## 3. Core Components

### 3.1 AutoMemoryStore

The central memory persistence engine. Thread-safe via `CopyOnWriteArrayList`.

**Key operations:**
- `add(category, content, tags, source, global, projectPath)` — Create, sign, persist, and index a new entry
- `search(query, category, limit)` — Hybrid-ranked retrieval (delegates to `MemorySearchEngine`), increments access count
- `query(category, tags, limit)` — Filter-based retrieval (recency-ordered), increments access count
- `remove(id, projectPath)` — Delete with file rewrite
- `formatForPrompt(projectPath, maxEntries, queryHint)` — Format entries grouped by category for system prompt injection
- `forWorkspace(aceclawHome, workspacePath)` — Factory that initializes workspace-scoped store + `DailyJournal`
- `entries()` — Unmodifiable view for consolidator access
- `replaceEntries(newEntries, projectPath)` — Atomic replacement (used by consolidator)

**Access tracking:** Every call to `search()` or `query()` increments `accessCount` and sets `lastAccessedAt` on returned entries. This enables the consolidator to identify stale entries (high age, zero access) for pruning.

**Persistence format:** JSONL (one JSON object per line)

```json
{"id":"uuid","category":"MISTAKE","content":"Process.getInputStream() not inputStream()","tags":["java","api"],"createdAt":"2026-02-18T10:30:00Z","source":"tool:memory","hmac":"a3f2...","accessCount":3,"lastAccessedAt":"2026-02-18T12:00:00Z"}
```

### 3.2 MemoryEntry

Immutable Java record with 23 categories and access tracking:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryEntry(
    String id,                // UUID
    Category category,        // 23 enum values
    String content,           // Natural language insight (1-2 sentences)
    List<String> tags,        // Searchable tags (e.g. "gradle", "java")
    Instant createdAt,        // Creation timestamp
    String source,            // Origin (e.g. "tool:memory", "session-end:abc")
    String hmac,              // HMAC-SHA256 hex digest
    int accessCount,          // Times retrieved in search/prompt injection
    Instant lastAccessedAt    // Last time this entry was used
) {
    public String signablePayload() {
        // Note: accessCount and lastAccessedAt intentionally EXCLUDED
        // These mutable fields change after signing; including them would
        // break HMAC verification on subsequent loads.
        return id + "|" + category + "|" + content + "|" +
               String.join(",", tags) + "|" + createdAt + "|" + source;
    }

    public MemoryEntry withAccess() {
        return new MemoryEntry(id, category, content, tags, createdAt, source,
                hmac, accessCount + 1, Instant.now());
    }
}
```

**23 Categories:**

| Category | Purpose | Example |
|----------|---------|---------|
| `MISTAKE` | Bugs/errors to avoid | "Process.getInputStream() not inputStream()" |
| `PATTERN` | Recurring code conventions | "Project uses records for all DTOs" |
| `PREFERENCE` | User's explicit preferences | "Always use Java 21 features" |
| `CODEBASE_INSIGHT` | Structural knowledge | "Auth module is in src/auth/, uses JWT" |
| `STRATEGY` | Approaches that worked/failed | "Run tests after every edit to catch regressions" |
| `WORKFLOW` | Multi-step processes | "Deploy: build -> test -> push -> tag" |
| `ENVIRONMENT` | Environment-specific config | "CI uses JDK 21.0.2 on Ubuntu 22.04" |
| `RELATIONSHIP` | Component/module relationships | "aceclaw-tools depends on aceclaw-memory" |
| `TERMINOLOGY` | Domain abbreviations | "BOM = Bill of Materials (Gradle platform)" |
| `CONSTRAINT` | Explicit limitations | "Never commit files under research/" |
| `DECISION` | Design rationale | "No framework - plain Java for min startup time" |
| `TOOL_USAGE` | Tool quirks/best practices | "Glob max depth is 20, max results 200" |
| `COMMUNICATION` | Communication preferences | "User prefers Chinese for conversations" |
| `CONTEXT` | Carried-forward context | "Working on P2 multi-provider feature" |
| `CORRECTION` | User corrections | "Use ConcurrentHashMap, not HashMap" |
| `BOOKMARK` | Quick references | "Key config file: ~/.aceclaw/config.json" |
| `SESSION_SUMMARY` | Session key actions/outcomes | "Implemented 8-tier memory system in 11 tasks" |
| `ERROR_RECOVERY` | Error + resolution | "Jackson readTree('') returns null, check isObject()" |
| `SUCCESSFUL_STRATEGY` | Proven strategies | "3-pass consolidation: dedup, merge, prune" |
| `ANTI_PATTERN` | Approaches to avoid | "Don't include mutable fields in HMAC payload" |
| `USER_FEEDBACK` | User feedback on agent | "User liked the evaluation summary format" |
| `RECOVERY_RECIPE` | Multi-step recovery procedure | "Recovery recipe for 'encoding error': detect encoding → re-read with UTF-8" |
| `FAILURE_SIGNAL` | Normalized runtime failure signal | "permission_denied source=permission-gate tool=bash" |

### 3.3 MemorySearchEngine

Hybrid ranking combining three signals:

```
Final Score = 0.50 * TF-IDF + 0.35 * Recency + 0.15 * Frequency
```

| Signal | Weight | Algorithm | Purpose |
|--------|--------|-----------|---------|
| **TF-IDF** | 0.50 | `log(1+tf) * log(N/df)` per query token, normalized | Content relevance |
| **Recency** | 0.35 | `2^(-age_days / 7.0)` exponential decay, 7-day half-life | Freshness |
| **Frequency** | 0.15 | `log(1 + matchingTagCount)` | Tag relevance boost |

**Tokenization:** Simple whitespace + punctuation split, lowercase, min 2 chars.

### 3.4 MemorySigner

HMAC-SHA256 signing with per-installation secret key.

- **Key:** 32-byte random secret at `~/.aceclaw/memory/memory.key` (POSIX 600 permissions)
- **Signing:** `HMAC-SHA256(id|category|content|tags|createdAt|source)` — mutable fields (accessCount, lastAccessedAt) intentionally excluded
- **Verification:** Constant-time comparison via `MessageDigest.isEqual()` (prevents timing attacks)
- **On tamper:** Entry silently skipped during load, warning logged

### 3.5 WorkspacePaths

Workspace isolation via SHA-256 hashing:

```
Input:  /Users/xinhua.gu/Documents/project/github/Chelava
Hash:   SHA-256 → first 12 hex chars → "a1b2c3d4e5f6"
Output: ~/.aceclaw/workspaces/a1b2c3d4e5f6/memory/
```

- **Marker file:** `workspace-path.txt` records original path for human reference
- **Auto-migration:** Old format (`project-{hashCode}.jsonl`) auto-migrated to new layout on first access

### 3.6 DailyJournal

Append-only daily activity log stored as markdown:

```
~/.aceclaw/workspaces/{hash}/memory/journal/2026-02-18.md
```

- **Format:** `- [2026-02-18T10:30:00Z] Session abc12345 ended: 15 messages, 3 memories extracted`
- **Cap:** 500 lines per file (prevents unbounded growth)
- **Loading window:** Today + yesterday (2-day sliding window injected into system prompt)
- **Writers:** Compaction events, session-end events, turn-level logging

### 3.7 MarkdownMemoryStore

Manages persistent markdown memory files per workspace — inspired by Claude Code's auto memory directory pattern.

**Storage layout:**
```
~/.aceclaw/workspaces/{hash}/memory/
  MEMORY.md          — always injected into system prompt (first 200 lines)
  debugging.md       — topic file (agent reads on demand)
  patterns.md        — topic file
  ...
```

**Key methods:**
- `loadMemoryMd()` — reads MEMORY.md, returns first 200 lines for prompt injection
- `writeMemoryMd(content)` — writes full MEMORY.md content (agent-controlled)
- `readTopicFile(name)` — reads a topic file by name
- `writeTopicFile(name, content)` — writes a topic file
- `listTopicFiles()` — lists available topic files

**Safeguards:**
- File size limit: 50KB per file
- Total size limit: 500KB per workspace
- File name validation: prevents path traversal (no `/`, `\`, `..`)
- Agent writes MEMORY.md + topic files via standard file tools
- Only the first 200 lines of MEMORY.md are injected into the system prompt (longer files are accessible via the file read tool)

### 3.8 PathBasedRule + RuleEngine

Conditional rules triggered by file glob patterns. Rules are loaded from `{project}/.aceclaw/rules/*.md`.

**Rule file format:**
```markdown
---
paths:
  - "*.test.java"
  - "**/*Test.java"
---

# Test conventions

- Always use JUnit 5 @Test annotation
- Use AssertJ for assertions (not JUnit built-in)
```

**PathBasedRule record:**
```java
public record PathBasedRule(
    String name,            // rule file name (e.g. "test-conventions")
    List<String> patterns,  // glob patterns (e.g. ["*.test.java", "**/*Test.java"])
    String content          // markdown rule content
) {}
```

**RuleEngine key methods:**
- `loadRules(projectPath)` — scans `{project}/.aceclaw/rules/*.md`, parses YAML frontmatter
- `matchRules(filePath)` — returns all rules whose glob patterns match a given file path
- `formatForPrompt(filePaths)` — formats matching rules for system prompt injection
- `rules()` — returns all loaded rules (unmodifiable list)

**Implementation details:**
- Uses Java's `PathMatcher` with glob syntax (from `java.nio.file.FileSystems`)
- Supports both list syntax (`paths:\n  - "*.java"`) and inline syntax (`paths: ["*.ts", "*.tsx"]`)
- Simple YAML frontmatter parser (no external YAML library dependency)
- Rules loaded at session start and injected into system prompt by `SystemPromptLoader`

### 3.9 MemoryConsolidator

Periodic memory maintenance: dedup, merge similar, prune low-relevance.

**3-pass consolidation:**

| Pass | Algorithm | Action |
|------|-----------|--------|
| **1. Exact dedup** | Group by `content + category` key | Remove duplicates, keep newest |
| **2. Similarity merge** | Jaccard coefficient on tokenized content >0.80 within same category | Merge into one entry, combine tags |
| **3. Age prune** | Entries older than 90 days AND `accessCount == 0` | Archive to `archived.jsonl` (not deleted) |

**Key methods:**
- `consolidate(AutoMemoryStore, Path projectPath, Path archiveDir)` — runs all 3 passes
- Returns `ConsolidationResult` record with counts: `{deduped, merged, pruned, hasChanges()}`

**Trigger:** Invoked by the deferred learning maintenance scheduler after session-close extraction and indexing. Runs on a background virtual thread.

**Null-safe:** If `archiveDir` is null, prune pass skips archiving but still removes entries.

### 3.10 MemoryTierLoader

Central orchestrator that discovers, loads, and assembles all 8 tiers:

```java
// Loading
LoadResult result = MemoryTierLoader.loadAll(
    aceclawHome, workspacePath, memoryStore, journal, markdownStore);

// Assembly for system prompt
String memorySection = MemoryTierLoader.assembleForSystemPrompt(
    result, memoryStore, workspacePath, 50);
```

**SOUL.md resolution:** Workspace `.aceclaw/SOUL.md` takes precedence over global `~/.aceclaw/SOUL.md`.

**New tiers in assembly:**
- `LocalMemory` → injected with header "# Local Memory (Per-Developer)"
- `MarkdownMemory` → injected with header "# Persistent Memory Notes"

---

## 4. Memory Write Pipelines

There are **five independent pipelines** that write memories:

### 4.1 Agent Active Memory (MemoryTool)

The agent explicitly saves memories during conversations using the built-in `memory` tool.

```
User corrects agent → Agent calls memory tool → save as CORRECTION
Agent discovers pattern → Agent calls memory tool → save as PATTERN
Agent resolves error → Agent calls memory tool → save as ERROR_RECOVERY
```

**Tool interface:** `memory(action, content, category, tags, query, limit, global)`

| Action | Description |
|--------|-------------|
| `save` | Store a new memory (requires content + category) |
| `search` | Find relevant memories via hybrid search (requires query) |
| `list` | Browse memories, optionally filtered by category |

**System prompt guidance** tells the agent when to save (corrections, patterns, mistakes, preferences, strategies, error recoveries, anti-patterns, user feedback) and when NOT to save (trivial info, every turn, raw file contents).

### 4.2 Session-End Extraction (SessionEndExtractor)

When a session is destroyed, heuristic patterns extract memories from conversation history. **No LLM calls.**

**Extraction patterns:**

| Type | Trigger | Regex Patterns |
|------|---------|---------------|
| **Corrections** | User corrects after assistant message | `^no[,.]\\s`, `should be`, `instead of`, `wrong`, `not X, use Y`, `actually`, `don't use`, `that's incorrect` |
| **Preferences** | User states preferences | `always`, `never`, `prefer`, `don't`, `make sure`, `I like`, `I want` |
| **Modified files** | 3+ file modifications in session | Matches `write_file`, `edit_file` tool results |
| **Error recovery** | Assistant describes a fix | `fixed by`, `resolved by`, `the issue was`, `the fix was`, `root cause was`, `solved by`, `workaround:` |
| **Successful strategy** | Assistant reports success | `that worked`, `successfully`, `problem solved`, `working now`, `fixed the issue`, `build passed` |
| **User feedback** | User gives explicit feedback | Positive: `good`, `perfect`, `great`, `nice`, `excellent`, `well done`; Negative: `bad`, `that's wrong`, `incorrect`, `not what I`, `don't do that` |

**Deduplication:** Content-based HashSet prevents duplicate extractions.
**Truncation:** Content capped at 200 characters.

### 4.3 Context Compaction Memory Flush (MessageCompactor Phase 0)

When the context window hits 85% capacity, the 3-phase compaction process begins. Phase 0 extracts key items before pruning:

| Extracted Item | Source | Example |
|---------------|--------|---------|
| Modified files | `write_file`, `edit_file` tool uses | "Modified file: /src/Foo.java" |
| Bash commands | `bash` tool uses (>10 chars, not cd/ls) | "Executed: ./gradlew clean build" |
| Errors | `ToolResult` with `isError=true` | "Error encountered: NullPointerException..." |

These items flow to the `StreamingAgentHandler` which persists them via `AutoMemoryStore.add()`.

### 4.4 Memory Consolidation (MemoryConsolidator)

After session-close extraction and indexing complete, the consolidator runs as part of deferred learning maintenance:

1. **Exact dedup** — Removes entries with identical content+category (keeps newest)
2. **Similarity merge** — Entries with >80% Jaccard token overlap in same category → merged, tags combined
3. **Age prune** — Entries >90 days old with 0 access count → archived to `archived.jsonl`

This prevents memory pollution from accumulating duplicate or stale entries over many sessions without slowing down session teardown.

### 4.5 Correction Rule Promotion (CorrectionRulePromoter)

When the agent makes the same mistake 2+ times across sessions, `CorrectionRulePromoter` automatically elevates those corrections from Tier 6 (auto-memory JSONL) to Tier 3 (workspace `.aceclaw/ACECLAW.md`). This closes the self-learning loop — repeated corrections become permanent rules visible in every system prompt.

- **Trigger:** Runs as step 2 of the deferred learning maintenance pipeline
- **Input:** `CORRECTION` and `MISTAKE` category entries from `AutoMemoryStore`
- **Grouping:** Jaccard token similarity >= 0.50 (looser than consolidation's 0.80)
- **Threshold:** 2+ similar entries required before promotion
- **Dedup:** SHA-256 fingerprint comments in ACECLAW.md prevent duplicate rules across runs
- **Output:** Appends rules under `## Auto-Promoted Rules` section in `.aceclaw/ACECLAW.md`

---

## 5. Memory Read Pipeline

### 5.1 System Prompt Injection (Boot Time)

On session start, `SystemPromptLoader` assembles the full system prompt:

```
base prompt (system-prompt.md)
  + environment context (OS, date, JDK, working dir)
  + git context (branch, status, recent commits)
  + 8-tier memory hierarchy (via MemoryTierLoader)
  + path-based rules (via RuleEngine)
  + model identity (provider, model name)
```

Memory tiers are injected as markdown sections:

```markdown
# Soul (Core Identity)
...

# Project Instructions
...

# Local Memory (Per-Developer)
...

# Auto-Memory
## Mistakes to Avoid
- Process.getInputStream() not inputStream() [java, api]

## Code Patterns
- Project uses records for all DTOs [java, conventions]

## Error Recoveries
- Jackson readTree("") returns null in 2.18.2 — check isObject() [jackson, api]

# Persistent Memory Notes
## AceClaw Project Memory
...

# Daily Journal
- [2026-02-18T08:00:00Z] Session started, 3 files modified

# Path-Based Rules
## Test Conventions
Applies to: *.test.java, **/*Test.java
- Always use JUnit 5 @Test annotation
```

### 5.2 Agent-Initiated Search (Runtime)

During a conversation, the agent can search memories using the `memory` tool:

```
Agent encounters unfamiliar error → memory(action="search", query="gradle build error") → ranked results
```

Each search increments `accessCount` on returned entries, informing the consolidator about entry usefulness.

### 5.3 Per-Turn Journal Logging

The `StreamingAgentHandler` appends to the daily journal after each agent turn:

```
- [2026-02-18T10:30:00Z] Turn 3: read_file, edit_file, bash (3 tools, model: claude-3-5-sonnet)
```

### 5.4 Markdown Memory Files

The agent can read and write persistent markdown files in the workspace memory directory:

- `MEMORY.md` — First 200 lines auto-injected into system prompt on every session start
- Topic files (`debugging.md`, `patterns.md`, etc.) — Read on demand via file tools

This complements auto-memory by providing a human-readable, editable knowledge base that persists across sessions.

---

## 6. Storage Layout

```
~/.aceclaw/
  SOUL.md                              # T1: Core identity (global)
  ACECLAW.md                           # T4: User preferences (global)
  managed-policy.md                    # T2: Organization policy (enterprise)
  memory/
    memory.key                         # 32-byte HMAC secret (POSIX 600)
    global.jsonl                       # Cross-project memories
    project-{hash}.jsonl               # Per-project memories (legacy format)
  workspaces/
    {sha256-12chars}/
      workspace-path.txt               # Human-readable path marker
      memory/
        MEMORY.md                      # T7: Persistent markdown memory
        debugging.md                   # Topic file (agent-managed)
        patterns.md                    # Topic file (agent-managed)
        project.jsonl                  # Per-project memories (new format)
        archived.jsonl                 # Pruned entries (from consolidator)
        journal/
          2026-02-17.md                # Yesterday's journal
          2026-02-18.md                # Today's journal

{project}/
  ACECLAW.md                           # T3: Project instructions
  ACECLAW.local.md                     # T5: Per-developer overrides (gitignored)
  .aceclaw/
    ACECLAW.md                         # T3: Additional project instructions
    SOUL.md                            # T1: Project-specific identity override
    config.json                        # Project config override
    rules/                             # Path-based conditional rules
      test-conventions.md              # Example rule for test files
      api-guidelines.md                # Example rule for API files
```

---

## 7. Security Model

| Threat | Mitigation |
|--------|-----------|
| **Memory tampering** | HMAC-SHA256 signature on every entry. Tampered entries silently skipped. |
| **Timing attack on HMAC** | `MessageDigest.isEqual()` constant-time comparison |
| **Key file exposure** | POSIX 600 permissions (owner read/write only) |
| **Cross-project leakage** | SHA-256 workspace hash isolation. Separate JSONL files per project. |
| **Unbounded growth** | Journal: 500 lines/day cap. MEMORY.md: 50KB/file, 500KB/workspace. Consolidator: dedup + merge + prune. System prompt budget: 150K chars total cap with priority-based truncation. |
| **Injection via memory content** | Memories are plain text, not executable. Loaded into system prompt as markdown. |
| **Stale memory pollution** | Consolidator prunes entries >90 days with 0 access. Similarity merge prevents near-duplicates. |
| **Path traversal in rules** | RuleEngine validates file names (no `/`, `\`, `..`). PathMatcher scoped to project directory. |
| **Path traversal in markdown memory** | MarkdownMemoryStore validates topic file names. Prevents `../../../etc/passwd` attacks. |
| **HMAC payload stability** | Mutable fields (accessCount, lastAccessedAt) excluded from signable payload to prevent verification failure after tracking. |

---

## 8. System Prompt Size Budget

The system prompt budget is implemented today. AceClaw assembles memory tiers, path-based rules, environment context, tool guidance, and runtime learning sections, then applies a character budget before the request is sent.

### 8.1 Current Budget Model

`SystemPromptBudget` provides two caps:

```text
maxPerTierChars = 20,000
maxTotalChars   = 150,000
```

For smaller context windows, `SystemPromptBudget.forContextWindow(...)` scales the budget down so the full system prompt does not consume an excessive share of the model window.

### 8.2 Truncation Strategy

When a tier exceeds its cap, or the total assembled prompt exceeds the global cap, `TierTruncator` applies priority-aware truncation:

- lower-priority tiers are truncated before higher-priority tiers
- truncation keeps a 70/20/10 split of head / tail / marker
- `Soul` and `Managed Policy` remain the hardest tiers to displace

This is an intentionally simple character-budget model. AceClaw does not require exact token accounting for every tier before assembly.

### 8.3 Context-Aware Compaction Boundary

`MessageCompactor` manages conversation history, not tier assembly, but the two systems are linked by the effective window calculation. `CompactionConfig` deducts output budget and can also account for system-prompt size when the caller passes it in, so compaction does not assume the entire model window is available for conversation history.

---

## 9. AceClaw vs OpenClaw

This section compares AceClaw's current memory-and-learning design with OpenClaw's currently documented default path. It is not a claim that OpenClaw cannot support richer behavior through plugins or alternative context engines.

### 9.1 High-Level Difference

![Architecture Comparison](img/architecture_comparison_openclaw_vs_aceclaw_1.drawio.png)

OpenClaw is stronger as a broader context and retrieval product. AceClaw is stronger as a behavior-centric learning kernel.

In practice:
- OpenClaw emphasizes explicit memory, retrieval, context observability, and platform breadth.
- AceClaw emphasizes governed memory, behavior-derived insights, rule promotion, and long-running learning loops.

### 9.2 Comparison Table

| Dimension | AceClaw today | OpenClaw documented core path |
|-----------|---------------|-------------------------------|
| **Primary focus** | Learn from runtime behavior and feed it back into future runs | Persist, retrieve, and inspect durable memory/context |
| **Memory model** | 8-tier hierarchy with policy vs learned-memory separation | Disk-backed memory plus platform context surfaces |
| **Persistence** | JSONL auto-memory + HMAC, `MEMORY.md`, journal, rules | Markdown memory, session transcripts, retrieval index |
| **Retrieval** | Hybrid search plus tiered prompt injection | Mature hybrid retrieval and operator-facing memory tools |
| **Context observability** | Limited today; stronger internal structure than external surface | Strong operator surface (`/context`, `/status`, compaction visibility) |
| **Learning loop** | Per-turn detectors, session-close extraction, deferred maintenance | Default path is more memory-centric than behavior-centric |
| **Governance** | Confidence thresholds, consolidation, candidate bridge, rule promotion, validation/rollback around adaptive skills | Strong context/runtime product controls; learning governance depends more on extensions |
| **Platform breadth** | Local daemon + CLI + coding-first workflow | Wider channels, broader platform surface, larger ecosystem |

### 9.3 Where AceClaw is Stronger

AceClaw is stronger in the places that matter most for long-running task execution:

1. **Behavior-derived memory** — learnings come from failures, recoveries, repeated sequences, and user corrections, not only from explicit note writing.
2. **Governed promotion** — confidence thresholds, consolidation, correction-rule promotion, candidate bridging, and skill validation reduce the chance of learning raw noise.
3. **Policy separation** — human-authored tiers stay separate from agent-authored tiers, which keeps learning subordinate to operator intent.

### 9.4 Where OpenClaw is Stronger

OpenClaw is still stronger in several context-engineering areas:

1. **Retrieval maturity** — its documented default path is richer in retrieval tooling and operator-facing memory inspection.
2. **Context observability** — OpenClaw exposes a clearer product surface for prompt composition and compaction behavior.
3. **Platform surface** — it reaches more channels and product scenarios than AceClaw currently does.

### 9.5 The Design Tradeoff

AceClaw is intentionally narrower. It treats memory as one layer of a larger long-running learning system:

```text
behavior -> typed insight -> persisted memory -> promoted rule/candidate -> future run
```

OpenClaw's documented default path is closer to:

```text
write memory -> index memory -> retrieve memory -> reuse memory
```

Both are valid. AceClaw's design matters if the goal is not just to remember more, but to improve behavior over time under governance.

---

## 10. Class Dependency Graph

```
MemoryTier (sealed interface)
  ├── Soul, ManagedPolicy, WorkspaceMemory, UserMemory,
  │   LocalMemory, AutoMemory, MarkdownMemory, Journal

MemoryEntry (record)
  └── Category (enum, 23 values)
  └── accessCount, lastAccessedAt (mutable tracking, excluded from HMAC)

MemorySigner
  └── HMAC-SHA256 sign/verify

WorkspacePaths
  └── SHA-256 hash, directory resolution, migration

DailyJournal
  └── Append-only markdown, 500-line cap

MemorySearchEngine
  └── Hybrid TF-IDF + recency + frequency ranking

MarkdownMemoryStore                    ← NEW
  └── MEMORY.md + topic files, size limits, path validation

PathBasedRule (record)                 ← NEW
  └── name, patterns (globs), content

RuleEngine                             ← NEW
  ├── loadRules(projectPath) — YAML frontmatter parser
  ├── matchRules(filePath) — PathMatcher glob matching
  └── formatForPrompt(filePaths) — rule content assembly

MemoryConsolidator                     ← NEW
  ├── Pass 1: exact dedup (content+category key)
  ├── Pass 2: similarity merge (Jaccard > 0.80)
  └── Pass 3: age prune (>90 days, accessCount=0 → archive)

AutoMemoryStore
  ├── uses MemorySigner (sign on add, verify on load)
  ├── uses MemorySearchEngine (hybrid search)
  ├── uses WorkspacePaths (workspace isolation)
  ├── contains DailyJournal (via forWorkspace factory)
  ├── trackAccess() on search/query results
  └── stores List<MemoryEntry> (CopyOnWriteArrayList)

MemoryTierLoader
  ├── uses AutoMemoryStore (Tier 6 content)
  ├── uses MarkdownMemoryStore (Tier 7 content)
  ├── uses DailyJournal (Tier 8 content)
  └── produces LoadResult → assembleForSystemPrompt()

--- Daemon layer (consumers) ---

SystemPromptLoader
  ├── uses MemoryTierLoader (8-tier assembly)
  └── uses RuleEngine (path-based rules)

MemoryTool (aceclaw-tools)
  └── uses AutoMemoryStore (23 categories)

SessionEndExtractor (aceclaw-daemon)
  └── produces entries → AutoMemoryStore.add()
  └── 6 extraction types (corrections, preferences, files, errors, strategies, feedback)

MemoryConsolidator (aceclaw-memory)
  └── runs in deferred learning maintenance → dedup/merge/prune

CorrectionRulePromoter (aceclaw-memory)
  ├── scans AutoMemoryStore for CORRECTION/MISTAKE entries
  ├── groups by Jaccard similarity (>= 0.50)
  ├── generates rules for groups with 2+ occurrences
  └── appends to .aceclaw/ACECLAW.md (Tier 6 → Tier 3 promotion)

MessageCompactor (aceclaw-core)
  └── Phase 0 extractContextItems → persisted via StreamingAgentHandler
```

---

## 11. Configuration

| Config Key | Default | Description |
|-----------|---------|-------------|
| `contextWindowTokens` | 200,000 | Context window size for compaction trigger |
| `maxTokens` | 16,384 | Max output tokens |
| `thinkingBudget` | 10,240 | Extended thinking budget |
| Compaction threshold | 85% | Trigger compaction at this % of effective window |
| Prune target | 60% | Target after Phase 1 pruning |
| Protected turns | 5 | Recent turns protected from pruning |
| Journal max lines | 500 | Max lines per daily journal file |
| MEMORY.md injection | 200 lines | First N lines of MEMORY.md injected into prompt |
| Markdown file size | 50 KB | Max size per markdown memory file |
| Markdown total size | 500 KB | Max total size per workspace |
| Consolidation dedup key | content+category | Exact match dedup |
| Consolidation similarity | 0.80 (Jaccard) | Merge threshold for similar entries |
| Consolidation age prune | 90 days | Prune entries older than this with 0 access |
| Search default limit | 10 | Default results for memory search |
| List default limit | 20 | Default results for memory list |
| Max limit | 50 | Maximum results for any query |
| Auto-memory max entries | 50 | Max entries injected into system prompt |
| **System prompt per-tier cap** | **20,000 chars** | **Max chars per memory tier** |
| **System prompt total cap** | **150,000 chars** | **Max total chars for all tiers + base prompt** |
| **Truncation split** | **70/20/10** | **Head/tail/marker ratio when truncating a tier** |

---

## 12. Future Roadmap

| Phase | Feature | Status |
|-------|---------|--------|
| P1 | 8-tier hierarchy, HMAC signing, hybrid search | **Done** |
| P1 | Agent memory tool (23 categories), session-end extraction (6 types), journal | **Done** |
| P1 | Context compaction with memory flush | **Done** |
| P1 | MarkdownMemoryStore (MEMORY.md + topic files) | **Done** |
| P1 | PathBasedRule + RuleEngine (conditional rules per file type) | **Done** |
| P1 | MemoryConsolidator (dedup + merge + prune) | **Done** |
| P1 | Access tracking (accessCount, lastAccessedAt) | **Done** |
| P1 | Local Memory tier (ACECLAW.local.md, gitignored) | **Done** |
| P1 | System prompt budget (150K char cap, per-tier 20K cap, 70/20/10 truncation) | **Done** |
| P1 | Context-aware effective window / prompt-budget-aware compaction | **Done** |
| P2 | Dynamic rule matching during tool execution (per-file injection) | Planned |
| P2 | CLAUDE.md import system (`@path/to/file`) | Planned |
| P3 | Skill-based memory (skills remember their own patterns) | Planned |
| P3 | Memory Tool API-level (persistent files like Claude API) | Planned |
| P3 | Community memory marketplace (secured ClawHub-like) | Planned |
