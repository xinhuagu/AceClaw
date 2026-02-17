# AceClaw Self-Learning Architecture

> System Architecture Document — Memory, Tools, Skills, and Self-Improvement
> Author: System Architect | Date: 2026-02-17

---

## Table of Contents

1. [Design Philosophy](#1-design-philosophy)
2. [Memory Architecture](#2-memory-architecture)
3. [Tool Plugin Architecture](#3-tool-plugin-architecture)
4. [Skills System](#4-skills-system)
5. [Self-Improvement Loop](#5-self-improvement-loop)
6. [Module Assignments](#6-module-assignments)
7. [Interface Definitions](#7-interface-definitions)
8. [Data Flow Diagrams](#8-data-flow-diagrams)
9. [Implementation Roadmap](#9-implementation-roadmap)

---

## 1. Design Philosophy

### 1.1 Core Principles

AceClaw is a **general-purpose autonomous agent**, not a coding-specific tool. The self-learning architecture must support any domain: software engineering, data analysis, research, DevOps, writing, or novel tasks the agent has never encountered.

| Principle | Description |
|-----------|-------------|
| **Memory-backed persistence** | Every insight survives session boundaries via HMAC-signed storage |
| **Tool composability** | Native tools, MCP tools, and skill-generated tools share a single registry |
| **Skill autonomy** | Agent discovers, composes, and generates skills without human intervention |
| **Sealed type safety** | All type hierarchies use Java 21 sealed interfaces with exhaustive pattern matching |
| **Virtual thread concurrency** | All I/O-bound operations (MCP calls, LLM summarization) run on virtual threads |
| **Zero-framework** | Plain Java 21 — no Spring, no Quarkus — for minimal binary size and fast startup |

### 1.2 Evolution from Current State

```
Current AceClaw (71+ classes, BUILD SUCCESSFUL)
  ├── AutoMemoryStore (JSONL + HMAC, flat category enum)
  ├── ToolRegistry (ConcurrentHashMap<String, Tool>)
  ├── McpClientManager (stdio transport, tool discovery)
  ├── StreamingAgentLoop (ReAct, 25 iterations, StructuredTaskScope)
  └── MessageCompactor (3-phase: flush → prune → summarize)

Target AceClaw (self-learning)
  ├── Multi-tier Memory System (working/episodic/semantic/procedural)
  ├── Unified Tool Registry (native + MCP + skill-generated, hot-loadable)
  ├── Skills System (SKILL.md, discovery, composition, lazy loading)
  ├── Self-Improvement Loop (pattern detection, strategy refinement, memory-backed)
  └── Sub-Agent Architecture (Task tool, agent types, no-nesting)
```

---

## 2. Memory Architecture

### 2.1 Four-Type Memory Model

Inspired by cognitive science and validated by Claude Code's 6-tier hierarchy, AceClaw's memory system uses four distinct memory types, each with different persistence, retrieval, and lifecycle characteristics.

```
+------------------------------------------------------------------+
|                    MEMORY TYPE HIERARCHY                           |
+------------------------------------------------------------------+
|                                                                    |
|  WORKING MEMORY (in-session, volatile)                            |
|  ┌──────────────────────────────────────────────────────────┐    |
|  │  ConversationHistory (LLM Message list)                   │    |
|  │  ActiveGoals (current task decomposition)                 │    |
|  │  ToolResultCache (recent tool outputs, prunable)          │    |
|  │  ThinkingTrace (extended thinking blocks, prunable)       │    |
|  └──────────────────────────────────────────────────────────┘    |
|                                                                    |
|  EPISODIC MEMORY (cross-session, per-project)                     |
|  ┌──────────────────────────────────────────────────────────┐    |
|  │  SessionSummary (compaction output from each session)     │    |
|  │  ErrorEncounters (what went wrong + resolution)           │    |
|  │  SuccessfulStrategies (approaches that worked)            │    |
|  │  UserFeedback (explicit corrections from user)            │    |
|  └──────────────────────────────────────────────────────────┘    |
|                                                                    |
|  SEMANTIC MEMORY (persistent, factual knowledge)                  |
|  ┌──────────────────────────────────────────────────────────┐    |
|  │  ProjectInsight (architecture, key files, conventions)    │    |
|  │  DomainKnowledge (API patterns, framework details)        │    |
|  │  UserPreference (code style, communication style)         │    |
|  │  EnvironmentFact (build tool, test framework, OS)         │    |
|  └──────────────────────────────────────────────────────────┘    |
|                                                                    |
|  PROCEDURAL MEMORY (persistent, learned behaviors)                |
|  ┌──────────────────────────────────────────────────────────┐    |
|  │  SkillDefinition (SKILL.md files, reusable procedures)    │    |
|  │  ToolUsagePattern (which tools work for which tasks)      │    |
|  │  RecoveryStrategy (error → fix mappings)                  │    |
|  │  AntiPattern (things to avoid, with context)              │    |
|  └──────────────────────────────────────────────────────────┘    |
|                                                                    |
+------------------------------------------------------------------+
```

### 2.2 Memory Tier Hierarchy (Configuration Files)

Following Claude Code's 6-tier model, adapted for AceClaw:

```
TIER 1: Managed Policy (Organization-wide)
  Location: /etc/aceclaw/ACECLAW.md (Linux) or managed-config directory
  Loaded: Always at session start
  Shared: All users in organization
  Mutable: Admin only

TIER 2: Project Memory (Team-shared)
  Location: {project}/ACECLAW.md or {project}/.aceclaw/ACECLAW.md
  Loaded: Always at session start
  Shared: Team via source control

TIER 3: Project Rules (Modular, conditional)
  Location: {project}/.aceclaw/rules/*.md
  Loaded: Always, but path-based rules are conditional
  Shared: Team via source control

TIER 4: User Memory (Personal global)
  Location: ~/.aceclaw/ACECLAW.md
  Loaded: Always at session start
  Shared: Just you (all projects)

TIER 5: Project Local Memory (Personal, project-specific)
  Location: {project}/ACECLAW.local.md (auto-gitignored)
  Loaded: Always at session start
  Shared: Just you (current project)

TIER 6: Auto Memory (Agent's own notes)
  Location: ~/.aceclaw/projects/{project-hash}/memory/
  Loaded: MEMORY.md first 200 lines into system prompt
  Shared: Just you (per project)
```

### 2.3 Evolving AutoMemoryStore

The existing `AutoMemoryStore` (JSONL + HMAC) becomes the persistence backend for all memory types. The key evolution:

```
Current MemoryEntry categories:
  MISTAKE, PATTERN, PREFERENCE, CODEBASE_INSIGHT, STRATEGY

New MemoryEntry categories (expanded):
  // Episodic
  SESSION_SUMMARY, ERROR_ENCOUNTER, SUCCESSFUL_STRATEGY, USER_FEEDBACK,
  // Semantic
  PROJECT_INSIGHT, DOMAIN_KNOWLEDGE, USER_PREFERENCE, ENVIRONMENT_FACT,
  // Procedural
  TOOL_USAGE_PATTERN, RECOVERY_STRATEGY, ANTI_PATTERN
```

### 2.4 Hybrid Search

The current `AutoMemoryStore.query()` uses exact category + tag matching. For self-learning, we need hybrid search combining:

1. **Tag-based filtering** (existing) — fast, deterministic
2. **Recency weighting** — newer memories score higher
3. **Frequency boosting** — memories accessed more often score higher
4. **Relevance scoring** — TF-IDF over content + tags vs. query terms

```
+------------------------------------------------------------+
|                  MEMORY RETRIEVAL PIPELINE                   |
+------------------------------------------------------------+
|                                                              |
|  Query: "How to fix Gradle build?"                          |
|       │                                                      |
|       ▼                                                      |
|  1. TAG FILTER (cheap, O(n) scan)                           |
|     tags ∩ {"gradle", "build"} → candidate set              |
|       │                                                      |
|       ▼                                                      |
|  2. CATEGORY FILTER (optional)                              |
|     category in {RECOVERY_STRATEGY, ERROR_ENCOUNTER} →      |
|     narrowed set                                             |
|       │                                                      |
|       ▼                                                      |
|  3. RELEVANCE SCORE (TF-IDF over content)                   |
|     score = tf(term, doc) * idf(term, corpus)               |
|       │                                                      |
|       ▼                                                      |
|  4. RECENCY WEIGHT                                          |
|     score *= decay(age_hours, half_life=168)                |
|       │                                                      |
|       ▼                                                      |
|  5. ACCESS FREQUENCY BOOST                                  |
|     score *= log(1 + accessCount)                           |
|       │                                                      |
|       ▼                                                      |
|  6. TOP-K SELECTION                                         |
|     return top K entries by composite score                  |
|                                                              |
+------------------------------------------------------------+
```

### 2.5 Memory Integrity

The existing `MemorySigner` (HMAC-SHA256) is retained for all memory entries. Additional protections:

- **Per-installation secret key** (`~/.aceclaw/memory/memory.key`, 32 bytes)
- **Tamper detection on load**: Invalid HMAC → entry skipped with warning
- **Access tracking**: `accessCount` and `lastAccessedAt` fields added to `MemoryEntry`
- **TTL-based expiry**: Configurable per category (e.g., `SESSION_SUMMARY` expires after 30 days)

### 2.6 ACECLAW.md and Rules System

```java
// New: MemoryTier sealed interface
public sealed interface MemoryTier permits
    ManagedPolicy, ProjectMemory, ProjectRules, UserMemory,
    ProjectLocalMemory, AutoMemory {

    String content();
    Path source();
    int priority();  // 1 = highest (managed), 6 = lowest (auto)
}

// Project rules with optional path-based scoping
public record ProjectRule(
    String name,
    String content,
    List<String> paths,  // glob patterns, null = unconditional
    Path source
) {
    public boolean appliesTo(Path filePath) {
        if (paths == null || paths.isEmpty()) return true;
        return paths.stream().anyMatch(pattern ->
            filePath.toString().matches(globToRegex(pattern)));
    }
}
```

### 2.7 Memory Loading Flow

```
Session Start
  │
  ├── 1. Load managed policy (/etc/aceclaw/ACECLAW.md)
  ├── 2. Walk directory hierarchy (cwd → root)
  │      ├── ./ACECLAW.md or ./.aceclaw/ACECLAW.md
  │      ├── ./ACECLAW.local.md
  │      ├── ./.aceclaw/rules/*.md
  │      └── ../ACECLAW.md (parent dirs)
  ├── 3. Load user memory (~/.aceclaw/ACECLAW.md)
  │      └── ~/.aceclaw/rules/*.md
  ├── 4. Load auto memory
  │      └── ~/.aceclaw/projects/{hash}/memory/MEMORY.md (200 lines)
  ├── 5. Process @imports (recursive, max depth 5)
  ├── 6. Assemble system prompt
  │      └── Static fragments + dynamic context + memory tiers
  └── 7. Ready for first prompt
```

---

## 3. Tool Plugin Architecture

### 3.1 Unified Tool Registry

The current `ToolRegistry` (ConcurrentHashMap) is extended to support three tool sources with unified lifecycle management:

```
+------------------------------------------------------------------+
|                   UNIFIED TOOL REGISTRY                           |
+------------------------------------------------------------------+
|                                                                    |
|  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────┐ |
|  │  Native Tools    │  │  MCP Tools       │  │  Skill Tools   │ |
|  │  (compile-time)  │  │  (runtime)       │  │  (runtime)     │ |
|  ├──────────────────┤  ├──────────────────┤  ├────────────────┤ |
|  │ read_file        │  │ mcp__github__*   │  │ skill__review  │ |
|  │ write_file       │  │ mcp__sentry__*   │  │ skill__deploy  │ |
|  │ edit_file        │  │ mcp__db__*       │  │ skill__test    │ |
|  │ bash             │  │                  │  │                │ |
|  │ glob             │  │ (discovered at   │  │ (discovered at │ |
|  │ grep             │  │  server connect) │  │  startup, lazy │ |
|  │ list_directory   │  │                  │  │  loaded)       │ |
|  │ web_fetch        │  │ (hot-reloadable  │  │                │ |
|  │ web_search       │  │  via list_changed│  │ (hot-reloadable│ |
|  │ browser          │  │  notifications)  │  │  via WatchSvc) │ |
|  │ applescript      │  │                  │  │                │ |
|  │ screen_capture   │  │                  │  │                │ |
|  │ task (sub-agent) │  │                  │  │                │ |
|  └──────────────────┘  └──────────────────┘  └────────────────┘ |
|                                                                    |
|  ToolRegistry.register(tool)                                      |
|  ToolRegistry.unregister(toolName)                                |
|  ToolRegistry.get(toolName) → Optional<Tool>                     |
|  ToolRegistry.toDefinitions() → List<ToolDefinition>             |
|  ToolRegistry.bySource(ToolSource) → List<Tool>                  |
|                                                                    |
+------------------------------------------------------------------+
```

### 3.2 Tool Source Tracking

```java
// New enum for tracking tool provenance
public enum ToolSource {
    NATIVE,      // Compiled into AceClaw (read_file, bash, etc.)
    MCP,         // Discovered from MCP servers at runtime
    SKILL,       // Generated from SKILL.md files
    CUSTOM       // User-defined via plugin or config
}

// Extended Tool interface
public interface Tool {
    String name();
    String description();
    JsonNode inputSchema();
    ToolResult execute(String inputJson) throws Exception;

    // New: provenance tracking
    default ToolSource source() { return ToolSource.NATIVE; }

    // New: permission level hint
    default PermissionLevel permissionLevel() { return PermissionLevel.EXECUTE; }

    record ToolResult(String output, boolean isError) {}

    default ToolDefinition toDefinition() {
        return new ToolDefinition(name(), description(), inputSchema());
    }
}
```

### 3.3 Tool Hot-Loading

```
+----------------------------------------------------------+
|                  TOOL HOT-LOADING                         |
+----------------------------------------------------------+
|                                                            |
|  MCP Tools:                                               |
|  ┌─────────────────────────────────────────────────┐     |
|  │ MCP Server sends list_changed notification      │     |
|  │      │                                           │     |
|  │      ▼                                           │     |
|  │ McpClientManager.refreshTools(serverName)        │     |
|  │      │                                           │     |
|  │      ▼                                           │     |
|  │ Unregister old mcp__{server}__* tools            │     |
|  │ Register newly discovered tools                   │     |
|  │ Log delta (added/removed tool names)              │     |
|  └─────────────────────────────────────────────────┘     |
|                                                            |
|  Skill Tools:                                             |
|  ┌─────────────────────────────────────────────────┐     |
|  │ WatchService monitors skill directories:         │     |
|  │   ~/.aceclaw/skills/                             │     |
|  │   {project}/.aceclaw/skills/                     │     |
|  │      │                                           │     |
|  │      ▼ (CREATE/MODIFY/DELETE events)             │     |
|  │ SkillRegistry.reload(changedPath)                │     |
|  │      │                                           │     |
|  │      ▼                                           │     |
|  │ Parse SKILL.md → SkillDefinition                 │     |
|  │ Update ToolRegistry (add/replace/remove)          │     |
|  └─────────────────────────────────────────────────┘     |
|                                                            |
+----------------------------------------------------------+
```

### 3.4 MCP Tool Search (Lazy Loading)

When many MCP tools exceed 10% of the context window, replace individual tool definitions with a meta-tool:

```java
public final class McpToolSearchTool implements Tool {

    private final McpClientManager mcpManager;

    @Override
    public String name() { return "mcp_search"; }

    @Override
    public String description() {
        return "Search for MCP tools by keyword. Use when you need " +
               "a capability not available in native tools.";
    }

    @Override
    public ToolResult execute(String inputJson) {
        // Parse query, search tool names + descriptions
        // Return matching tool definitions for the LLM to select
    }
}
```

### 3.5 Tool Metrics

Each tool execution is instrumented for self-improvement feedback:

```java
public record ToolMetrics(
    String toolName,
    int totalInvocations,
    int successCount,
    int errorCount,
    double avgExecutionMs,
    Instant lastUsed,
    Map<String, Integer> errorTypes  // error message → count
) {}

// Stored in auto-memory as TOOL_USAGE_PATTERN entries
```

---

## 4. Skills System

### 4.1 Skill Definition Format

Following the Agent Skills open standard (compatible with Claude Code):

```
{project}/.aceclaw/skills/{name}/
├── SKILL.md           # Main instructions (required)
├── template.md        # Template for agent to fill in (optional)
├── examples/
│   └── sample.md      # Example output (optional)
└── scripts/
    └── validate.sh    # Validation script (optional)
```

### 4.2 SKILL.md Format

```yaml
---
name: explain-code
description: Explains code with visual diagrams and analogies.
  Use when explaining how code works or when the user asks
  "how does this work?"
argument-hint: "[file-path] [detail-level]"
disable-model-invocation: false
user-invocable: true
allowed-tools:
  - read_file
  - glob
  - grep
model: null          # null = inherit from session
context: null        # null = inline, "fork" = subagent
agent: null          # subagent type when context=fork
---

When explaining code, always include:

1. **Start with an analogy**: Compare to everyday life
2. **Draw a diagram**: Use ASCII art
3. **Walk through the code**: Step-by-step
4. **Highlight a gotcha**: Common misconception

$ARGUMENTS
```

### 4.3 Skill Registry

```java
public final class SkillRegistry {

    // Discovery locations (by priority, higher overrides lower)
    // 1. ~/.aceclaw/skills/ (personal)
    // 2. {project}/.aceclaw/skills/ (project)

    private final ConcurrentHashMap<String, SkillDefinition> skills;
    private final WatchService watchService;  // hot-reload

    // Lazy loading: descriptions always in context,
    // full content only on invocation
    public List<SkillSummary> listDescriptions();
    public Optional<SkillDefinition> lookup(String name);
    public String expand(SkillDefinition skill, String arguments);
}

public record SkillDefinition(
    String name,
    String description,
    String argumentHint,
    boolean disableModelInvocation,
    boolean userInvocable,
    List<String> allowedTools,
    String model,           // null = inherit
    String context,         // null or "fork"
    String agent,           // subagent type for fork
    String content,         // full markdown content
    Path sourceDir,         // directory containing SKILL.md
    SkillSource source      // PERSONAL, PROJECT
) {}

public record SkillSummary(
    String name,
    String description,
    String argumentHint,
    boolean userInvocable
) {}

public enum SkillSource { PERSONAL, PROJECT }
```

### 4.4 Skill Invocation Flow

```
+------------------------------------------------------------------+
|                    SKILL INVOCATION FLOW                           |
+------------------------------------------------------------------+
|                                                                    |
|  Trigger: User types "/explain-code src/auth/login.java"          |
|  OR: LLM decides to invoke skill based on description match       |
|       │                                                            |
|       ▼                                                            |
|  SkillRegistry.lookup("explain-code")                              |
|       │                                                            |
|       ▼                                                            |
|  Load full SKILL.md content (lazy)                                 |
|       │                                                            |
|       ▼                                                            |
|  Argument Substitution                                             |
|  ┌─────────────────────────────────────────────────┐              |
|  │ $ARGUMENTS   → "src/auth/login.java"            │              |
|  │ $ARGUMENTS[0]→ "src/auth/login.java"            │              |
|  │ ${ACECLAW_SESSION_ID} → current session UUID    │              |
|  └─────────────────────────────────────────────────┘              |
|       │                                                            |
|       ▼                                                            |
|  Dynamic Context (if any !`command` preprocessing)                 |
|       │                                                            |
|       ▼                                                            |
|  context == "fork"?                                                |
|  ┌──────┐  ┌───────┐                                             |
|  │  No  │  │  Yes  │→ Spawn subagent (TaskTool)                  |
|  │      │  │       │  Skill content = subagent prompt              |
|  └──┬───┘  └───┬───┘  Separate context window                    |
|     │          │                                                   |
|     ▼          ▼                                                   |
|  Inject expanded content into conversation                         |
|  Agent processes with tool access                                  |
|                                                                    |
+------------------------------------------------------------------+
```

### 4.5 Skill Context Budget

Skill descriptions are injected into the system prompt with a budget constraint:

- **Budget**: 2% of context window tokens (e.g., 4,000 tokens for 200K window)
- **Overflow**: If total skill descriptions exceed budget, prioritize by:
  1. Skills matching current file types (path-based relevance)
  2. Most recently used skills
  3. User-invocable skills over model-only skills
- **Lazy loading**: Full skill content loads only on invocation

### 4.6 Skill Composition

Skills can reference other skills via `@skill-name` syntax in their content:

```markdown
---
name: full-review
description: Complete code review with tests
---

First, use @explain-code to understand the code.
Then, check for issues using @security-audit.
Finally, verify tests with @run-tests.
```

The agent resolves `@skill-name` references and chains skill invocations.

### 4.7 Autonomous Skill Generation

When the agent detects a repeated multi-step pattern (via procedural memory), it can propose creating a new skill:

```
+----------------------------------------------------------+
|              AUTONOMOUS SKILL GENERATION                  |
+----------------------------------------------------------+
|                                                            |
|  1. Pattern Detection                                     |
|     ProceduralMemory detects: "User asked for X 3 times"  |
|     Same tool sequence used each time                      |
|                                                            |
|  2. Proposal                                              |
|     Agent proposes: "I notice we do X frequently.          |
|     Shall I create a skill for it?"                        |
|                                                            |
|  3. User Approval                                         |
|     User: "Yes, call it quick-deploy"                      |
|                                                            |
|  4. Skill Generation                                      |
|     Agent writes SKILL.md to                               |
|     {project}/.aceclaw/skills/quick-deploy/SKILL.md        |
|     with extracted steps and argument hints                 |
|                                                            |
|  5. Registration                                          |
|     WatchService detects new file                           |
|     SkillRegistry hot-loads the new skill                   |
|     Available immediately for future sessions               |
|                                                            |
+----------------------------------------------------------+
```

---

## 5. Self-Improvement Loop

### 5.1 Architecture Overview

The self-improvement loop is a background process that runs between agent turns, analyzing patterns in the agent's behavior and persisting improvements to memory.

```
+------------------------------------------------------------------+
|                    SELF-IMPROVEMENT LOOP                           |
+------------------------------------------------------------------+
|                                                                    |
|                     ┌─────────────┐                               |
|                     │ Agent Turn  │                               |
|                     │ (ReAct loop)│                               |
|                     └──────┬──────┘                               |
|                            │                                       |
|               ┌────────────┼────────────┐                         |
|               │            │            │                          |
|               ▼            ▼            ▼                          |
|       ┌──────────┐  ┌──────────┐  ┌──────────┐                  |
|       │  Error   │  │ Success  │  │ Pattern  │                   |
|       │ Detector │  │ Detector │  │ Detector │                   |
|       └────┬─────┘  └────┬─────┘  └────┬─────┘                  |
|            │              │              │                         |
|            ▼              ▼              ▼                         |
|       ┌──────────────────────────────────────┐                   |
|       │        Insight Aggregator            │                   |
|       │  - Deduplicate similar insights      │                   |
|       │  - Score by confidence + frequency   │                   |
|       │  - Filter noise (low-value items)    │                   |
|       └──────────────┬───────────────────────┘                   |
|                      │                                             |
|                      ▼                                             |
|       ┌──────────────────────────────────────┐                   |
|       │        Memory Writer                 │                   |
|       │  - Persist to AutoMemoryStore        │                   |
|       │  - Update MEMORY.md if significant   │                   |
|       │  - Create/update topic files         │                   |
|       └──────────────────────────────────────┘                   |
|                                                                    |
+------------------------------------------------------------------+
```

### 5.2 Error Detection and Self-Correction

```java
public sealed interface ErrorPattern permits
    ToolFailure, PermissionDenied, InvalidParameter,
    TimeoutError, ResourceNotFound, BuildFailure {

    String toolName();
    String errorMessage();
    String resolution();  // how it was resolved (if resolved)
    int occurrenceCount();
}

// Detector scans tool results in the current turn
public final class ErrorDetector {

    public List<ErrorPattern> analyze(Turn turn) {
        var patterns = new ArrayList<ErrorPattern>();
        for (var msg : turn.newMessages()) {
            // Scan for isError=true tool results
            // Match against known patterns
            // Extract resolution from subsequent successful calls
        }
        return patterns;
    }
}
```

### 5.3 Success Pattern Detection

```java
public record SuccessPattern(
    String taskType,           // "build fix", "test writing", "refactoring"
    List<String> toolSequence, // ["grep", "read_file", "edit_file", "bash"]
    int stepCount,
    Duration duration,
    String summary             // human-readable description
) {}

// Detects successful multi-step sequences
public final class SuccessDetector {

    public List<SuccessPattern> analyze(Turn turn) {
        // Identify tool sequences that led to end_turn
        // without errors in the final iterations
        // Classify the task type from the user prompt
    }
}
```

### 5.4 Strategy Refinement

When the same task type is encountered multiple times, the agent compares strategies:

```
Session 1: "Fix build" → grep → read → edit → bash(build) → SUCCESS (5 tools, 45s)
Session 2: "Fix build" → bash(build) → read(error) → edit → bash(build) → SUCCESS (4 tools, 30s)
Session 3: "Fix build" → read(MEMORY) → edit → bash(build) → SUCCESS (3 tools, 20s)

Insight: "For build fixes, start by reading relevant memory, then edit and verify."
→ Persisted to PROCEDURAL/RECOVERY_STRATEGY
```

### 5.5 Prompt-Encoded Recovery Strategies

Following Claude Code's pattern, recovery strategies are embedded directly in tool descriptions:

```java
// Example: Pre-commit hook recovery (in system prompt)
"If a commit fails due to pre-commit hook: fix the issue and create
a NEW commit. CRITICAL: Always create NEW commits rather than amending."

// Example: Edit uniqueness recovery (in edit_file description)
"The edit will FAIL if old_string is not unique in the file.
Either provide more surrounding context or use replace_all."

// Example: Permission fallback (in bash description)
"If a command fails with permission errors, check if the command
requires elevated privileges and inform the user."
```

### 5.6 Self-Improvement Integration with Compaction

During context compaction (Phase 0: Memory Flush), the existing `extractContextItems()` method feeds insights into the self-improvement loop:

```
Context Compaction
  │
  ├── Phase 0: Memory Flush
  │   ├── Extract file paths modified
  │   ├── Extract commands executed
  │   ├── Extract errors encountered
  │   └── Feed to Insight Aggregator
  │
  ├── Phase 1: Prune (existing)
  │
  └── Phase 2: Summarize (existing)
      └── Summary also feeds SessionSummary → Episodic Memory
```

### 5.7 Memory-Backed Persistence

All self-improvement insights are persisted via the existing `AutoMemoryStore.add()` method:

```java
// Error pattern → RECOVERY_STRATEGY
memoryStore.add(
    MemoryEntry.Category.RECOVERY_STRATEGY,
    "Gradle build fails with OutOfMemoryError: increase heap with -Xmx2g in gradle.properties",
    List.of("gradle", "build", "oom", "memory"),
    "session:" + sessionId,
    false,  // project-specific
    projectPath
);

// Tool usage pattern → TOOL_USAGE_PATTERN
memoryStore.add(
    MemoryEntry.Category.TOOL_USAGE_PATTERN,
    "For TypeScript refactoring: grep → read_file → edit_file → bash(tsc) is optimal",
    List.of("typescript", "refactoring", "tool-sequence"),
    "session:" + sessionId,
    true,  // global (applies to all TS projects)
    projectPath
);
```

---

## 6. Module Assignments

### 6.1 Module Map

```
aceclaw-memory (ENHANCED)
  ├── AutoMemoryStore (existing, extended with new categories)
  ├── MemoryEntry (existing, extended with accessCount, lastAccessedAt, ttl)
  ├── MemorySigner (existing, unchanged)
  ├── MemorySearchEngine (NEW: hybrid search with TF-IDF + recency + frequency)
  ├── MemoryTier (NEW: sealed interface for 6-tier config hierarchy)
  ├── MemoryTierLoader (NEW: loads ACECLAW.md hierarchy at session start)
  ├── ProjectRule (NEW: path-scoped rule with glob matching)
  └── MemoryMetrics (NEW: access tracking for frequency-based scoring)

aceclaw-core (ENHANCED)
  ├── agent/
  │   ├── Tool (existing, extended with source() and permissionLevel())
  │   ├── ToolRegistry (existing, extended with bySource(), unregister())
  │   ├── StreamingAgentLoop (existing, unchanged)
  │   ├── AgentLoop (existing, unchanged)
  │   ├── Turn (existing, unchanged)
  │   ├── MessageCompactor (existing, unchanged)
  │   ├── ToolMetrics (NEW: per-tool execution statistics)
  │   └── ToolMetricsCollector (NEW: collects metrics during tool execution)
  └── llm/ (existing, unchanged)

aceclaw-tools (ENHANCED)
  ├── [existing 14 tools, unchanged]
  ├── TaskTool (NEW: spawns sub-agents)
  ├── TaskOutputTool (NEW: retrieves background task results)
  └── SkillTool (NEW: invokes skills)

aceclaw-mcp (ENHANCED)
  ├── McpClientManager (existing, add refreshTools() for hot-reload)
  ├── McpToolBridge (existing, add source()=MCP)
  ├── McpServerConfig (existing, unchanged)
  └── McpToolSearchTool (NEW: lazy loading for many MCP tools)

aceclaw-daemon (ENHANCED)
  ├── [existing classes, unchanged]
  ├── SubAgentRunner (NEW: creates and runs sub-agent loops)
  ├── AgentTypeRegistry (NEW: built-in + custom agent configs)
  ├── SubAgentConfig (NEW: record for agent type configuration)
  ├── SkillRegistry (NEW: discovers and manages skills)
  ├── SelfImprovementEngine (NEW: error/success/pattern detectors)
  └── SystemPromptAssembler (NEW: fragment-based prompt assembly)

aceclaw-security (ENHANCED)
  ├── [existing classes, unchanged]
  └── PermissionRuleEngine (NEW: glob pattern matching for tool access)
```

### 6.2 New Class Count by Module

| Module | Existing Classes | New Classes | Total |
|--------|-----------------|-------------|-------|
| aceclaw-memory | 4 | 5 | 9 |
| aceclaw-core | 10 | 2 | 12 |
| aceclaw-tools | 14 | 3 | 17 |
| aceclaw-mcp | 3 | 1 | 4 |
| aceclaw-daemon | 16 | 6 | 22 |
| aceclaw-security | 7 | 1 | 8 |
| **Total** | **54** | **18** | **72** |

---

## 7. Interface Definitions

### 7.1 Memory Interfaces

```java
// --- aceclaw-memory ---

// Extended MemoryEntry with access tracking
public record MemoryEntry(
    String id,
    Category category,
    String content,
    List<String> tags,
    Instant createdAt,
    String source,
    String hmac,
    // NEW fields
    int accessCount,
    Instant lastAccessedAt,
    Duration ttl  // null = never expires
) {
    public enum Category {
        // Existing (backward compatible)
        MISTAKE, PATTERN, PREFERENCE, CODEBASE_INSIGHT, STRATEGY,
        // New: Episodic
        SESSION_SUMMARY, ERROR_ENCOUNTER, SUCCESSFUL_STRATEGY, USER_FEEDBACK,
        // New: Semantic
        PROJECT_INSIGHT, DOMAIN_KNOWLEDGE, USER_PREFERENCE, ENVIRONMENT_FACT,
        // New: Procedural
        TOOL_USAGE_PATTERN, RECOVERY_STRATEGY, ANTI_PATTERN
    }

    public String signablePayload() {
        // Include new fields in HMAC computation
        return id + "|" + category + "|" + content + "|" +
            String.join(",", tags) + "|" + createdAt + "|" + source;
    }

    public boolean isExpired() {
        if (ttl == null) return false;
        return Instant.now().isAfter(createdAt.plus(ttl));
    }
}

// Hybrid search engine
public final class MemorySearchEngine {

    public record SearchQuery(
        String text,
        MemoryEntry.Category category,  // null = all
        List<String> tags,              // null = all
        int limit
    ) {}

    public record ScoredEntry(MemoryEntry entry, double score) {}

    public List<ScoredEntry> search(SearchQuery query);
}

// Memory tier hierarchy
public sealed interface MemoryTier permits
    MemoryTier.ManagedPolicy,
    MemoryTier.ProjectMemory,
    MemoryTier.ProjectRules,
    MemoryTier.UserMemory,
    MemoryTier.ProjectLocalMemory,
    MemoryTier.AutoMemory {

    String content();
    Path source();
    int priority();

    record ManagedPolicy(String content, Path source) implements MemoryTier {
        public int priority() { return 1; }
    }
    record ProjectMemory(String content, Path source) implements MemoryTier {
        public int priority() { return 2; }
    }
    record ProjectRules(String content, Path source, List<String> paths)
        implements MemoryTier {
        public int priority() { return 3; }
    }
    record UserMemory(String content, Path source) implements MemoryTier {
        public int priority() { return 4; }
    }
    record ProjectLocalMemory(String content, Path source) implements MemoryTier {
        public int priority() { return 5; }
    }
    record AutoMemory(String content, Path source) implements MemoryTier {
        public int priority() { return 6; }
    }
}
```

### 7.2 Tool Interfaces

```java
// --- aceclaw-core ---

// Extended Tool interface (backward compatible)
public interface Tool {
    String name();
    String description();
    JsonNode inputSchema();
    ToolResult execute(String inputJson) throws Exception;

    default ToolSource source() { return ToolSource.NATIVE; }
    default PermissionLevel permissionLevel() { return PermissionLevel.EXECUTE; }
    default ToolDefinition toDefinition() {
        return new ToolDefinition(name(), description(), inputSchema());
    }

    record ToolResult(String output, boolean isError) {}
}

public enum ToolSource { NATIVE, MCP, SKILL, CUSTOM }

// Tool metrics
public record ToolMetrics(
    String toolName,
    int totalInvocations,
    int successCount,
    int errorCount,
    long totalExecutionMs,
    Instant lastUsed
) {
    public double avgExecutionMs() {
        return totalInvocations > 0
            ? (double) totalExecutionMs / totalInvocations : 0;
    }
    public double successRate() {
        return totalInvocations > 0
            ? (double) successCount / totalInvocations : 0;
    }
}
```

### 7.3 Skill Interfaces

```java
// --- aceclaw-daemon ---

public record SkillDefinition(
    String name,
    String description,
    String argumentHint,
    boolean disableModelInvocation,
    boolean userInvocable,
    List<String> allowedTools,
    String model,
    String context,       // null or "fork"
    String agent,         // subagent type for context=fork
    String content,       // full markdown body
    Path sourceDir,
    SkillSource source
) {}

public enum SkillSource { PERSONAL, PROJECT }

public record SkillSummary(
    String name,
    String description,
    String argumentHint,
    boolean userInvocable
) {}

public final class SkillRegistry {
    Optional<SkillDefinition> lookup(String name);
    List<SkillSummary> listDescriptions();
    String expand(SkillDefinition skill, String arguments, String sessionId);
    void reload();  // re-scan directories
}
```

### 7.4 Sub-Agent Interfaces

```java
// --- aceclaw-daemon ---

public record SubAgentConfig(
    String name,
    String description,
    String systemPrompt,
    String model,                  // "inherit", "sonnet", "haiku", "opus"
    List<String> allowedTools,     // empty = all (minus Task)
    List<String> disallowedTools,
    String permissionMode,
    int maxTurns,
    List<String> preloadedSkills,
    List<String> mcpServers
) {}

public final class AgentTypeRegistry {
    // Built-in agents
    SubAgentConfig get(String agentType);
    List<SubAgentConfig> listBuiltIn();

    // Custom agents from .aceclaw/agents/*.md
    void loadCustomAgents(Path projectDir);
    List<SubAgentConfig> listCustom();
}

public final class SubAgentRunner {
    // Creates a fresh StreamingAgentLoop and runs the sub-agent
    Turn run(SubAgentConfig config, String prompt,
             LlmClient llmClient, StreamEventHandler handler);
}
```

### 7.5 Self-Improvement Interfaces

```java
// --- aceclaw-daemon ---

public sealed interface Insight permits
    ErrorInsight, SuccessInsight, PatternInsight {

    String summary();
    List<String> tags();
    MemoryEntry.Category targetCategory();
    double confidence();  // 0.0 - 1.0
}

record ErrorInsight(
    String toolName,
    String errorMessage,
    String resolution,
    double confidence
) implements Insight { ... }

record SuccessInsight(
    String taskType,
    List<String> toolSequence,
    double confidence
) implements Insight { ... }

record PatternInsight(
    String description,
    int occurrenceCount,
    double confidence
) implements Insight { ... }

public final class SelfImprovementEngine {
    // Analyze a completed turn for insights
    List<Insight> analyze(Turn turn, AutoMemoryStore memoryStore);

    // Persist insights with sufficient confidence
    void persist(List<Insight> insights, AutoMemoryStore store,
                 Path projectPath, String sessionId);
}
```

---

## 8. Data Flow Diagrams

### 8.1 Session Lifecycle with Self-Learning

```
┌──────────────────────────────────────────────────────────────────┐
│                  SESSION LIFECYCLE (SELF-LEARNING)                │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  SESSION START                                                     │
│  ┌──────────────────────────────────────────────────┐             │
│  │ 1. Load memory tiers (ACECLAW.md hierarchy)      │             │
│  │ 2. Load auto-memory (MEMORY.md → system prompt)  │             │
│  │ 3. Initialize ToolRegistry (native + MCP)         │             │
│  │ 4. Load SkillRegistry (descriptions → context)    │             │
│  │ 5. Assemble system prompt (fragments + context)   │             │
│  │ 6. Start WatchService for hot-reload              │             │
│  └──────────────────────────────────────────────────┘             │
│                                                                    │
│  EACH TURN                                                         │
│  ┌──────────────────────────────────────────────────┐             │
│  │ User Prompt                                       │             │
│  │     │                                             │             │
│  │     ▼                                             │             │
│  │ [Context compaction check]                        │             │
│  │     │                                             │             │
│  │     ▼                                             │             │
│  │ StreamingAgentLoop.runTurn()                      │             │
│  │   ├── LLM call (system prompt + tools + history)  │             │
│  │   ├── Tool execution (parallel via VirtualThreads)│             │
│  │   │   ├── ToolMetricsCollector records stats      │             │
│  │   │   └── Permission checking (existing flow)     │             │
│  │   └── Loop until end_turn or max iterations       │             │
│  │     │                                             │             │
│  │     ▼                                             │             │
│  │ SelfImprovementEngine.analyze(turn)               │             │
│  │   ├── ErrorDetector → ErrorInsights               │             │
│  │   ├── SuccessDetector → SuccessInsights           │             │
│  │   └── PatternDetector → PatternInsights           │             │
│  │     │                                             │             │
│  │     ▼                                             │             │
│  │ Persist high-confidence insights to AutoMemoryStore│             │
│  └──────────────────────────────────────────────────┘             │
│                                                                    │
│  SESSION END                                                       │
│  ┌──────────────────────────────────────────────────┐             │
│  │ 1. Generate session summary (if non-trivial)      │             │
│  │ 2. Persist to episodic memory (SESSION_SUMMARY)   │             │
│  │ 3. Flush tool metrics to auto-memory              │             │
│  │ 4. Stop WatchService                              │             │
│  └──────────────────────────────────────────────────┘             │
│                                                                    │
└──────────────────────────────────────────────────────────────────┘
```

### 8.2 Tool Execution with Metrics

```
Tool Invocation
  │
  ├── ToolMetricsCollector.startTimer(toolName)
  │
  ├── PermissionAwareTool.execute()
  │   ├── Permission check (existing flow)
  │   └── Delegate to actual tool
  │
  ├── ToolMetricsCollector.record(toolName, success/error, durationMs)
  │
  └── Return ToolResult to AgentLoop
```

### 8.3 Skill Invocation with Sub-Agent Fork

```
Skill Invocation (context: fork)
  │
  ├── SkillTool receives skill name + arguments
  │
  ├── SkillRegistry.lookup(name)
  │
  ├── SkillRegistry.expand(skill, arguments, sessionId)
  │   ├── $ARGUMENTS substitution
  │   └── !`command` preprocessing
  │
  ├── SubAgentRunner.run(
  │     config = AgentTypeRegistry.get(skill.agent()),
  │     prompt = expandedContent,
  │     ...)
  │   ├── Create fresh StreamingAgentLoop
  │   ├── Filtered ToolRegistry (skill.allowedTools)
  │   ├── Sub-agent's system prompt (skill content)
  │   └── Run ReAct loop in sub-agent context
  │
  └── Return sub-agent result to parent
```

---

## 9. Implementation Roadmap

### Phase 1: Memory Enhancement (P0)

| Task | Module | Effort | Dependencies |
|------|--------|--------|--------------|
| Extend MemoryEntry with new categories | aceclaw-memory | Low | None |
| Add accessCount, lastAccessedAt to MemoryEntry | aceclaw-memory | Low | None |
| Implement MemorySearchEngine (hybrid search) | aceclaw-memory | Medium | MemoryEntry extension |
| Implement MemoryTier sealed interface | aceclaw-memory | Low | None |
| Implement MemoryTierLoader (ACECLAW.md hierarchy) | aceclaw-memory | Medium | MemoryTier |
| Implement ProjectRule with path-based scoping | aceclaw-memory | Medium | None |
| Integrate memory tiers into SystemPromptLoader | aceclaw-daemon | Medium | MemoryTierLoader |

### Phase 2: Skills System (P1)

| Task | Module | Effort | Dependencies |
|------|--------|--------|--------------|
| Implement SkillDefinition and SkillSummary records | aceclaw-daemon | Low | None |
| Implement SkillRegistry (discovery + lazy loading) | aceclaw-daemon | Medium | SkillDefinition |
| Implement SkillTool (inline invocation) | aceclaw-tools | Medium | SkillRegistry |
| Add WatchService-based hot-reload | aceclaw-daemon | Medium | SkillRegistry |
| Implement skill context budget management | aceclaw-daemon | Low | SkillRegistry |
| Skill argument substitution ($ARGUMENTS, etc.) | aceclaw-daemon | Low | SkillRegistry |

### Phase 3: Sub-Agent Infrastructure (P1)

| Task | Module | Effort | Dependencies |
|------|--------|--------|--------------|
| Implement SubAgentConfig record | aceclaw-daemon | Low | None |
| Implement AgentTypeRegistry (built-in agents) | aceclaw-daemon | Medium | SubAgentConfig |
| Implement SubAgentRunner | aceclaw-daemon | High | AgentTypeRegistry |
| Implement TaskTool | aceclaw-tools | Medium | SubAgentRunner |
| No-nesting enforcement (exclude Task from sub-agents) | aceclaw-daemon | Low | SubAgentRunner |
| Load custom agents from .aceclaw/agents/*.md | aceclaw-daemon | Medium | AgentTypeRegistry |

### Phase 4: Self-Improvement (P2)

| Task | Module | Effort | Dependencies |
|------|--------|--------|--------------|
| Implement ToolMetrics and ToolMetricsCollector | aceclaw-core | Low | None |
| Implement ErrorDetector | aceclaw-daemon | Medium | None |
| Implement SuccessDetector | aceclaw-daemon | Medium | None |
| Implement SelfImprovementEngine | aceclaw-daemon | Medium | Detectors |
| Integrate into StreamingAgentHandler (post-turn) | aceclaw-daemon | Low | SelfImprovementEngine |
| Autonomous skill generation proposal flow | aceclaw-daemon | High | Skills + SelfImprovement |

### Phase 5: Tool Enhancement (P2)

| Task | Module | Effort | Dependencies |
|------|--------|--------|--------------|
| Add source() to Tool interface | aceclaw-core | Low | None |
| Add unregister() and bySource() to ToolRegistry | aceclaw-core | Low | None |
| Implement McpToolSearchTool (lazy loading) | aceclaw-mcp | Medium | None |
| MCP hot-reload via list_changed notifications | aceclaw-mcp | Medium | None |
| Implement PermissionRuleEngine (glob patterns) | aceclaw-security | Medium | None |

---

## Appendix A: Backward Compatibility

All changes are backward-compatible:

1. **MemoryEntry**: New categories are additive; `@JsonIgnoreProperties(ignoreUnknown = true)` handles old JSONL files
2. **Tool interface**: New methods have defaults; existing tools compile unchanged
3. **ToolRegistry**: Extended with new methods; existing API unchanged
4. **AutoMemoryStore**: New query methods alongside existing `query(category, tags, limit)`
5. **System prompt**: Assembled from fragments; existing `SystemPromptLoader` evolves into `SystemPromptAssembler`

## Appendix B: Security Considerations

1. **Memory integrity**: All entries HMAC-signed; tampered entries skipped on load
2. **Skill sandboxing**: Skills inherit the permission model; `!command` preprocessing runs with user's shell permissions
3. **Sub-agent isolation**: Each sub-agent gets its own context; no access to parent history
4. **No-nesting rule**: Prevents resource exhaustion from recursive sub-agent spawning
5. **MCP tool permissions**: MCP tools default to EXECUTE permission level; user approval required
6. **Auto-memory TTL**: Expired entries purged on load; prevents unbounded memory growth
7. **Path traversal**: Memory and skill directories validated against path traversal attacks

## Appendix C: Performance Considerations

1. **Hybrid search**: TF-IDF computed over in-memory index; no external dependency needed
2. **Lazy skill loading**: Descriptions only (~100 tokens each) in context; full content on demand
3. **Virtual threads**: MCP connections, sub-agents, and self-improvement analysis all use virtual threads
4. **WatchService**: Native OS file watching; zero polling overhead
5. **JSONL append-only**: Memory writes are append operations; no full-file rewrites except on delete
