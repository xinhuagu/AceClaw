# Analysis: Claude Code Architecture for AceClaw Self-Learning Agent

**Analyst**: Claude Code Expert
**Date**: 2026-02-17
**Task**: Research Claude Code's auto-memory, tools, sub-agents, and agent teams architecture to inform AceClaw's self-learning and orchestration design.

---

## 1. Executive Summary

Claude Code implements a sophisticated multi-layer architecture for memory persistence, tool orchestration, sub-agent delegation, and multi-agent coordination. The key insight for AceClaw is that **Claude Code's architecture is domain-agnostic** — its memory, tool, and agent systems are general-purpose primitives that happen to be applied to coding. AceClaw can adopt these patterns for any autonomous agent domain.

**Five architectural pillars:**
1. **Layered Memory** — 8+ memory scopes from managed policy to auto-memory, with recursive directory walking and on-demand loading
2. **Rich Tool Descriptions** — Tools are not just functions; their descriptions are 20-80 line documents encoding anti-patterns, substitution rules, and contextual guidance
3. **Composable System Prompt** — 135+ fragments assembled dynamically from base prompt + environment + git + memory + instructions
4. **Depth-1 Sub-Agents** — Isolated child agents with fresh context, no nesting, model/tool/permission scoping per agent type
5. **Agent Teams** — Full separate instances coordinating via shared task lists and direct messaging

---

## 2. Auto-Memory Architecture

### 2.1 Memory Hierarchy (8 Scopes)

Claude Code uses 8 distinct memory scopes, evaluated from highest to lowest precedence:

| Priority | Scope | Location | Loaded When | Shared |
|----------|-------|----------|-------------|--------|
| 1 | Managed policy | `/etc/claude-code/CLAUDE.md` | Always at launch | Organization-wide |
| 2 | User memory | `~/.claude/CLAUDE.md` | Always at launch | Personal, all projects |
| 3 | User rules | `~/.claude/rules/*.md` | Always at launch | Personal, all projects |
| 4 | Project memory | `./CLAUDE.md` or `./.claude/CLAUDE.md` | Always at launch | Team via VCS |
| 5 | Project rules | `./.claude/rules/*.md` | Always (conditional with `paths` frontmatter) | Team via VCS |
| 6 | Local memory | `./CLAUDE.local.md` | Always at launch | Personal, gitignored |
| 7 | Subdirectory memory | `./subdir/CLAUDE.md` | **On-demand** when accessing files in that subtree | Team via VCS |
| 8 | Auto memory | `~/.claude/projects/<project>/memory/` | First 200 lines of `MEMORY.md` at launch | Personal, per-project |

### 2.2 Key Memory Design Patterns

**Recursive directory walking**: Starting from cwd, walks UP the directory tree reading CLAUDE.md files. Parent directories provide broader context; child directories provide specific overrides. This is a powerful pattern for hierarchical instruction injection.

**On-demand loading**: Subdirectory CLAUDE.md files are NOT loaded at startup. They're loaded lazily when Claude reads files in those directories. This keeps initial context focused and prevents token waste. Critical for large monorepos.

**Import syntax**: CLAUDE.md files can import other files using `@path/to/file` syntax with max depth 5. Enables modular, composable instruction sets without duplication.

**Conditional rules**: Rules files support `paths` frontmatter with glob patterns. A rule with `paths: ["src/api/**/*.ts"]` only activates when working on matching files. This is "adaptive system prompt" — the agent's instructions change based on what it's doing.

**Auto-memory structure**: The auto-memory directory (`~/.claude/projects/<project>/memory/`) uses a curated index pattern:
- `MEMORY.md` — concise index file, first 200 lines injected into prompt
- Topic files (e.g., `patterns.md`, `debugging.md`) — detailed notes linked from MEMORY.md
- The agent itself manages these files using Read/Write/Edit tools

### 2.3 Sub-Agent Memory Persistence

Sub-agents can have their own persistent memory across sessions:

