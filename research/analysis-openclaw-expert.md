# OpenClaw Expert Analysis: Memory, Tools, Skills & Self-Evolution

> Analysis for AceClaw Architecture Team — Task #1
> Author: openclaw-expert
> Date: 2026-02-17

---

## 1. Executive Summary

This analysis synthesizes findings from four research documents covering OpenClaw and Claude Code's architecture. The key insight: **AceClaw is NOT just a coding agent — it is a general-purpose autonomous agent**. The architecture patterns below apply to any domain (coding, DevOps, data analysis, personal automation, enterprise workflows).

The three most impactful patterns for AceClaw are:
1. **Multi-tier memory hierarchy** — 6 tiers from organization policy to auto-memory, each with different persistence, sharing, and loading behavior
2. **Skills as composable knowledge units** — SKILL.md format with lazy-loading, subagent forking, and description-based auto-invocation
3. **Error-as-feedback self-correction** — No programmatic retry; errors flow back to LLM as `isError: true` tool results, with recovery strategies encoded in the system prompt

---

## 2. Memory System Analysis

### 2.1 Architecture: 6-Tier Hierarchy

Claude Code's memory is the most sophisticated of any coding agent, using a 6-tier hierarchy:

| Tier | Location | Scope | Loaded | Purpose |
|------|----------|-------|--------|---------|
| 1. Managed Policy | `/Library/.../CLAUDE.md` | Organization | Always | Company standards, security |
| 2. Project Memory | `./CLAUDE.md` | Team (VCS) | Always | Architecture, conventions |
| 3. Project Rules | `.claude/rules/*.md` | Team (VCS) | Conditional on paths | Language/domain rules |
| 4. User Memory | `~/.claude/CLAUDE.md` | Personal global | Always | Personal preferences |
| 5. Project Local | `./CLAUDE.local.md` | Personal project | Always | Local-only overrides |
| 6. Auto Memory | `~/.claude/projects/<hash>/memory/` | Per-project auto | First 200 lines | Agent's own learnings |

**Key insight**: Only Tier 6 (auto memory) is written by the agent itself. Tiers 1-5 are human-authored. This separation is critical — humans control policy, the agent controls operational knowledge.

### 2.2 Auto Memory — The Self-Evolution Engine

Auto memory is the primary self-evolution mechanism:

- **Storage**: `~/.claude/projects/<project-hash>/memory/MEMORY.md` + topic files
- **Loading**: First 200 lines of MEMORY.md injected into system prompt at session start
- **Topic files**: `debugging.md`, `patterns.md`, etc. — read on-demand via file tools, NOT auto-loaded
- **Content**: Build commands, debugging insights, architecture notes, user preferences
- **200-line hard limit**: Forces concise index; detailed notes go to topic files

**What gets remembered:**
- Patterns: "This project uses pnpm, not npm"
- Solutions: "Vite build requires --legacy-peer-deps"
- Architecture: "Auth is in src/auth/, uses JWT tokens"
- Preferences: "User prefers functional style over OOP"

### 2.3 CLAUDE.md Import System

CLAUDE.md files support recursive imports via `@path/to/file` syntax:
- Max depth: 5 hops
- Relative paths resolve relative to containing file
- Not evaluated inside code blocks
- First-time imports require user approval

### 2.4 Path-Specific Conditional Rules

Rules in `.claude/rules/*.md` can target specific files:
```yaml
---
paths:
  - "src/api/**/*.ts"
---
# API rules that only load when working with API files
```

### 2.5 OpenClaw Memory (Contrast)

OpenClaw's memory is simpler and less persistent:
- **Gateway state**: In-memory, lost on restart
- **ClawHub registry**: Static skill catalog, not user-specific memory
- **No auto-memory**: No cross-session learning
- **No memory hierarchy**: Single flat config

**Lesson**: OpenClaw's memory weakness is a primary reason it cannot improve over time. AceClaw must have Claude Code-class memory from day one.

---

## 3. Tools System Analysis

### 3.1 Claude Code's 4-Layer Tool Architecture

```
Layer 1: Core Built-in Tools (14+)
  Read, Write, Edit, Bash, Glob, Grep, WebFetch, WebSearch, etc.

Layer 2: MCP External Tools (dynamic)
  mcp__github__search, mcp__sentry__list_issues, etc.
  - stdio (local) or HTTP (remote) transport
  - OAuth 2.0 authentication
  - Dynamic tool updates via list_changed notifications

Layer 3: Skill-Provided Tools (lazy-loaded)
  Skills can grant allowed-tools permissions when active

Layer 4: Plugin-Bundled Tools (distributable)
  Plugins bundle MCP servers, hooks, agents, skills into packages
```

