# AceClaw Architecture Implementation Plan

## Self-Learning, Adaptive Skills & Agent Teams

**Version**: 1.0
**Date**: 2026-02-17
**Author**: Product Owner (Architecture Team Synthesis)
**Status**: Approved Architecture Plan
**Sources**: OpenClaw analysis, Claude Code analysis, self-learning architecture, agent teams architecture, security review

---

## Table of Contents

1. [Architecture Summary](#1-architecture-summary)
2. [Current State Inventory](#2-current-state-inventory)
3. [Module Assignment](#3-module-assignment)
4. [Phase 1: Memory System Enhancement](#4-phase-1-memory-system-enhancement)
5. [Phase 2: Tool Plugin Architecture & MCP Protocol](#5-phase-2-tool-plugin-architecture--mcp-protocol)
6. [Phase 3: Skills System](#6-phase-3-skills-system)
7. [Phase 4: Self-Improvement Loop](#7-phase-4-self-improvement-loop)
8. [Phase 5: Agent Teams](#8-phase-5-agent-teams)
9. [Phase 6: Summary Learning Integration](#9-phase-6-summary-learning-integration)
10. [Dependency Graph & Critical Path](#10-dependency-graph--critical-path)
11. [Security Checkpoints](#11-security-checkpoints)
12. [Testing Strategy](#12-testing-strategy)
13. [Risk Assessment](#13-risk-assessment)

---

## 1. Architecture Summary

### Design Philosophy

AceClaw's next-generation capabilities follow three guiding principles drawn from our research of Claude Code (135+ prompt fragments, 6-tier memory, agent teams) and OpenClaw (self-evolution, skills, multi-agent):

1. **Progressive Enhancement** — Each phase delivers standalone value; no phase depends on all others being complete
2. **Memory-First** — Self-learning is the PRIMARY investment; every feature feeds into or benefits from the memory system
3. **Radical Simplicity** — A simple loop that produces high agency beats complex multi-agent frameworks (Claude Code's core principle)

### Key Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| **Evolve AutoMemoryStore, don't replace it** | Current HMAC-signed JSONL store works; add tiers on top |
| **ACECLAW.md hierarchy (6-tier)** | Matches Claude Code's proven memory model |
| **Skills as SKILL.md files** | Follows AgentSkills open standard; compatible with Claude Code |
| **Sub-agents before Agent Teams** | Depth-1 delegation is simpler and covers 80% of use cases |
| **File-based coordination for teams** | Survives crashes; matches Claude Code's production-proven approach |
| **In-process teammates via virtual threads** | AceClaw's daemon architecture enables ~40% resource savings |
| **Sealed interfaces for all state machines** | Compile-time exhaustiveness; Java's strongest type-safety guarantee |
| **No custom framework** — plain Java | Consistent with existing architecture; min binary size |

### Capability Tiers (Priority Order)

```
PRIMARY (Phases 1-4): Self-Learning
  Memory Enhancement → Tool Plugins → Skills → Self-Improvement

SECONDARY (Phases 5-6): Agent Teams + Integration
  Agent Teams → Summary Learning
```

---

## 2. Current State Inventory

### Existing Modules & Classes (~75+ classes)

| Module | Classes | Key Components | Status |
|--------|---------|---------------|--------|
| **aceclaw-core** | 25 | LlmClient, StreamingAgentLoop, ContextEstimator, MessageCompactor, ContentBlock, StreamEvent, Turn, ToolRegistry | Complete |
| **aceclaw-llm** | ~8 | AnthropicClient, OpenAICompatClient, LlmClientFactory, ProviderCapabilities | Complete |
| **aceclaw-tools** | 14 | ReadFile, WriteFile, EditFile, BashExec, Glob, Grep, ListDir, WebFetch, WebSearch, AppleScript, ScreenCapture, Browser, SchemaBuilder | Complete |
| **aceclaw-security** | ~5 | PermissionManager, PermissionDecision (sealed), DefaultPermissionPolicy | Complete |
| **aceclaw-daemon** | 16 | AceClawDaemon, UdsListener, RequestRouter, ConnectionBridge, SessionManager, StreamingAgentHandler, SystemPromptLoader, AceClawConfig | Complete |
| **aceclaw-cli** | 4 | AceClawMain, DaemonClient, DaemonStarter, TerminalRepl | Complete |
| **aceclaw-memory** | 4 | AutoMemoryStore, MemoryEntry, MemorySigner, package-info | Basic (JSONL + HMAC) |
| **aceclaw-mcp** | 3 | McpClientManager, McpServerConfig, McpToolBridge | Basic (stdio transport) |
| **aceclaw-sdk** | 0 | Placeholder | Empty |
| **aceclaw-infra** | 0 | Placeholder | Empty |
| **aceclaw-server** | 0 | Placeholder | Empty |
| **aceclaw-test** | 0 | Placeholder | Empty |

### What Works Today

- Full ReAct loop with streaming (25 iterations max)
- 14 built-in tools with permission checks
- Multi-provider support (Anthropic, OpenAI, Groq, Together, Mistral, Copilot, Ollama)
- Extended thinking (adaptive for Anthropic; disabled for others)
- Context compaction (3-phase: prune → summarize → memory flush)
- Prompt caching (system + tools + last user message)
- Retry with exponential backoff
- HMAC-signed auto-memory (5 categories: MISTAKE, PATTERN, PREFERENCE, CODEBASE_INSIGHT, STRATEGY)
- MCP client (stdio transport, tool bridging)
- 11 E2E integration tests

### What's Missing

- ACECLAW.md hierarchy (only config.json exists)
- Markdown-based memory (MEMORY.md with 200-line system prompt injection)
- Path-based rules
- Skills system
- Sub-agents (Task tool)
- Agent Teams
- Hook system
- Pattern detection and self-improvement
- Fragment-based system prompt assembly

---

## 3. Module Assignment

### Where New Code Goes

```
aceclaw-core (agent abstractions)
  ├── SubAgentConfig, SubAgentRunner
  ├── AgentTypeRegistry
  ├── TeamMessage (sealed), TeamCommand (sealed)
  ├── TaskStore, AgentTask, TaskStatus
  ├── TeamMessageRouter, TeamManager
  ├── PlanLearner (pattern→memory)
  └── Turn enhancements (thinking, planning states)

aceclaw-memory (all memory & learning)
  ├── AutoMemoryStore (existing, enhanced)
  ├── MemoryEntry (existing, enhanced categories)
  ├── MarkdownMemoryStore (MEMORY.md + topic files)
  ├── ProjectMemoryLoader (ACECLAW.md hierarchy)
  ├── PathBasedRules (conditional rules)
  ├── MemoryConsolidator (dedup, merge, prune)
  ├── PatternDetector (heuristic pattern extraction)
  ├── SkillDefinition, SkillRegistry, SkillMetrics
  ├── SkillOutcomeTracker, SkillRefinementEngine
  ├── SkillProposalEngine
  └── MemoryRetriever (parallel multi-store query)

aceclaw-tools (tool implementations)
  ├── TaskTool (sub-agent spawning)
  ├── TaskOutputTool (background result retrieval)
  ├── SkillTool (skill invocation)
  ├── AskUserTool (structured Q&A)
  ├── TodoWriteTool (internal planning)
  └── Enhanced tool descriptions (40+ lines each)

aceclaw-mcp (MCP protocol - existing, enhanced)
  ├── McpClientManager (enhanced: HTTP transport, OAuth)
  ├── McpToolSearch (lazy loading when many tools)
  ├── McpResourceProxy, McpPromptProxy
  └── McpConfigHierarchy (user → project → managed)

aceclaw-daemon (orchestration)
  ├── SystemPromptLoader (fragment-based assembly)
  ├── HookSystem (event types, matchers, executors)
  ├── TeamSession (per-team state)
  ├── BackgroundTaskManager (virtual thread pool)
  └── SessionTranscriptStore (JSONL persistence)

aceclaw-sdk (extension API)
  ├── ToolPlugin (SPI interface)
  ├── SkillPlugin (SPI interface)
  ├── HookPlugin (SPI interface)
  └── PluginLoader (classloader isolation)

aceclaw-infra (operational backbone)
  ├── EventBus (sealed event hierarchy)
  ├── MessageQueue (BlockingQueue-based)
  ├── HealthMonitor
  ├── Scheduler (virtual thread-backed)
  └── CircuitBreaker
```

---

## 4. Phase 1: Memory System Enhancement

**Goal**: Evolve from basic JSONL auto-memory to a full 6-tier memory hierarchy with markdown support, matching Claude Code's proven architecture.

**Priority**: P0 (Foundation — everything else depends on this)

### 4.1 Tasks

#### 4.1.1 ACECLAW.md Hierarchy (~4 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `ProjectMemoryLoader` | aceclaw-memory | Walks directory hierarchy (cwd → root), loads ACECLAW.md files |
| `MemoryTier` | aceclaw-memory | Enum: MANAGED, PROJECT, PROJECT_RULES, USER, LOCAL, AUTO |
| `MemoryDocument` | aceclaw-memory | Record: tier, path, content, loadOrder |
| `ImportResolver` | aceclaw-memory | Handles `@path/to/import` syntax (recursive, max depth 5) |

**File locations**:
```
~/.aceclaw/ACECLAW.md              → USER tier
{project}/.aceclaw/ACECLAW.md      → PROJECT tier
{project}/.aceclaw/rules/*.md      → PROJECT_RULES tier
{project}/ACECLAW.local.md         → LOCAL tier (gitignored)
```

**Loading order**: Managed → Project → Rules → User → Local → Auto

#### 4.1.2 Markdown Memory Store (~3 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `MarkdownMemoryStore` | aceclaw-memory | Manages `~/.aceclaw/projects/<project>/memory/` directory |
| `MemoryIndex` | aceclaw-memory | Reads MEMORY.md (first 200 lines for system prompt injection) |
| `TopicFileManager` | aceclaw-memory | Manages topic files (debugging.md, patterns.md, etc.) |

**Key behaviors**:
- MEMORY.md first 200 lines injected into system prompt at session start
- Topic files read on-demand via standard file tools
- Agent can read/write memory files during sessions
- Project hash based on git repository root (consistent with Claude Code)

#### 4.1.3 Path-Based Rules (~2 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `PathBasedRule` | aceclaw-memory | Record with YAML frontmatter `paths:` and markdown content |
| `RuleEngine` | aceclaw-memory | Evaluates glob patterns, returns applicable rules for file paths |

#### 4.1.4 Memory Retrieval Enhancement (~2 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `MemoryRetriever` | aceclaw-memory | Parallel retrieval from all stores via StructuredTaskScope |
| `MemoryInjection` | aceclaw-memory | Record combining all tier results for prompt injection |

#### 4.1.5 System Prompt Enhancement (~3 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `PromptFragment` | aceclaw-daemon | Record: name, content, condition (always/conditional) |
| `PromptAssembler` | aceclaw-daemon | Assembles system prompt from fragments + dynamic context |
| `DynamicContext` | aceclaw-daemon | Record: cwd, platform, date, model, gitStatus, permissionMode |

Evolve `SystemPromptLoader` to build prompt from:
1. Static fragments (core identity, tool usage, safety, style)
2. Tool descriptions (40+ lines each with anti-patterns)
3. Dynamic context (cwd, platform, date, model, git status)
4. Memory files (ACECLAW.md hierarchy + auto-memory)
5. Conditional fragments (plan mode, delegate mode, team context)

### 4.2 Estimated Effort

- **New classes**: ~14
- **Modified classes**: ~3 (SystemPromptLoader, StreamingAgentHandler, AceClawConfig)
- **Tests**: 8-10 unit tests, 3 integration tests
- **Effort**: Medium (1-2 weeks)

### 4.3 Security Checkpoint

- [ ] Memory files loaded with untrusted content treatment (potential prompt injection)
- [ ] ACECLAW.md @import paths validated (no directory traversal)
- [ ] MEMORY.md 200-line limit enforced (prevent context pollution)
- [ ] File size limits on all memory files (50KB per file, 500KB total per project)
- [ ] Directory permissions: 700 (owner only)
- [ ] HMAC signing extended to markdown memory files

---

## 5. Phase 2: Tool Plugin Architecture & MCP Protocol

**Goal**: Enhance tool descriptions for reasoning quality, add MCP HTTP transport, and lay groundwork for skill invocation.

**Priority**: P1 (Reasoning quality + extensibility)

### 5.1 Tasks

#### 5.1.1 Enhanced Tool Descriptions (~0 new classes, extensive tool description rewrite)

Rewrite all 14 tool descriptions to 40+ lines each, following Claude Code's pattern:

1. **Capability declaration** — what it does
2. **Anti-pattern prevention** — "use Read instead of cat"
3. **Behavioral guidelines** — "only commit when requested"
4. **Format specification** — "use HEREDOC for commit messages"
5. **Workflow templates** — multi-step procedures
6. **Safety constraints** — "NEVER skip hooks"
7. **Optimization hints** — "parallel reads encouraged"

Move descriptions to resource files: `aceclaw-tools/src/main/resources/tool-descriptions/{tool-name}.md`

#### 5.1.2 Sub-Agent Infrastructure (~6 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `SubAgentConfig` | aceclaw-core | Record: name, description, model, tools, disallowedTools, maxTurns, permissionMode |
| `AgentTypeRegistry` | aceclaw-core | Registry of built-in + custom agents from `.aceclaw/agents/*.md` |
| `SubAgentRunner` | aceclaw-core | Creates fresh StreamingAgentLoop for each sub-agent |
| `TaskTool` | aceclaw-tools | Tool for spawning sub-agents (foreground/background) |
| `TaskOutputTool` | aceclaw-tools | Tool for retrieving background sub-agent results |
| `SubAgentTranscript` | aceclaw-daemon | JSONL-based transcript persistence for sub-agents |

**Built-in agent types**:

| Agent | Model | Tools | Purpose |
|-------|-------|-------|---------|
| `explore` | Haiku | ReadFile, GlobSearch, GrepSearch, ListDir | Fast codebase exploration |
| `plan` | Inherit | ReadFile, GlobSearch, GrepSearch | Research for plan mode |
| `general` | Inherit | All (except Task) | Complex multi-step tasks |

**No-nesting rule**: TaskTool excluded from sub-agent ToolRegistry (compile-time enforced).

#### 5.1.3 MCP Enhancements (~4 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `HttpMcpTransport` | aceclaw-mcp | HTTP/SSE transport for remote MCP servers |
| `McpToolSearch` | aceclaw-mcp | Lazy loading when MCP tools exceed 10% of context window |
| `McpConfigHierarchy` | aceclaw-mcp | User → project → managed config merging |
| `McpOAuthHandler` | aceclaw-mcp | OAuth 2.0 authentication for remote servers |

#### 5.1.4 AskUser Tool (~1 class)

| Class | Module | Description |
|-------|--------|-------------|
| `AskUserTool` | aceclaw-tools | Structured Q&A with multiple-choice options |

### 5.2 Estimated Effort

- **New classes**: ~11
- **Modified classes**: ~5 (all 14 tool descriptions rewritten, ToolRegistry, StreamingAgentHandler)
- **Tests**: 5-7 unit tests, 3 integration tests
- **Effort**: Medium-High (2-3 weeks)

### 5.3 Security Checkpoint

- [ ] Sub-agent tools restricted by principle of least privilege
- [ ] Background sub-agents use pre-approved permissions only
- [ ] TaskTool excluded from sub-agent ToolRegistry (no nesting)
- [ ] MCP tool output truncated (25K token max)
- [ ] MCP server OAuth tokens stored securely
- [ ] MCP managed config cannot be overridden by user

---

## 6. Phase 3: Skills System

**Goal**: Implement the skills system — model-invoked capabilities that learn and improve from execution feedback.

**Priority**: P1 (Core self-learning mechanism)

### 6.1 Tasks

#### 6.1.1 Skill Definition & Registry (~6 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `SkillDefinition` | aceclaw-memory | Record: name, description, argumentHint, modelInvocable, userInvocable, allowedTools, model, context, agent, content |
| `SkillSource` | aceclaw-memory | Sealed interface: Enterprise, Personal, Project, Plugin |
| `SkillState` | aceclaw-memory | Sealed interface: Draft, Active, Deprecated, Disabled |
| `SkillRegistry` | aceclaw-memory | Discovery, lookup, lazy-loading of skill descriptions |
| `SkillParser` | aceclaw-memory | YAML frontmatter + markdown body parser for SKILL.md |
| `SkillTool` | aceclaw-tools | Internal tool for invoking skills (model or user via /name) |

**Skill file format** (compatible with AgentSkills standard):
```yaml
---
name: explain-code
description: Explains code with visual diagrams and analogies
argument-hint: "[file-path]"
allowed-tools: ["read_file", "glob", "grep"]
---

When explaining code, always include:
1. Start with an analogy
2. Draw an ASCII diagram
3. Walk through step-by-step
```

**Storage hierarchy**:
```
~/.aceclaw/skills/<name>/SKILL.md           → Personal
{project}/.aceclaw/skills/<name>/SKILL.md   → Project
```

**Lazy loading**: Skill descriptions (2% of context budget) loaded into system prompt; full content only on invocation.

#### 6.1.2 Skill Invocation (~2 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `SkillExpander` | aceclaw-memory | Argument substitution ($ARGUMENTS, $0, $1), dynamic context (!`command`) |
| `SkillForkHandler` | aceclaw-core | `context: fork` → spawn sub-agent with skill content as prompt |

**Invocation flow**:
1. User types `/explain-code src/main.java` OR model decides to invoke
2. SkillTool looks up SKILL.md
3. SkillExpander performs $ARGUMENTS substitution + !`command` preprocessing
4. If `context: fork`: spawn sub-agent with expanded content as prompt
5. Otherwise: inject expanded content into conversation

#### 6.1.3 Skill Metrics & Tracking (~3 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `SkillMetrics` | aceclaw-memory | Record: invocationCount, successRate, correctionRate, avgTurns, lastUsed |
| `SkillOutcomeTracker` | aceclaw-memory | Tracks Success/Failure/UserCorrected per invocation |
| `SkillOutcome` | aceclaw-memory | Sealed interface: Success, Failure, UserCorrected |

**Metrics persistence**: `{skill}/metrics.json` sidecar file next to SKILL.md.

### 6.2 Estimated Effort

- **New classes**: ~11
- **Modified classes**: ~3 (SystemPromptLoader for description injection, StreamingAgentHandler for outcome tracking)
- **Tests**: 6-8 unit tests, 2 integration tests
- **Effort**: Medium (1-2 weeks)

### 6.3 Security Checkpoint

- [ ] Skill content treated as untrusted (potential prompt injection)
- [ ] !`command` preprocessing runs in sandbox
- [ ] Skill-allowed tools validated against permission system
- [ ] Plugin skills use `plugin-name:skill-name` namespace
- [ ] Enterprise managed skills cannot be overridden

---

## 7. Phase 4: Self-Improvement Loop

**Goal**: Implement pattern detection, skill refinement, and auto-generated skills — the core self-learning loop that makes AceClaw progressively smarter.

**Priority**: P2 (Depends on Phases 1-3)

### 7.1 Tasks

#### 7.1.1 Pattern Detection (~3 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `PatternDetector` | aceclaw-memory | Heuristic extraction of patterns from session history |
| `DetectedPattern` | aceclaw-memory | Record: type, description, frequency, confidence, evidence |
| `PatternType` | aceclaw-memory | Enum: REPEATED_TOOL_SEQUENCE, ERROR_CORRECTION, USER_PREFERENCE, WORKFLOW |

**Detection heuristics**:
- **REPEATED_TOOL_SEQUENCE**: Same tool sequence (3+ calls) appears 3+ times
- **ERROR_CORRECTION**: Tool error followed by successful retry with different params
- **USER_PREFERENCE**: User corrects agent behavior 2+ times same way
- **WORKFLOW**: Multi-step operation performed 3+ times

**Trigger**: Async analysis after each turn on a virtual thread (non-blocking).

#### 7.1.2 Memory Consolidation (~2 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `MemoryConsolidator` | aceclaw-memory | Dedup, merge similar, prune low-relevance entries |
| `RelevanceScorer` | aceclaw-memory | Score memories by recency, frequency, category weight |

**Consolidation triggers**:
- After every N sessions (configurable, default 10)
- When memory file exceeds size threshold
- On daemon idle (background task)

**Consolidation operations**:
- Deduplicate entries with >80% content similarity
- Merge related entries (same tags + similar content)
- Prune entries below relevance threshold (based on recency + access count)
- Promote frequently-accessed project memories to global

#### 7.1.3 Skill Refinement (~3 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `SkillRefinementEngine` | aceclaw-memory | Analyzes failure patterns, proposes SKILL.md updates |
| `RefinementDecision` | aceclaw-memory | Sealed: NoActionNeeded, RefinementRecommended, DisableRecommended |
| `SkillVersionHistory` | aceclaw-memory | Tracks SKILL.md versions for rollback |

**Refinement triggers**:
- Success rate drops below 70% over last 10 invocations
- User correction rate exceeds 30%
- 5+ consecutive failures

**Refinement process**:
1. Gather failure patterns from SkillOutcomeTracker
2. Send to LLM with structured prompt: "Analyze these failures, suggest improvements"
3. Present proposed changes to user for approval
4. On approval: update SKILL.md, reset metrics
5. On rollback: restore previous version

#### 7.1.4 Auto-Generated Skills (~2 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `SkillProposalEngine` | aceclaw-memory | Detects repeated patterns → proposes new skill drafts |
| `SkillProposal` | aceclaw-memory | Record: name, description, content, evidence, confidence |

**Proposal triggers**:
- PatternDetector finds REPEATED_TOOL_SEQUENCE or WORKFLOW pattern 3+ times
- User performs same multi-step operation in similar way 3+ times

**Proposal flow**:
1. PatternDetector identifies candidate pattern
2. SkillProposalEngine drafts SKILL.md from pattern
3. Proposal presented to user: "I noticed you often do X. Create a skill for it?"
4. User approves → skill saved as Draft state
5. After 3 successful invocations → promoted to Active

### 7.2 Estimated Effort

- **New classes**: ~10
- **Modified classes**: ~2 (StreamingAgentHandler for post-turn analysis, AutoMemoryStore for consolidation)
- **Tests**: 8-10 unit tests, 2 integration tests
- **Effort**: High (2-3 weeks)

### 7.3 Security Checkpoint

- [ ] Pattern detection runs on sanitized history (no credential leaks)
- [ ] Auto-generated skills go through Draft → user approval → Active pipeline
- [ ] Refinement engine LLM calls use separate, shorter context (no user conversation)
- [ ] Skill version history enables rollback on regression
- [ ] Memory consolidation preserves HMAC signatures (re-sign after merge)

---

## 8. Phase 5: Agent Teams

**Goal**: Implement multi-agent orchestration — team creation, teammate spawning, inter-agent messaging, and shared task coordination.

**Priority**: P3 (SECONDARY — after self-learning is stable)

### 8.1 Tasks

#### 8.1.1 Team Message Protocol (~8 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `TeamMessage` | aceclaw-core | Sealed interface: DirectMessage, Broadcast, ShutdownRequest, ShutdownResponse, PlanApprovalRequest, PlanApprovalResponse, IdleNotification |
| `DirectMessage` | aceclaw-core | Record: from, to, content, summary, timestamp |
| `Broadcast` | aceclaw-core | Record: from, content, summary, timestamp |
| `ShutdownRequest` | aceclaw-core | Record: requestId, from, reason |
| `ShutdownResponse` | aceclaw-core | Record: requestId, from, approve, reason |
| `PlanApprovalRequest` | aceclaw-core | Record: requestId, from, planContent |
| `PlanApprovalResponse` | aceclaw-core | Record: requestId, from, approve, feedback |
| `IdleNotification` | aceclaw-core | Record: from, peerDmSummaries |

#### 8.1.2 Team Management (~5 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `TeamManager` | aceclaw-core | Creates/deletes teams, spawns/shuts down teammates |
| `TeamConfig` | aceclaw-core | Record: name, description, leadAgentId, members, createdAt |
| `TeamMember` | aceclaw-core | Record: name, agentId, agentType, model, backend, joinedAt |
| `TeammateBackend` | aceclaw-core | Enum: IN_PROCESS, EXTERNAL_PROCESS |
| `TeamHandle` | aceclaw-core | Interface for interacting with an active team |

**Team lifecycle**:
```
TeamCreate → TaskCreate × N → SpawnTeammate × N
  → [teammates self-coordinate via tasks + messages]
  → ShutdownRequest × N → TeamDelete
```

#### 8.1.3 Task Store (~4 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `TaskStore` | aceclaw-core | Concurrent task storage with auto-incrementing IDs |
| `AgentTask` | aceclaw-core | Record: id, subject, description, status, owner, blockedBy, blocks, metadata |
| `TaskStatus` | aceclaw-core | Enum: PENDING, IN_PROGRESS, COMPLETED, DELETED |
| `InMemoryTaskStore` | aceclaw-core | ReentrantReadWriteLock-based for in-process teams |

**Task claiming**:
- Priority: lowest ID first (same as Claude Code)
- Auto-unblocking: completing a task removes it from dependents' blockedBy
- Concurrent access via ReadWriteLock (in-process) or FileLock (cross-process)

#### 8.1.4 Message Transport (~4 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `TeamMessageRouter` | aceclaw-core | Routes messages to correct transport per teammate |
| `InProcessTransport` | aceclaw-core | BlockingQueue-based delivery (zero-copy, in-process) |
| `FileBasedTransport` | aceclaw-core | JSON file inbox with FileLock (cross-process) |
| `TeamMessageInjector` | aceclaw-daemon | Injects received messages between agent turns |

**Dual-mode transport**:
- In-process teammates (virtual threads): `BlockingQueue` — zero I/O, zero serialization
- Cross-process teammates: File-based inbox at `~/.aceclaw/teams/{team}/inboxes/{agent}.json`

#### 8.1.5 Team Session Management (~3 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `TeamSession` | aceclaw-daemon | Per-team state: config, task store, message router, active teammates |
| `TeammateSession` | aceclaw-daemon | Extends AgentSession with team context (name, message inbox) |
| `BackgroundTaskManager` | aceclaw-daemon | Virtual thread pool for background teammates |

#### 8.1.6 Team Tools (~3 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `TeamCreateTool` | aceclaw-tools | Creates a new team with shared task list |
| `SendMessageTool` | aceclaw-tools | Sends messages to teammates (DM, broadcast, shutdown) |
| `TeamDeleteTool` | aceclaw-tools | Deletes team and cleans up resources |

### 8.2 Estimated Effort

- **New classes**: ~27
- **Modified classes**: ~5 (StreamingAgentHandler, SessionManager, RequestRouter, AceClawDaemon)
- **Tests**: 10-12 unit tests, 4 integration tests
- **Effort**: Very High (3-4 weeks)

### 8.3 Security Checkpoint

- [ ] Team members cannot access other teams' data
- [ ] Message size limits enforced (prevent context flooding)
- [ ] Teammate spawning respects parent's permission mode
- [ ] File-based inbox uses FileLock for atomic writes
- [ ] Team deletion cleans up all temporary files
- [ ] Rate limiting on broadcast messages (prevent DoS)

---

## 9. Phase 6: Summary Learning Integration

**Goal**: Connect all learning systems into a unified feedback loop where every execution improves future performance.

**Priority**: P3 (Integration phase)

### 9.1 Tasks

#### 9.1.1 Unified Learning Pipeline (~3 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `LearningPipeline` | aceclaw-memory | Orchestrates post-session analysis across all systems |
| `SessionAnalyzer` | aceclaw-memory | Extracts learnings from completed sessions |
| `LearningEvent` | aceclaw-memory | Sealed: PatternLearned, MistakeRecorded, SkillRefined, PreferenceUpdated |

**Post-session flow**:
```
Session Complete
  → SessionAnalyzer extracts patterns, mistakes, preferences
  → PatternDetector identifies repeated workflows
  → MemoryConsolidator deduplicates and prunes
  → SkillProposalEngine checks for skill candidates
  → SkillRefinementEngine reviews underperforming skills
  → LearningPipeline persists all updates
```

#### 9.1.2 Task Planner Integration (~2 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `PlanLearner` | aceclaw-core | Records plan outcomes (success/replan/failure) to auto-memory |
| `MemoryInformedPlanner` | aceclaw-core | Queries memory for relevant strategies before planning |

**Plan learning loop**:
1. Plan succeeds → record as STRATEGY memory
2. Plan fails → record as MISTAKE memory
3. Plan replans → record both original failure and successful adaptation
4. Next plan request → retrieve relevant strategies and mistakes before LLM call

#### 9.1.3 Hook System (~5 classes)

| Class | Module | Description |
|-------|--------|-------------|
| `HookEvent` | aceclaw-daemon | Sealed interface: SessionStart, PreToolUse, PostToolUse, Stop, PreCompact, etc. |
| `HookDefinition` | aceclaw-daemon | Record: event, matcher, type (command/prompt/agent), command, timeout |
| `HookExecutor` | aceclaw-daemon | Executes hooks on virtual threads with timeout |
| `HookConfig` | aceclaw-daemon | Loads hooks from settings.json |
| `HookResult` | aceclaw-daemon | Sealed: Proceed, Block(feedback), Modify(input) |

**Hook events (initial set)**:

| Event | When | Can Block? |
|-------|------|-----------|
| `SessionStart` | Session begins | No |
| `PreToolUse` | Before tool execution | Yes (exit 2) |
| `PostToolUse` | After successful tool | No |
| `PostToolUseFailure` | After failed tool | No |
| `Stop` | Agent finishes | Yes (exit 2) |
| `PreCompact` | Before context compaction | No |
| `SubagentStart` | Sub-agent spawned | No |
| `SubagentStop` | Sub-agent completed | Yes (exit 2) |
| `TeammateIdle` | Teammate goes idle | Yes (exit 2) |
| `TaskCompleted` | Task marked complete | Yes (exit 2) |

### 9.2 Estimated Effort

- **New classes**: ~10
- **Modified classes**: ~4 (StreamingAgentHandler, StreamingAgentLoop, MessageCompactor)
- **Tests**: 6-8 unit tests, 3 integration tests
- **Effort**: Medium-High (2 weeks)

### 9.3 Security Checkpoint

- [ ] Hook commands run in sandbox with timeout
- [ ] Hook exit code 2 feedback sanitized (no injection)
- [ ] Learning pipeline runs on sanitized data (strip credentials)
- [ ] Plan learning does not persist sensitive file contents
- [ ] Hook configs from managed settings take precedence

---

## 10. Dependency Graph & Critical Path

### Phase Dependencies

```
Phase 1 (Memory)
  │
  ├──→ Phase 2 (Tools + Sub-agents + MCP)
  │       │
  │       ├──→ Phase 3 (Skills)
  │       │       │
  │       │       └──→ Phase 4 (Self-Improvement)
  │       │               │
  │       └───────────────┼──→ Phase 5 (Agent Teams)
  │                       │       │
  │                       └───────┼──→ Phase 6 (Integration)
  │                               │
  └───────────────────────────────┘
```

### Critical Path

```
Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 6
(Memory)   (Tools)   (Skills)   (Self-   (Integra-
                                 Improve)  tion)
~2 wk      ~3 wk    ~2 wk      ~3 wk    ~2 wk
                                          = ~12 weeks
```

Phase 5 (Agent Teams) can be developed in parallel with Phase 4, but requires Phase 2 (sub-agents) to be complete.

### What Blocks What

| Blocker | Blocks | Why |
|---------|--------|-----|
| Phase 1 (Memory) | Everything | All features need memory hierarchy for context loading |
| Phase 2 (Sub-agents) | Phase 3 (Skills) | Skills with `context: fork` need sub-agent infrastructure |
| Phase 2 (Sub-agents) | Phase 5 (Teams) | Teams build on sub-agent spawning mechanism |
| Phase 3 (Skills) | Phase 4 (Self-Improve) | Skill refinement needs skill system to exist |
| Phase 4 (Self-Improve) | Phase 6 (Integration) | Learning pipeline integrates all improvement mechanisms |

---

## 11. Security Checkpoints

### Per-Phase Security Requirements

| Phase | Key Security Concerns | Required Mitigations |
|-------|----------------------|---------------------|
| **Phase 1** | Prompt injection via ACECLAW.md imports, memory file tampering | Content sanitization, HMAC validation, @import path validation, size limits |
| **Phase 2** | Sub-agent privilege escalation, MCP tool poisoning | Least-privilege tool restriction, no-nesting rule, MCP output truncation |
| **Phase 3** | Malicious skill definitions, !`command` injection | Skill sandbox, user approval for new skills, enterprise managed skills |
| **Phase 4** | Auto-generated skill containing harmful instructions | Draft → user approval → Active pipeline, version rollback |
| **Phase 5** | Message flooding, unauthorized team access, resource exhaustion | Rate limiting, team isolation, resource quotas per teammate |
| **Phase 6** | Learning pipeline leaking credentials, hook command injection | Credential scrubbing, sandbox execution, hook output sanitization |

### Cross-Cutting Security Patterns

1. **HMAC-SHA256 signing** — All persisted memory/skill files
2. **Sealed interface exhaustiveness** — All state machines compile-time verified
3. **Principle of least privilege** — Sub-agents and skills get minimal required tools
4. **User approval gates** — New skills, refinements, auto-proposals all require user consent
5. **Sandbox execution** — All external commands (hooks, !`command`, bash) sandboxed
6. **Size limits everywhere** — Memory files, MCP output, message sizes, tool results

---

## 12. Testing Strategy

### Per-Phase Testing

| Phase | Unit Tests | Integration Tests | Mock-Based | What to Verify |
|-------|-----------|------------------|-----------|---------------|
| **Phase 1** | ACECLAW.md loading, import resolution, rule matching, memory retrieval | Full memory hierarchy loaded into system prompt | MockLlmClient | Correct tier precedence, 200-line limit, path-based rule activation |
| **Phase 2** | Sub-agent tool filtering, no-nesting enforcement, transcript serialization | TaskTool spawns sub-agent, runs ReAct loop, returns result | MockLlmClient | Explore agent uses only read tools, background tasks work, MCP tools bridge correctly |
| **Phase 3** | SKILL.md parsing, argument substitution, lazy loading budget | Skill invocation end-to-end (model invoke + fork) | MockLlmClient | Skill descriptions fit in context budget, fork creates isolated sub-agent, outcome tracked |
| **Phase 4** | Pattern detection heuristics, consolidation logic, refinement triggers | Pattern detected → memory stored → affects next session | MockLlmClient | Repeated tool sequence detected, skill proposal generated, refinement approved |
| **Phase 5** | TeamMessage serialization, TaskStore concurrency, message routing | Full team lifecycle: create → tasks → spawn → coordinate → shutdown | MockLlmClient | Messages delivered between turns, tasks claimed correctly, shutdown graceful |
| **Phase 6** | Learning pipeline stages, hook execution, plan learning | Session → analysis → pattern → skill proposal → refinement → better next session | MockLlmClient | End-to-end learning loop produces measurable improvement |

### Testing Infrastructure

1. **MockLlmClient** (existing) — Queue-based programmable responses for E2E tests
2. **TestMemoryStore** — Pre-populated memory for testing retrieval and injection
3. **MockMcpServer** — Local process that simulates MCP tool responses
4. **TeamTestHarness** — Spawns in-process teammates with MockLlmClient for team tests
5. **ArchUnit rules** — Enforce module boundaries (e.g., aceclaw-memory cannot depend on aceclaw-daemon)

### Quality Metrics

| Metric | Target | Phase |
|--------|--------|-------|
| Unit test coverage | >80% for new classes | All |
| Integration test coverage | All happy paths + key error paths | All |
| Build time | <60 seconds (all modules) | All |
| Memory overhead per sub-agent | <5MB | Phase 2 |
| Skill invocation latency | <100ms (excluding LLM call) | Phase 3 |
| Pattern detection latency | <500ms per session | Phase 4 |
| Team message delivery latency | <10ms (in-process) | Phase 5 |

---

## 13. Risk Assessment

| Risk | Probability | Impact | Phase | Mitigation |
|------|------------|--------|-------|------------|
| **Memory hierarchy complexity** — 6 tiers with precedence rules | Medium | Medium | 1 | Start with 3 tiers (project + user + auto), add others incrementally |
| **Tool description bloat** — 40+ lines × 14 tools = significant context | Low | High | 2 | Budget: 2% of context window for tools; measure and tune |
| **Skill quality** — auto-generated skills may be noisy | Medium | Low | 4 | Require 3+ pattern matches + user approval; disable if correction rate >50% |
| **Sub-agent context isolation** — information leaks between parent/child | Low | High | 2 | Strict context separation: sub-agents get fresh history only |
| **Team coordination overhead** — message passing + task claiming latency | Medium | Medium | 5 | In-process BlockingQueue for zero-copy; only use file-based for cross-process |
| **GraalVM compatibility** — new classes may need reflection metadata | Medium | High | All | Test native-image build after each phase; maintain reflect-config.json |
| **Self-improvement feedback loop** — LLM calls for refinement add cost | Medium | Low | 4 | Budget: max 1 refinement call per 10 sessions; use Haiku for analysis |
| **Plugin classloader isolation** — complex and JVM-only | High | Medium | 6 | Defer to post-v1.0; SPI discovery sufficient for now |

---

## Appendix A: Class Count Summary

| Phase | New Classes | Modified Classes | Tests | Cumulative Total |
|-------|------------|-----------------|-------|------------------|
| Phase 1 (Memory) | 14 | 3 | 11-13 | ~14 |
| Phase 2 (Tools + Sub-agents) | 11 | 5 | 8-10 | ~25 |
| Phase 3 (Skills) | 11 | 3 | 8-10 | ~36 |
| Phase 4 (Self-Improvement) | 10 | 2 | 10-12 | ~46 |
| Phase 5 (Agent Teams) | 27 | 5 | 14-16 | ~73 |
| Phase 6 (Integration) | 10 | 4 | 9-11 | ~83 |
| **TOTAL** | **~83** | **~22** | **~60-72** | **83 new + 75 existing = ~158 classes** |

---

## Appendix B: Module Dependency Changes

### New Dependencies (Gradle)

```kotlin
// aceclaw-memory
dependencies {
    api(project(":aceclaw-core"))       // MemoryEntry used in core types
    implementation(libs.jackson.core)    // Existing
    implementation(libs.jackson.yaml)    // NEW: YAML frontmatter parsing
    implementation(libs.slf4j.api)       // Existing
}

// aceclaw-tools (enhanced)
dependencies {
    api(project(":aceclaw-core"))        // Existing
    implementation(project(":aceclaw-memory"))  // NEW: SkillTool needs SkillRegistry
    implementation(project(":aceclaw-security")) // Existing
}

// aceclaw-daemon (enhanced)
dependencies {
    implementation(project(":aceclaw-memory"))  // Already exists for auto-memory
    implementation(project(":aceclaw-mcp"))      // Already exists
}
```

### No New External Dependencies

All features built with existing stack (Jackson, SLF4J, JLine3, Picocli). YAML parsing uses Jackson's `jackson-dataformat-yaml` (already in Jackson ecosystem). No framework additions.

---

## Appendix C: Configuration Additions

### AceClawConfig Additions

```java
// Memory system
String memoryDir();          // default: ~/.aceclaw/projects/<project>/memory/
int memoryMaxEntriesPrompt(); // default: 20
int memoryMaxLinesMd();       // default: 200
long memorySizeLimitBytes();  // default: 50KB per file

// Skills
String skillsDir();           // default: ~/.aceclaw/skills/
boolean skillAutoPropose();   // default: true
int skillMinPatternCount();   // default: 3
double skillRefinementThreshold(); // default: 0.7 (70% success rate)

// Sub-agents
int subAgentMaxTurns();       // default: 25
int subAgentMaxConcurrent();  // default: 3

// Teams
int teamMaxMembers();         // default: 5
int teamMessageMaxLength();   // default: 10000
Duration teamHeartbeatTimeout(); // default: 5 minutes

// Hooks
boolean hooksEnabled();       // default: true
Duration hookTimeout();       // default: 30 seconds
```

---

*This plan was synthesized from research by the AceClaw Architecture Team: OpenClaw Expert, Claude Code Expert, Architect, Security Expert, and Product Owner.*