| Scope | Location | Use Case |
|-------|----------|----------|
| `user` | `~/.claude/agent-memory/<name>/` | Learnings across all projects |
| `project` | `.claude/agent-memory/<name>/` | Project-specific, VCS-shareable |
| `local` | `.claude/agent-memory-local/<name>/` | Project-specific, not in VCS |

When memory is enabled for a sub-agent:
- System prompt includes instructions for reading/writing the memory directory
- First 200 lines of `MEMORY.md` are included in prompt
- Read, Write, Edit tools are auto-enabled for memory management
- Agent curates MEMORY.md as a concise index with topic files for details

### 2.4 Implications for AceClaw

**What AceClaw should adopt:**
1. The 8-scope hierarchy — especially managed policy (enterprise), conditional rules (path-based), and on-demand subdirectory loading
2. The auto-memory "curated index" pattern — agent manages its own MEMORY.md with 200-line cap
3. Per-agent memory scoping (user/project/local) for sub-agents
4. Import syntax for modular instruction composition
5. Directory walking for hierarchical context injection

**What AceClaw should improve:**
1. **Semantic memory search** — Claude Code's auto-memory is file-based. AceClaw's daemon architecture enables in-memory vector search or BM25 retrieval for faster, more relevant memory recall
2. **Background memory consolidation** — Claude Code can't consolidate during idle time. AceClaw's daemon can merge duplicates, prune stale memories, and refine entries in the background
3. **Cross-session memory flow** — Claude Code memories are project-scoped. AceClaw's daemon can track memory evolution across sessions and projects

---

## 3. Tool System Architecture

### 3.1 Tool Description Philosophy

Claude Code's tool descriptions are NOT simple one-liners. They are 20-80 line documents that serve as **instruction manuals** guiding the LLM's tool selection and usage. Each description includes:

1. **Primary purpose** — what the tool does
2. **Usage guidelines** — when to use it, when NOT to use it
3. **Anti-patterns** — explicit "NEVER do X" rules
4. **Substitution rules** — "Use this tool INSTEAD of Bash for..."
5. **Parameter details** — format, defaults, constraints
6. **Context-specific guidance** — platform-specific notes, timeout recommendations

Example from Claude Code's system prompt (tool usage section):
```
- Do NOT use the Bash to run commands when a relevant dedicated tool is provided:
  - To read files use Read instead of cat, head, tail, or sed
  - To edit files use Edit instead of sed or awk
  - To create files use Write instead of cat with heredoc or echo redirection
  - To search for files use Glob instead of find or ls
  - To search the content of files, use Grep instead of grep or rg
```

### 3.2 Claude Code's 20+ Tools

Claude Code ships with 20+ tools organized by category:

| Category | Tools | Permission Level |
|----------|-------|-----------------|
| File Read | Read, Glob, Grep, LS, NotebookRead | No permission needed |
| File Write | Write, Edit, NotebookEdit | Permission required |
| Execution | Bash, BashOutput, KillShell | Permission required |
| Agent | Task, TaskOutput | Task requires permission |
| Planning | ExitPlanMode, AskUserQuestion | Various |
| Task Management | TodoRead, TodoWrite, TaskCreate, TaskUpdate, TaskList, TaskGet | No permission |
| Web | WebFetch, WebSearch | WebFetch requires permission |
| Communication | Skill, SendMessage | Various |
| Team | TeamCreate, TeamDelete | Various |

### 3.3 Tool Selection Guidance in System Prompt

Claude Code's system prompt includes detailed tool selection heuristics:

1. **Glob/Grep over Bash** — Always prefer dedicated search tools over `grep`/`find` commands
2. **Read before Edit** — Must read a file before editing it (enforced)
3. **Edit over Write** — Prefer editing existing files to creating new ones
4. **Task for complex searches** — Use sub-agents for open-ended exploration requiring multiple rounds
5. **Direct tools for simple queries** — Don't use Task when Glob/Grep suffices
6. **Parallel tool calls** — Make independent tool calls in parallel; chain dependent ones sequentially

### 3.4 AceClaw's Current Tool Set (12 Tools)

AceClaw currently implements 12 tools:

| Tool | Name | Notes |
|------|------|-------|
| ReadFileTool | `read_file` | 2000 line default, line numbers |
| WriteFileTool | `write_file` | Creates parents, overwrites |
| EditFileTool | `edit_file` | Exact string replacement |
| BashExecTool | `bash` | 120s default, 600s max, 30K char cap |
| GlobSearchTool | `glob` | 200 max results, 20 depth |
| GrepSearchTool | `grep` | 50 files, 500 matches, regex |
| ListDirTool | `list_directory` | 1000 entries, formatted table |
| AppleScriptTool | `applescript` | macOS only, 30s timeout |
| ScreenCaptureTool | `screen_capture` | macOS only, OCR via Vision |
| WebFetchTool | `web_fetch` | Jsoup HTML-to-text, 30K char |
| WebSearchTool | `web_search` | Brave Search API |
| BrowserTool | `browser` | Playwright headless Chrome |

### 3.5 Implications for AceClaw

**Gaps vs Claude Code:**
1. **No TaskTool** — Cannot spawn sub-agents (P0 need)
2. **No TaskOutputTool** — Cannot retrieve background task results
3. **No TodoWrite/TodoRead** — No structured task tracking for the agent
4. **No AskUserQuestion** — No structured multiple-choice user interaction
5. **No NotebookRead/NotebookEdit** — Jupyter support (lower priority)
6. **No KillShell** — Cannot terminate running processes
7. **No SendMessage** — No inter-agent messaging (needed for teams)

**What AceClaw should improve:**
1. **Enrich tool descriptions** — Current descriptions are minimal (~5 lines). Need 20-40 lines with anti-patterns and substitution rules
2. **Add tool selection guidance to system prompt** — Explicit rules for when to use which tool
3. **Enforce read-before-edit** — Claude Code enforces this at the tool level
4. **Add parallel tool call guidance** — System prompt should tell the LLM when to parallelize

---

## 4. System Prompt Architecture

### 4.1 Composition Model (135+ Fragments)

Claude Code's system prompt is NOT a single monolithic string. It's **dynamically composed** from 135+ fragments:

1. **Base prompt** (~100 lines) — Core identity, principles, tool usage rules
2. **Environment context** — Working directory, platform, OS version, date, model name
3. **Git context** — Current branch, recent commits, git status
4. **Memory files** — CLAUDE.md hierarchy (8 scopes)
5. **Tool descriptions** — Each tool contributes its own description fragment
6. **Permission context** — Current permission mode, allowed/denied rules
7. **Active features** — Thinking mode, effort level, output style
8. **MCP server descriptions** — External tool server documentation
9. **Skill preloads** — Active skill content injected into context
10. **Agent team context** — Team configuration, teammate names, task list state

### 4.2 Key System Prompt Sections

From analyzing Claude Code's actual system prompt structure:

**Identity & Principles** (~20 lines):
- "You are Claude Code, Anthropic's official CLI for Claude"
- Core behavioral rules (concise output, avoid unnecessary files, security awareness)

**Doing Tasks** (~30 lines):
- Task execution guidelines
- Over-engineering avoidance rules
- Backwards-compatibility avoidance
- Help/feedback directions

**Using Your Tools** (~20 lines):
- Dedicated tool preference over Bash
- Sub-agent delegation rules
- Parallel vs sequential tool calls

**Tone and Style** (~10 lines):
- No emojis unless asked
- Short, concise responses
- File:line references for code

**Committing Changes** (~40 lines):
- Git safety protocol (never force push, never amend without asking)
- Commit message format with HEREDOC
- Co-authored-by requirements

**Creating Pull Requests** (~20 lines):
- PR template with summary and test plan
- gh CLI usage

**Context Injection** (~variable):
- Environment details (dynamically generated)
- Git status snapshot
- Memory/instruction files

### 4.3 AceClaw's Current System Prompt (134 Lines)

AceClaw already has a comprehensive system prompt at `aceclaw-daemon/src/main/resources/system-prompt.md` covering:
- Core identity (enterprise AI coding agent)
- 11 system principles
- 5 task execution rules
- Tool usage guidelines
- Autonomy directives (max 25 tool calls)
- 7 error recovery strategies
- Git and communication guidelines
- MCP tool information