### 3.2 OpenClaw's 4 Extension Types

OpenClaw has a different but analogous extension model:

| Type | Purpose | Example |
|------|---------|---------|
| **Channels** | Messaging platform adapters | WhatsApp, Telegram, Slack |
| **Tools** | Executable functions | Browser control, API calls |
| **Providers** | LLM backends | Claude, GPT, DeepSeek, local |
| **Memory** | State storage | Gateway memory, ClawHub |

**Key difference**: OpenClaw's "Channels" (platform adapters) map to AceClaw's transport layer, not tools. OpenClaw treats each messaging platform as an extension point.

### 3.3 Tool Description as Prompt Engineering

Claude Code's tool descriptions are NOT just API schemas. They are **behavioral guidance** encoding 7 functions:

1. **Capability declaration**: "What this tool does"
2. **Anti-pattern prevention**: "Use Read instead of cat"
3. **Behavioral guidelines**: "Only create commits when requested"
4. **Format specification**: "Use HEREDOC for commit messages"
5. **Workflow templates**: Multi-step procedures (git commit flow)
6. **Safety constraints**: "NEVER skip hooks (--no-verify)"
7. **Optimization hints**: "Make all independent calls in parallel"

**Quantified impact**: Claude Code uses ~1,067 tokens for the Bash tool description alone. AceClaw currently uses ~5-10 lines per tool. **This is the single highest-ROI improvement area** — expanding tool descriptions to 40-80 lines per tool.

### 3.4 MCP Integration Architecture

MCP (Model Context Protocol) is the standard for external tool integration:

- **Transport**: stdio (local processes), HTTP (remote), SSE (deprecated)
- **Capabilities**: Tools, Resources, Prompts, Sampling
- **Naming**: `mcp__<server>__<tool>` convention
- **Config**: `.mcp.json` with env var expansion (`${VAR}`, `${VAR:-default}`)
- **Scope**: User → Project → Managed (enterprise control)
- **Dynamic loading**: MCP Tool Search defers tools when >10% of context window

**AceClaw already has basic MCP client support**. Gaps: HTTP transport, OAuth 2.0, tool search (lazy loading), managed MCP policies.

### 3.5 Tool Result Processing

- **Truncation**: 30K chars with 40%/60% head/tail split
- **Error feedback**: `is_error: true` flows back to LLM for self-correction
- **MCP limits**: 25K tokens default, configurable via `MAX_MCP_OUTPUT_TOKENS`
- **Format**: Tool results in single user message, `tool_result` blocks FIRST (before text)

---

## 4. Skills System Analysis

### 4.1 SKILL.md Format

Skills are composable knowledge units defined as Markdown with YAML frontmatter:

```yaml
---
name: explain-code
description: Explains code with visual diagrams and analogies. Use when
  explaining how code works or when the user asks "how does this work?"
argument-hint: [file-path]
disable-model-invocation: false
user-invocable: true
allowed-tools: Read, Glob, Grep
model: sonnet
context: fork
agent: Explore
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/validate.sh"
---

Skill instructions in markdown body...
```

### 4.2 Key Frontmatter Fields

| Field | Description | Default |
|-------|-------------|---------|
| `name` | Slash command name (lowercase, hyphens) | Directory name |
| `description` | When to use (Claude reads this for auto-invocation) | First paragraph |
| `argument-hint` | Autocomplete hint: `[issue-number]` | None |
| `disable-model-invocation` | `true` = user-only, not in context | false |
| `user-invocable` | `false` = model-only, hidden from `/` menu | true |
| `allowed-tools` | Tools auto-approved when skill is active | None |
| `model` | Override model for skill execution | Inherit |
| `context` | `fork` = run in isolated subagent | None (inline) |
| `agent` | Subagent type when `context: fork` | None |
| `hooks` | Lifecycle hooks scoped to this skill | None |

### 4.3 Invocation Model: Dual-Path

```
User invocation:  /explain-code src/auth.ts
  → Skill lookup → Argument substitution → Content injection

Model invocation: (Claude sees description, decides to use skill)
  → Skill tool call → Same expansion pipeline
```

**Argument substitution:**
- `$ARGUMENTS` → all args as string
- `$ARGUMENTS[N]` or `$N` → Nth arg
- `${CLAUDE_SESSION_ID}` → session ID
- `!command` → shell command output (preprocessing)

### 4.4 Lazy Loading Optimization

- **Descriptions always in context** (so Claude knows what's available)
- **Full content only loaded on invocation** (saves context tokens)
- **Description budget**: 2% of context window (16K chars default)
- **`SLASH_COMMAND_TOOL_CHAR_BUDGET`** env var override

### 4.5 Skill Discovery Hierarchy

| Location | Scope | Priority |
|----------|-------|----------|
| Enterprise (managed) | Organization | 1 (highest) |
| Personal (`~/.claude/skills/`) | All projects | 2 |
| Project (`.claude/skills/`) | This project | 3 |
| Plugin (`<plugin>/skills/`) | Where enabled | 4 (lowest) |

**Monorepo support**: Skills in nested `.claude/skills/` directories (e.g., `packages/frontend/.claude/skills/`) are auto-discovered when working in those directories.

### 4.6 OpenClaw Skills (ClawHub)

OpenClaw's ClawHub is a marketplace model:
- **700+ community skills** (YAML + JS/TS handlers)
- **Static pull-based discovery**: Periodically checks registry for updates
- **No learning**: Skills don't adapt based on usage
- **Security problems**: Credential stealers discovered in community skills within 48 hours of going viral

**Key difference from Claude Code**: ClawHub is a **distribution channel** (like npm for skills). Claude Code skills are **local knowledge units**. AceClaw should support both: local SKILL.md (like Claude Code) + remote registry (like ClawHub, but with security).

### 4.7 Skill Composition Patterns

1. **Inline skill**: Content injected directly into conversation (default)
2. **Forked skill**: `context: fork` runs in isolated subagent (separate context window)
3. **Chained skills**: One skill's output feeds into another (via conversation flow)
4. **Subagent-backed skill**: `context: fork` + `agent: Explore` uses specific subagent type
5. **Hook-enhanced skill**: Skills can define their own lifecycle hooks

---

## 5. Self-Evolution Analysis

### 5.1 How Claude Code "Learns"

Claude Code does NOT fine-tune models. Its learning is entirely through **persistent context manipulation**:

```
┌─────────────────────────────────────────────────────┐
│              SELF-EVOLUTION MECHANISMS                │
├─────────────────────────────────────────────────────┤
│                                                      │
│  1. AUTO MEMORY (cross-session persistence)          │
│     Agent writes MEMORY.md → loaded next session     │
│     "Discovered: use --legacy-peer-deps for vite"    │
│                                                      │
│  2. ERROR FEEDBACK (within-session learning)         │
│     isError:true tool results → LLM self-corrects   │
│     No programmatic retry; LLM decides strategy     │
│                                                      │
│  3. PROMPT-ENCODED STRATEGIES (hardcoded learning)   │
│     135+ fragments encode recovery patterns:         │
│     - Pre-commit hook failure → new commit           │
│     - Edit uniqueness failure → expand context       │
│     - Sandbox permission error → retry without       │
│                                                      │
│  4. HOOK-BASED ENFORCEMENT (deterministic control)   │
│     PreToolUse → block/modify tool inputs            │
│     PostToolUseFailure → log/alert on errors         │
│     Stop → verify work before completion             │
│     TaskCompleted → quality gates                    │
│                                                      │
│  5. CONTEXT COMPACTION (memory management)           │
│     Server-side: API summarizes automatically        │
│     Context editing: selective tool/thinking clear   │
│     Client-side: 3-phase prune→summarize             │
│     PreCompact hooks: save state before compress     │
│                                                      │
│  6. SYSTEM REMINDERS (real-time state awareness)     │
│     "File modified since last read"                  │
│     "Token budget at 75%"                            │
│     "Plan mode active"                               │
│                                                      │
└─────────────────────────────────────────────────────┘
```

### 5.2 What Claude Code Does NOT Do

Important gaps in Claude Code's self-evolution:
- **No skill auto-generation**: Skills are static, human-authored
- **No usage metrics**: No tracking of skill invocation count or success rate
- **No skill refinement**: No automatic improvement of skill instructions
- **No pattern detection**: No automatic identification of repeated workflows
- **No adaptive scoring**: Description matching only, no success-weighted ranking

**AceClaw opportunity**: The AceClaw research proposes an **adaptive skill system** with metrics, scoring, and auto-generation that goes beyond both Claude Code and OpenClaw.

### 5.3 OpenClaw Self-Evolution

OpenClaw has minimal self-evolution:
- **No auto-memory**: Skills and knowledge don't persist across sessions
- **No error learning**: Errors retried programmatically, not adaptively
- **Static skills**: ClawHub skills never change based on usage
- **No pattern detection**: No mechanism to identify recurring workflows
- **Gateway state loss**: All in-memory state lost on restart

### 5.4 Extended Thinking Evolution

Extended thinking has evolved rapidly:

| Phase | When | Mechanism |
|-------|------|-----------|
| Manual budget | 2025 | `budget_tokens: N` (user-specified) |
| Interleaved | Mid-2025 | Thinking between tool calls (beta header) |
| Adaptive | Late 2025 | Model decides depth, effort parameter |
| Opus 4.6 (current) | 2026 | Adaptive only, manual deprecated, interleaved automatic |

**Key for AceClaw**: Adaptive thinking with effort levels ("think harder", "ultrathink") is the current best practice. AceClaw should support regex-based thinking intensity detection.

### 5.5 Memory Tool (Claude API)

A newer API-level persistent memory mechanism (beta Aug 2025):
- Client-side tool with commands: view, create, str_replace, insert, delete, rename
- `/memories/` directory persists across sessions
- System prompt injection: "ALWAYS VIEW YOUR MEMORY DIRECTORY BEFORE DOING ANYTHING ELSE"
- Combined with context editing (`exclude_tools: ["memory"]`) for indefinite workflows
- **ASSUME INTERRUPTION protocol**: Agent saves progress assuming context may be cleared

---

## 6. Key Patterns for AceClaw (General-Purpose Agent)

### 6.1 Domain-Agnostic Memory Architecture

Since AceClaw is NOT limited to coding, the memory system must be domain-agnostic:

| Memory Type | Coding Example | DevOps Example | Data Analysis Example |
|-------------|---------------|----------------|----------------------|
| Project Memory | "Use Java 21 + Gradle" | "Deploy to k8s cluster X" | "Data in PostgreSQL schema Y" |
| Auto Memory | "Build: ./gradlew clean build" | "Rollback: kubectl rollout undo" | "ETL: run_pipeline.sh --full" |
| Rules | "API endpoints need validation" | "All deployments need approval" | "PII columns must be masked" |
| Skills | /commit, /review-pr | /deploy, /rollback | /analyze, /visualize |

### 6.2 Skill Composition for Any Domain

The SKILL.md format is inherently domain-agnostic:
- Description drives auto-invocation (works for any task domain)
- `context: fork` isolates domain-specific workflows
- `allowed-tools` grants tool access per-skill (different tools for different domains)
- Hooks provide domain-specific validation

### 6.3 Self-Evolution Priorities for AceClaw

| Priority | Pattern | Impact | Effort |
|----------|---------|--------|--------|
| **P0** | Auto Memory (MEMORY.md + topic files) | Very High | Medium |
| **P0** | Error-as-feedback (isError → LLM self-corrects) | Very High | Low (already done) |
| **P0** | Tool descriptions as prompt engineering (40+ lines) | Very High | Low |
| **P0** | ACECLAW.md hierarchy (6-tier) | High | Medium |
| **P1** | Skills system (SKILL.md + /name invocation) | High | Medium |
| **P1** | Hook system (14+ lifecycle events) | High | High |
| **P1** | Adaptive thinking (effort levels) | High | Medium |
| **P2** | Server-side compaction (compact_20260112) | Medium | Medium |
| **P2** | Context editing (tool + thinking clearing) | Medium | Medium |
| **P2** | Skill metrics + adaptive scoring | Medium | High |
| **P3** | Memory Tool (API-level persistent files) | Medium | Medium |
| **P3** | Skill auto-generation from patterns | Medium | Very High |

---

## 7. Architecture Recommendations

### 7.1 Memory System Design

```
~/.aceclaw/
  ACECLAW.md                           # Tier 4: User global memory
  rules/*.md                           # Tier 4: User global rules
  skills/<name>/SKILL.md               # Personal skills
  projects/<project-hash>/memory/      # Tier 6: Auto memory
    MEMORY.md                          # Index (200 lines → system prompt)
    debugging.md                       # Topic file
    patterns.md                        # Topic file

{project}/
  ACECLAW.md or .aceclaw/ACECLAW.md    # Tier 2: Project memory
  ACECLAW.local.md                     # Tier 5: Project local
  .aceclaw/
    rules/*.md                         # Tier 3: Project rules
    skills/<name>/SKILL.md             # Project skills
    agents/<name>.md                   # Custom sub-agents

/Library/.../AceClaw/ACECLAW.md        # Tier 1: Managed policy (enterprise)
```

### 7.2 Skill Registry Design

```java
public record SkillDefinition(
    String name,
    String description,
    String argumentHint,
    boolean disableModelInvocation,
    boolean userInvocable,
    List<String> allowedTools,
    String model,
    String context,      // null or "fork"
    String agent,        // subagent type when fork
    Map<String, List<HookConfig>> hooks,
    String content       // full markdown body
) {}

public sealed interface SkillSource
    permits Enterprise, Personal, Project, Plugin {
    int priority();
    Path basePath();
}

public interface SkillRegistry {
    Optional<SkillDefinition> lookup(String name);
    List<SkillSummary> listDescriptions();  // for context injection
    String expand(SkillDefinition skill, String arguments);
    void reload();  // WatchService-triggered
}
```

### 7.3 Hook System Design

```java
public enum HookEvent {
    SESSION_START, USER_PROMPT_SUBMIT,
    PRE_TOOL_USE, PERMISSION_REQUEST,
    POST_TOOL_USE, POST_TOOL_USE_FAILURE,
    NOTIFICATION, SUBAGENT_START, SUBAGENT_STOP,
    STOP, TEAMMATE_IDLE, TASK_COMPLETED,
    PRE_COMPACT, SESSION_END
}

public sealed interface HookType
    permits CommandHook, PromptHook, AgentHook {
    record CommandHook(String command, int timeout, boolean async) implements HookType {}
    record PromptHook(String prompt, int timeout) implements HookType {}
    record AgentHook(String prompt, int timeout) implements HookType {}
}

public sealed interface HookResult
    permits Proceed, Block, ModifyInput {
    record Proceed(String stdout) implements HookResult {}
    record Block(String feedback) implements HookResult {}
    record ModifyInput(String modifiedJson) implements HookResult {}
}
```

### 7.4 System Prompt Fragment Architecture

```java
public sealed interface PromptFragment
    permits Static, Conditional, Dynamic, ToolDescription, Memory {

    record Static(String id, String content) implements PromptFragment {}
    record Conditional(String id, Predicate<AgentContext> condition, String content) implements PromptFragment {}
    record Dynamic(String id, Function<AgentContext, String> generator) implements PromptFragment {}
    record ToolDescription(String toolName, String description) implements PromptFragment {}
    record Memory(MemoryTier tier, String content) implements PromptFragment {}
}

public class PromptAssembler {
    private final List<PromptFragment> fragments;

    public String assemble(AgentContext context) {
        return fragments.stream()
            .filter(f -> switch (f) {
                case Conditional c -> c.condition().test(context);
                default -> true;
            })
            .map(f -> switch (f) {
                case Static s -> s.content();
                case Dynamic d -> d.generator().apply(context);
                case Conditional c -> c.content();
                case ToolDescription t -> t.description();
                case Memory m -> m.content();
            })
            .collect(Collectors.joining("\n\n"));
    }
}
```

---

## 8. Critical Findings Summary

1. **Memory hierarchy is the foundation**: 6-tier with different persistence/sharing per tier. Auto memory (agent writes MEMORY.md) is the self-evolution engine.

2. **Tool descriptions ARE the prompt engineering**: 40-80 lines per tool with anti-patterns, substitution rules, workflow templates, safety constraints. This is the single highest-ROI improvement.

3. **Skills are lazy-loaded composable knowledge**: SKILL.md with YAML frontmatter, description-based auto-invocation, forked subagent execution, and scoped hooks.

4. **Error-as-feedback, not programmatic retry**: `isError: true` flows to LLM; recovery strategies are prompt-encoded. The LLM decides how to recover, enabling creative problem-solving.

5. **135+ prompt fragments, not a monolithic prompt**: Conditional assembly based on context, mode, provider, and active tools. This is how Claude Code achieves sophisticated behavior without complex code.

6. **OpenClaw's weaknesses are AceClaw's opportunities**: No persistent memory, no cross-session learning, static skills, security vulnerabilities. AceClaw can do better on all fronts.

7. **Hook system provides deterministic control**: 14+ lifecycle events with 3 execution types (command, prompt, agent). Hooks can block, modify, and validate — providing guardrails around LLM behavior.

8. **Context compaction has three layers**: Server-side (API), context editing (selective clearing), and client-side (SDK/CLI). Server-side is newest and recommended for Anthropic provider.

---

## Sources

All findings sourced from:
- `/research/openclaw-architecture.md` (977 lines)
- `/research/openclaw-agent-orchestration.md` (1074 lines)
- `/research/openclaw-skills-system.md` (1099 lines)
- `/research/openclaw-self-evolution.md` (1111 lines)