The `SystemPromptLoader` dynamically composes: base prompt + environment + git + ACECLAW.md hierarchy + auto-memory.

### 4.4 Implications for AceClaw

**Current gaps:**
1. **Tool selection substitution rules** — Missing explicit "use X instead of Y" directives
2. **Anti-pattern documentation** — Missing "NEVER use Bash for file operations" rules
3. **Parallel execution guidance** — Missing rules for when to parallelize tool calls
4. **Permission context injection** — Not injecting current permission state into prompt
5. **Conditional fragment loading** — No path-based conditional rules yet
6. **Dynamic context freshness** — Git context is static snapshot; could refresh between turns

---

## 5. Sub-Agent Architecture

### 5.1 Core Design: Depth-1, No Nesting

Claude Code enforces a **strict no-nesting rule**: sub-agents cannot spawn other sub-agents. This is a hard architectural constraint.

**Rationale:**
- Prevents infinite nesting and resource exhaustion
- Keeps execution model simple and predictable
- Forces the parent to be the sole orchestrator

**Enforcement:**
- Task tool is never available to sub-agents
- Even if Task is listed in a sub-agent's tools, it has no effect

### 5.2 Built-in Agent Types

| Agent | Model | Tools | Purpose |
|-------|-------|-------|---------|
| **Explore** | Haiku (fast, cheap) | Read-only | Fast codebase search and exploration |
| **Plan** | Inherit from parent | Read-only | Research for plan mode context gathering |
| **General-purpose** | Inherit from parent | All tools | Complex multi-step tasks |
| **Bash** | Inherit | Bash only | Terminal commands in isolated context |
| **statusline-setup** | Sonnet | Read, Edit | Status line configuration |
| **Claude Code Guide** | Haiku | Read-only + WebFetch/WebSearch | Questions about Claude Code |

### 5.3 Custom Agent Configuration

Custom agents defined as Markdown files with YAML frontmatter in `.claude/agents/`:

```yaml
---
name: code-reviewer
description: Reviews code for quality and best practices
tools: Read, Glob, Grep, Bash
disallowedTools: Write, Edit
model: sonnet
permissionMode: default
maxTurns: 25
skills:
  - api-conventions
mcpServers:
  - slack
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/validate-command.sh"
memory: user
---
System prompt body goes here...
```

**Priority resolution** (highest first):
1. `--agents` CLI flag (session-only)
2. `.claude/agents/` (project)
3. `~/.claude/agents/` (user global)
4. Plugin `agents/` directory

### 5.4 Context Sharing Model

**What sub-agents receive:**
- Their own system prompt (from markdown body)
- Basic environment details (working dir, platform, date)
- CLAUDE.md files from the working directory
- Preloaded skills (if specified)
- MCP server connections (if specified)

**What sub-agents do NOT receive:**
- Parent conversation history
- Full Claude Code system prompt
- Skills from parent conversation
- Other sub-agents' results or state

### 5.5 Foreground vs Background Execution

| Aspect | Foreground | Background |
|--------|-----------|------------|
| Main conversation | Blocked | Continues working |
| Permission prompts | Passed to user | Pre-approved only |
| MCP tools | Available | NOT available |
| Clarifying questions | Work | Fail (agent continues) |
| Output retrieval | Inline | Via TaskOutput tool |

### 5.6 Transcript Persistence and Resumption

Sub-agent transcripts stored as JSONL:
```
~/.claude/projects/{project}/{sessionId}/subagents/agent-{agentId}.jsonl
```

Key properties:
- Persist independently of main conversation
- Main conversation compaction does NOT affect sub-agent transcripts
- Can resume by agent ID with full context preserved
- Auto-cleanup based on `cleanupPeriodDays` (default: 30)

### 5.7 Plugin Agent Patterns (Feature-Dev Example)

The feature-dev plugin demonstrates production agent orchestration:

```
Phase 1: Discovery          -> Main conversation
Phase 2: Codebase Explore   -> 2-3 code-explorer agents IN PARALLEL
Phase 3: Clarifying Qs      -> Main conversation
Phase 4: Architecture       -> 2-3 code-architect agents IN PARALLEL
Phase 5: Implementation     -> Main conversation (after approval)
Phase 6: Quality Review     -> 3 code-reviewer agents IN PARALLEL
Phase 7: Summary            -> Main conversation
```

**Key patterns:**
- Read-only agents use cheaper models (Haiku/Sonnet) for speed
- Write-capable agents inherit the main model
- Parallel fan-out: 2-3 agents simultaneously for different aspects
- Results from one phase feed the next phase
- Tools restricted by principle of least privilege

### 5.8 Implications for AceClaw

**Implementation priority for sub-agents:**
1. **P0**: TaskTool + SubAgentRunner (foreground only)
2. **P0**: Built-in Explore agent (Haiku, read-only)
3. **P0**: No-nesting enforcement (Task excluded from sub-agent tools)
4. **P1**: General-purpose agent + custom agent configs from `.aceclaw/agents/`
5. **P1**: Transcript persistence + resumption
6. **P2**: Background execution with TaskOutput
7. **P2**: Agent persistent memory (user/project/local scopes)

**AceClaw advantages over Claude Code:**
- Daemon can keep sub-agent transcripts in memory (faster resumption)
- Virtual threads enable true parallel sub-agent execution without thread pool limits
- StructuredTaskScope provides clean cancellation semantics
- In-process sub-agents (when running in daemon) avoid network overhead

---

## 6. Agent Teams Architecture

### 6.1 Architecture Overview

Agent Teams are a higher-level orchestration mechanism (experimental, behind `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`):

| Component | Role |
|-----------|------|
| **Team lead** | Main session; creates team, spawns teammates, coordinates |
| **Teammates** | Separate Claude Code instances working independently |
| **Task list** | Shared work items that teammates claim and complete |
| **Mailbox** | Messaging system for inter-agent communication |

### 6.2 Key Differences from Sub-Agents

| Aspect | Sub-Agents | Agent Teams |
|--------|-----------|-------------|
| Context | Own window; results return to parent | Own window; fully independent |
| Communication | Report back to parent only | Teammates message each other directly |
| Coordination | Parent manages all work | Shared task list with self-coordination |
| Token cost | Lower (results summarized) | Higher (each is full Claude instance) |
| Nesting | Cannot spawn sub-agents | Cannot spawn their own teams |
| Persistence | Transcripts per agent | Teams persist beyond client sessions |

### 6.3 Communication Model

**Message types:**
- `message` — Direct message to one specific teammate
- `broadcast` — Send to ALL teammates (expensive, use sparingly)
- `shutdown_request` / `shutdown_response` — Graceful shutdown protocol
- `plan_approval_request` / `plan_approval_response` — Plan mode for teammates

**Automatic delivery:** Messages arrive automatically as new conversation turns. No polling needed. Idle teammates can receive messages (sending wakes them up).

**Idle notifications:** Teammates go idle after every turn — this is normal. The system sends idle notifications to the team lead automatically. Peer DM summaries are included in idle notifications.

### 6.4 Task Coordination

Task management tools available to all team members:
- `TaskCreate` — Create new tasks with subject, description, activeForm
- `TaskUpdate` — Update status, owner, dependencies (addBlocks/addBlockedBy)
- `TaskList` — List all tasks with status, owner, blocked state
- `TaskGet` — Get full task details

Task lifecycle: `pending` -> `in_progress` -> `completed` (or `deleted`)

**Self-coordination pattern:** Team lead creates tasks; teammates self-claim unblocked tasks. Tasks have dependency chains (blockedBy) that prevent premature claiming.

### 6.5 Team File Structure

```
~/.claude/teams/{team-name}/
  config.json       # Team config + members array
                    # members: [{name, agentId, agentType}]

~/.claude/tasks/{team-name}/
  {task-id}.json    # Shared task entries
```

### 6.6 Team Workflow

1. Team lead creates team with `TeamCreate`
2. Lead creates tasks with `TaskCreate`
3. Lead spawns teammates with `Task` tool (team_name + name parameters)
4. Lead assigns tasks with `TaskUpdate` (owner field)
5. Teammates work on assigned tasks, mark completed
6. Teammates check `TaskList` for next available work
7. Lead sends `shutdown_request` when all work is done
8. `TeamDelete` cleans up team and task directories

### 6.7 Hooks Integration

| Hook Event | Purpose |
|-----------|---------|
| `TeammateIdle` | Enforce quality gates before teammate goes idle |
| `TaskCompleted` | Validate task completion criteria |
| `SubagentStart/Stop` | Lifecycle hooks for sub-agents within teams |

### 6.8 Implications for AceClaw

**AceClaw's daemon architecture gives it major advantages for teams:**

1. **In-process teams** — Claude Code spawns separate OS processes for teammates. AceClaw can run teammates as virtual threads within the daemon, sharing memory and resources efficiently.

2. **Persistent teams** — Claude Code teams die when the CLI exits (unless experimentally persisted). AceClaw's daemon naturally supports teams that outlive client connections.

3. **Background team execution** — AceClaw's daemon can run agent teams overnight without any client connection. Results available when user reconnects.

4. **Dual transport** — File-based messaging (for cross-process) + in-memory messaging (for in-daemon teammates). Claude Code only has file-based.

5. **Shared context optimization** — In-daemon teammates can share read-only resources (codebase index, memory) without duplication.

**Implementation approach:**

```java
// Sealed hierarchy for team messages
public sealed interface TeamMessage permits
    TeamMessage.DirectMessage,
    TeamMessage.Broadcast,
    TeamMessage.ShutdownRequest,
    TeamMessage.ShutdownResponse,
    TeamMessage.PlanApprovalRequest,
    TeamMessage.PlanApprovalResponse,
    TeamMessage.IdleNotification {

    record DirectMessage(String from, String to, String content, String summary)
        implements TeamMessage {}
    record Broadcast(String from, String content, String summary)
        implements TeamMessage {}
    record ShutdownRequest(String requestId, String from, String to, String reason)
        implements TeamMessage {}
    record ShutdownResponse(String requestId, String from, boolean approved, String reason)
        implements TeamMessage {}
    record PlanApprovalRequest(String requestId, String from, String planContent)
        implements TeamMessage {}
    record PlanApprovalResponse(String requestId, String from, boolean approved, String feedback)
        implements TeamMessage {}
    record IdleNotification(String from, String lastTaskId, String peerDmSummary)
        implements TeamMessage {}
}
```

---

## 7. Hooks System Architecture

### 7.1 Overview

Claude Code has 14 hook event types and 3 handler types. Hooks are user-defined commands, LLM prompts, or agents that execute at specific lifecycle points.

### 7.2 Event Types (14)

| Event | Can Block? | Key Use Case |
|-------|-----------|-------------|
| `SessionStart` | No | Environment setup, DB connections |
| `UserPromptSubmit` | Yes | Input validation, prompt transformation |
| `PreToolUse` | Yes | Tool input modification, safety gates |
| `PermissionRequest` | Yes | Auto-approve/deny based on policy |
| `PostToolUse` | No | Linting after file changes |
| `PostToolUseFailure` | No | Error reporting |
| `Notification` | No | UI notifications |
| `SubagentStart` | No | Sub-agent setup |
| `SubagentStop` | Yes | Sub-agent cleanup/quality gates |
| `Stop` | Yes | Prevent premature stopping |
| `TeammateIdle` | Yes | Force teammates to continue |
| `TaskCompleted` | Yes | Validate completion criteria |
| `PreCompact` | No | Pre-compaction actions |
| `SessionEnd` | No | Cleanup, state persistence |

### 7.3 Handler Types (3)

1. **Command hooks** (`type: "command"`) — Shell commands with JSON stdin/stdout, exit code semantics (0=proceed, 2=block)
2. **Prompt hooks** (`type: "prompt"`) — Single-turn LLM evaluation returning `{ok: true/false, reason: "..."}`
3. **Agent hooks** (`type: "agent"`) — Multi-turn agent with tools (Read, Grep, Glob) for investigation

### 7.4 PreToolUse Decision Control

PreToolUse is the most powerful hook — it can modify tool inputs before execution:

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "allow",     // "allow", "deny", "ask"
    "permissionDecisionReason": "...",
    "updatedInput": { "command": "npm run lint --fix" },
    "additionalContext": "Running in production environment."
  }
}
```

### 7.5 Implications for AceClaw

Priority implementation:
1. **P0**: PreToolUse + PostToolUse with command hooks (core safety gating)
2. **P0**: Exit code semantics (0=proceed, 2=block)
3. **P1**: JSON output with decision control and input modification
4. **P1**: SessionStart, SessionEnd, Stop events
5. **P2**: Prompt and agent hook types
6. **P2**: SubagentStart/Stop, TeammateIdle, TaskCompleted
7. **P3**: PreCompact, Notification, PermissionRequest

---

## 8. Configuration System Architecture

### 8.1 5-Scope Hierarchy

| Priority | Scope | Location | Shareable |
|----------|-------|----------|-----------|
| 1 (highest) | Managed | System-level managed-settings.json | IT-deployed |
| 2 | CLI args | Command-line arguments | Session-only |
| 3 | Local | `.claude/settings.local.json` | Gitignored |
| 4 | Project | `.claude/settings.json` | Committed |
| 5 (lowest) | User | `~/.claude/settings.json` | Local machine |

### 8.2 Merge Strategy

- **Denial takes precedence**: Any scope denying a permission overrides lower-scope allows
- **Array concatenation**: Permission lists are concatenated across scopes
- **Object deep merge**: Nested objects deep-merged
- **Scalar last-value-wins**: Simple values use highest-priority scope

### 8.3 Permission Rule Syntax

```
Tool                    # All invocations of Tool
Tool(specifier)         # Specific invocation pattern
Bash(npm run *)         # Bash commands starting with "npm run"
Edit(./src/**)          # Edit files under src/
Task(agent-name)        # Spawn specific sub-agent
WebFetch(domain:x.com)  # Fetch from specific domain
```

Evaluation order: Deny (first match wins) -> Ask -> Allow

### 8.4 Environment Variables

Key env vars for feature control:
- `CLAUDE_CODE_DISABLE_AUTO_MEMORY` — Disable auto memory
- `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS` — Enable agent teams
- `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` — Auto-compact threshold
- `CLAUDE_CODE_EFFORT_LEVEL` — low/medium/high
- `MAX_THINKING_TOKENS` — Extended thinking budget (0=disable)
- `CLAUDE_CODE_MAX_OUTPUT_TOKENS` — Max output tokens
- `CLAUDE_CODE_SUBAGENT_MODEL` — Override sub-agent model

---

## 9. Plugin Architecture

### 9.1 Structure

```
plugin-name/
  .claude-plugin/plugin.json   # Manifest (optional)
  agents/                      # Sub-agent definitions
  skills/                      # Skills (SKILL.md)
  hooks/hooks.json             # Hook configurations
  .mcp.json                    # MCP server definitions
  .lsp.json                    # LSP server configurations
```

### 9.2 Key Features

- **Namespace isolation**: Plugin name prefixes all components (`plugin-name:agent-name`)
- **Security**: Plugins cached to `~/.claude/plugins/cache/`, path traversal blocked
- **Portable paths**: `${CLAUDE_PLUGIN_ROOT}` variable resolves to cached location
- **Multi-source marketplace**: GitHub repos, npm packages, URLs, directories

### 9.3 Implications for AceClaw

The plugin system is a P3 priority but important for enterprise adoption:
- Allows organizations to distribute approved tools, agents, and policies
- Enables ecosystem of community-contributed capabilities
- Supports managed deployment through marketplace configuration

---

## 10. Key Architectural Patterns for AceClaw

### 10.1 Pattern: Curated Memory with Auto-Index

The agent maintains its own memory by writing to a structured directory. `MEMORY.md` serves as a 200-line curated index linking to topic files. The agent decides what to remember, what to forget, and how to organize.

**Why this works:** The LLM is good at deciding what's important. Manual memory curation (by users) doesn't scale. Letting the agent manage its own memory creates a virtuous cycle — the agent remembers what helped it succeed and forgets what didn't.

### 10.2 Pattern: Tool Description as Instruction Manual

Tool descriptions are not just API docs — they encode behavioral guidance:
- "NEVER use Bash for file operations"
- "ALWAYS prefer Edit over Write for existing files"
- "Use Task for open-ended searches requiring multiple rounds"

**Why this works:** LLMs follow instructions in tool descriptions more reliably than in system prompts. Tool descriptions are injected alongside the tool schema at every turn, so they're always "fresh" in context.

### 10.3 Pattern: Depth-1 Sub-Agents with Model/Tool Scoping

Sub-agents are isolated execution contexts with:
- Fresh conversation history (no parent context leakage)
- Scoped tools (principle of least privilege)
- Model selection (Haiku for exploration, inherit for modification)
- No nesting (prevents runaway recursion)

**Why this works:** Keeps the parent context window clean, enables parallel exploration, and prevents the "context pollution" problem where exploration results overwhelm the main conversation.

### 10.4 Pattern: Shared Task List with Self-Coordination

Agent teams use a shared task list where:
- Leader creates and assigns tasks
- Teammates self-claim unblocked tasks
- Dependencies prevent premature execution
- Completion triggers downstream task availability

**Why this works:** Decentralized coordination scales better than centralized orchestration. The leader doesn't need to micromanage — teammates autonomously pick up work.

### 10.5 Pattern: Hooks as Extension Points

Hooks enable customization without modifying core code:
- PreToolUse for safety gates and input transformation
- PostToolUse for automatic linting/formatting
- Stop for quality gates before conversation ends
- TaskCompleted for validation criteria

**Why this works:** Separates policy from mechanism. Enterprise admins can enforce rules (managed hooks) without changing the agent's code.

---

## 11. Summary: Priority Recommendations for AceClaw

### Must-Have (P0) for Self-Learning Agent

| Feature | Source | Notes |
|---------|--------|-------|
| 8-scope memory hierarchy | Claude Code | Already partially implemented (3 scopes) |
| Auto-memory with curated index | Claude Code | MEMORY.md + topic files, 200-line cap |
| Enriched tool descriptions (20-40 lines) | Claude Code | Current descriptions are ~5 lines |
| Tool selection substitution rules | Claude Code | "Use X instead of Y" in system prompt |
| Sub-agent support (TaskTool) | Claude Code | Depth-1, no nesting, model/tool scoping |
| Built-in Explore agent | Claude Code | Haiku, read-only, fast search |
| PreToolUse/PostToolUse hooks | Claude Code | Command type, exit code semantics |

### Should-Have (P1) for Self-Improving Agent

| Feature | Source | Notes |
|---------|--------|-------|
| Per-agent persistent memory | Claude Code | user/project/local scopes |
| Custom agent definitions | Claude Code | `.aceclaw/agents/` with YAML frontmatter |
| Conditional rules (path-based) | Claude Code | `paths` frontmatter in rules files |
| Background memory consolidation | AceClaw daemon | Merge, prune, refine during idle time |
| Transcript persistence + resumption | Claude Code | JSONL storage, agent ID-based resumption |
| Task management tools | Claude Code | TaskCreate/Update/List/Get |

### Nice-to-Have (P2) for Agent Teams

| Feature | Source | Notes |
|---------|--------|-------|
| Agent Teams with shared task list | Claude Code | TeamCreate, SendMessage, task coordination |
| In-process team messaging | AceClaw daemon | Virtual thread teammates in same JVM |
| Background team execution | AceClaw daemon | Teams survive client disconnects |
| Plugin system | Claude Code | Enterprise distribution of agents/tools/hooks |
| Full hooks system (14 events) | Claude Code | Including prompt and agent hook types |
