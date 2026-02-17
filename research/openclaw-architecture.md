# OpenClaw / Claude Code Architecture Research

> Research document for the Chelava PRD - Java-based AI Coding Agent
> Compiled: 2026-02-16

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Core Agent Loop](#2-core-agent-loop)
3. [Tool System](#3-tool-system)
4. [Memory and Context Management](#4-memory-and-context-management)
5. [Permission Model and Sandboxing](#5-permission-model-and-sandboxing)
6. [Hook System](#6-hook-system)
7. [Conversation Management](#7-conversation-management)
8. [Agent / Subagent Architecture](#8-agent--subagent-architecture)
9. [Agent Teams (Multi-Agent Orchestration)](#9-agent-teams-multi-agent-orchestration)
10. [MCP (Model Context Protocol)](#10-mcp-model-context-protocol)
11. [Extension Points (Skills, Plugins, Custom Agents)](#11-extension-points-skills-plugins-custom-agents)
12. [OpenClaw Specifics](#12-openclaw-specifics)
13. [Key Takeaways for Chelava](#13-key-takeaways-for-chelava)

---

## 1. Executive Summary

This document surveys the architecture of **Claude Code** (Anthropic's CLI-based AI coding agent) and **OpenClaw** (an open-source personal AI assistant), both representative of the "agentic AI" paradigm emerging in 2025-2026. The goal is to inform the design of **Chelava**, a Java-based AI coding agent.

**Claude Code** is a terminal-native AI coding assistant that follows a ReAct (Reason + Act) loop, executing tools iteratively to accomplish software engineering tasks. It features a rich tool system, sophisticated context management, a layered permission model, hook-based extensibility, subagent delegation, multi-agent team orchestration, and MCP integration.

**OpenClaw** is a self-hosted, open-source personal AI agent (TypeScript/MIT license, 157K+ GitHub stars) that connects to 15+ messaging platforms and focuses on personal automation. While architecturally distinct from Claude Code, it demonstrates the broader agent paradigm: gateway-based control planes, skill registries, and multi-platform integration.

---

## 2. Core Agent Loop

### 2.1 ReAct Framework

Claude Code operates on an iterative **ReAct (Reason + Act)** framework:

```
User Query --> Plan --> Reason --> Act (Tool Execution) --> Observe --> Repeat
```

The cycle is:
1. **Query**: User provides a task or question
2. **Plan**: Agent creates a step-by-step plan
3. **Reason**: Agent thinks about the next action needed
4. **Act**: Agent executes a tool (file read, bash command, search, etc.)
5. **Observe**: Agent analyzes the tool result
6. **Repeat**: Loop continues until the task is complete or the agent determines it needs user input

### 2.2 Gather-Act-Verify Pattern

The fundamental agentic pattern is: **gather context -> take action -> verify work -> repeat**. This is the hallmark of genuine autonomy in coding agents.

### 2.3 Architecture Layers

Claude Code has three architectural layers:

| Layer | Components | Purpose |
|-------|-----------|---------|
| **Core Layer** | Main Conversation Context, Tools (Read, Edit, Bash, Glob, Grep) | Primary agent loop execution |
| **Delegation Layer** | Subagents (Explore, Plan, General-purpose) | Task delegation and parallel work |
| **Extension Layer** | MCP Servers, Hooks, Skills, Plugins | Extensibility and customization |

### 2.4 Token Budget

The agent operates within a **200K token context window**, with approximately:
- System prompt: ~5-15K tokens
- CLAUDE.md files: ~1-10K tokens
- Tool schemas: ~500-2000 per MCP server
- Available for conversation: ~140-150K tokens

---

## 3. Tool System

### 3.1 Core Tool Categories

Claude Code provides **14+ core tools** organized into categories:

#### File Operations
| Tool | Description | Key Constraints |
|------|-------------|-----------------|
| **Read** | Read file contents with multimodal support (images, PDFs, notebooks) | Default 2000-line limit, returns `cat -n` format with line numbers |
| **Write** | Create new files or overwrite existing ones | Enforces read-before-write validation for existing files |
| **Edit** | Exact string matching replacements | Requires prior file read; fails if `old_string` appears multiple times without `replace_all` |
| **MultiEdit** | Multiple sequential find-and-replace operations | Batch editing in a single call |
| **Glob** | Fast file pattern matching (`**/*.js`) | Results sorted by modification time |
| **Grep** | Content search built on ripgrep | Full regex, multiline matching, three output modes |
| **LS** | Directory listing with pattern filtering | Basic directory exploration |

#### Execution Tools
| Tool | Description | Key Constraints |
|------|-------------|-----------------|
| **Bash** | Execute shell commands in persistent session | Max timeout 600s, output truncated at 30K chars, default timeout 120s |
| **BashOutput** | Retrieve incremental output from background shells | Returns only new output since last check |
| **KillShell** | Terminate background bash shells | By shell ID |

#### Web Operations
| Tool | Description | Key Constraints |
|------|-------------|-----------------|
| **WebFetch** | Fetch URL content with AI processing | 15-minute cache, HTML-to-markdown conversion |
| **WebSearch** | Web queries with domain filtering | US-only availability |

#### Notebook Operations
| Tool | Description |
|------|-------------|
| **NotebookRead** | Read Jupyter notebook cells |
| **NotebookEdit** | Modify Jupyter notebook cells (replace, insert, delete) |

#### Planning and Task Management
| Tool | Description |
|------|-------------|
| **TaskCreate** | Create structured task items with dependencies |
| **TaskUpdate** | Update task status (pending -> in_progress -> completed) |
| **TaskList** | List all tasks and their status |
| **TaskGet** | Retrieve full task details by ID |
| **ExitPlanMode** | Transition from planning to implementation |

#### User Interaction
| Tool | Description |
|------|-------------|
| **AskUserQuestion** | Structured Q&A with 1-4 questions, 2-4 options each |
| **SendMessage** | Inter-agent messaging (for agent teams) |

#### IDE Integration
| Tool | Description |
|------|-------------|
| **getDiagnostics** | Retrieve VS Code language server diagnostics |
| **executeCode** | Execute Python in Jupyter kernel |

#### MCP Resource Tools
| Tool | Description |
|------|-------------|
| **ListMcpResources** | List MCP server resources |
| **ReadMcpResource** | Access MCP server resources |

### 3.2 Tool Execution Model

- **Concurrent execution**: Multiple independent tool calls can be batched together in a single response
- **Stateless tool calls**: Each tool invocation is independent (except Bash which maintains session state)
- **Absolute paths**: All file paths must be absolute
- **Read-before-write enforcement**: The system validates that files are read before being written
- **Edit uniqueness**: Edit operations require exact string uniqueness or explicit `replace_all`

### 3.3 Key Design Principles

1. **Dedicated tools over shell commands**: Read/Write/Edit/Glob/Grep are preferred over cat/sed/awk/find
2. **Parallel execution**: Independent operations are executed concurrently
3. **Incremental output**: Background operations return only new output
4. **Safety constraints**: Dangerous operations require confirmation

---

## 4. Memory and Context Management

### 4.1 CLAUDE.md File Hierarchy

CLAUDE.md files provide persistent, project-specific instructions loaded into the system prompt:

| Location | Scope | Loading Behavior |
|----------|-------|-----------------|
| `~/.claude/CLAUDE.md` | User-global | Always loaded at launch |
| `<project>/.claude/CLAUDE.md` or `<project>/CLAUDE.md` | Project root | Always loaded at launch |
| `<project>/src/CLAUDE.md` | Subdirectory | Loaded on-demand when files in that directory are accessed |

### 4.2 Auto Memory System

Claude Code automatically saves useful context to a persistent memory directory:

- **Location**: `~/.claude/projects/<project-hash>/memory/MEMORY.md`
- **Loading**: First 200 lines of `MEMORY.md` are loaded into the system prompt at every session start
- **Content**: Project patterns, key commands, user preferences, debugging insights
- **Organization**: Detailed notes go in separate topic files (e.g., `debugging.md`, `patterns.md`) linked from MEMORY.md

### 4.3 Context Compression (Compaction)

When conversations approach context limits:

1. **Auto-compact trigger**: Fires at 75-92% context usage (configurable via `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`)
2. **Manual compact**: User can run `/compact` proactively at 70%+
3. **Process**: System generates a summary of the current conversation, creates a compaction block, and continues with condensed context
4. **PreCompact hook**: Fires before compression, allowing custom actions (e.g., re-injecting critical context)
5. **Server-side compaction**: Available in beta for Opus 4.6, performs summarization server-side

### 4.4 Best Practices for Context Management

- Move stable information (tech stacks, conventions, architectural decisions) into CLAUDE.md
- Use auto-memory for per-session learnings
- Compact strategically at natural breakpoints rather than letting auto-compact happen randomly
- Use hooks to re-inject critical context after compaction

---

## 5. Permission Model and Sandboxing

### 5.1 Permission Modes

| Mode | Behavior |
|------|----------|
| **Normal (default)** | Prompts for every potentially dangerous operation |
| **Accept Edits** | Auto-accepts file edits, prompts for other operations |
| **Plan Mode** | Read-only operations only, no modifications |
| **Auto-accept / YOLO** | Eliminates permission prompts for the session |
| **Bypass Permissions** | Skips all permission checks (use with caution) |
| **Delegate** | Coordination-only mode for agent team leads |
| **Don't Ask** | Auto-denies permission prompts (explicitly allowed tools still work) |

### 5.2 Sandbox Architecture

The sandbox provides OS-level isolation using two primary mechanisms:

#### Filesystem Isolation
- Agent can only access or modify specific, approved directories
- Prevents prompt-injected agents from modifying sensitive system files
- Uses OS-level primitives (macOS sandbox profiles, Linux namespaces)

#### Network Isolation
- Agent can only connect to approved servers/domains
- Prevents data exfiltration or malware download
- Proxy server enforces domain restrictions
- New domain requests trigger user permission prompts

### 5.3 Permission Configuration

```json
{
  "permissions": {
    "allow": ["Read", "Glob", "Grep"],
    "deny": ["Task(Explore)", "Bash(rm *)"],
    "additionalDirectories": ["/path/to/allowed"]
  }
}
```

### 5.4 Impact

Internal usage data shows sandboxing **reduces permission prompts by 84%** while maintaining security.

---

## 6. Hook System

### 6.1 Overview

Hooks are user-defined shell commands that execute at specific lifecycle points. They provide **deterministic control** over agent behavior, ensuring actions always happen rather than relying on the LLM.

### 6.2 Hook Events

| Event | When It Fires | Matcher Input |
|-------|--------------|---------------|
| `SessionStart` | Session begins or resumes | How session started (startup, resume, compact, clear) |
| `UserPromptSubmit` | User submits prompt, before processing | N/A |
| `PreToolUse` | Before a tool call executes (can block) | Tool name |
| `PermissionRequest` | Permission dialog appears | Tool name |
| `PostToolUse` | After a tool call succeeds | Tool name |
| `PostToolUseFailure` | After a tool call fails | Tool name |
| `Notification` | Claude sends a notification | Notification type |
| `SubagentStart` | Subagent is spawned | Agent type name |
| `SubagentStop` | Subagent finishes | Agent type name |
| `Stop` | Claude finishes responding | N/A |
| `TeammateIdle` | Agent team teammate about to go idle | N/A |
| `TaskCompleted` | Task being marked as completed | N/A |
| `PreCompact` | Before context compaction | Trigger type (manual, auto) |
| `SessionEnd` | Session terminates | Why session ended |

### 6.3 Hook Types

| Type | Description | Use Case |
|------|-------------|----------|
| `command` | Runs a shell command | Formatting, validation, logging, notifications |
| `prompt` | Single-turn LLM evaluation | Judgment-based decisions (Haiku by default) |
| `agent` | Multi-turn verification with tool access | Complex verification (e.g., running tests before stop) |

### 6.4 Hook I/O Model

- **Input**: JSON on stdin with event-specific fields (session_id, cwd, tool_name, tool_input, etc.)
- **Output**: Exit code determines behavior:
  - `exit 0`: Action proceeds; stdout added to context for some events
  - `exit 2`: Action blocked; stderr becomes Claude's feedback
  - Other codes: Action proceeds; stderr logged but not shown to Claude
- **Structured JSON output**: For fine-grained control (allow/deny/ask decisions)

### 6.5 Hook Configuration Locations

| Location | Scope |
|----------|-------|
| `~/.claude/settings.json` | All projects (user-wide) |
| `.claude/settings.json` | Single project (shareable) |
| `.claude/settings.local.json` | Single project (local only) |
| Plugin `hooks/hooks.json` | When plugin is enabled |
| Skill/agent frontmatter | While skill/agent is active |

### 6.6 Common Use Cases

1. **Auto-format code after edits** (PostToolUse + Edit|Write matcher -> prettier)
2. **Block edits to protected files** (PreToolUse + Edit|Write -> check against patterns)
3. **Desktop notifications** (Notification event -> osascript/notify-send)
4. **Re-inject context after compaction** (SessionStart + compact matcher -> echo reminders)
5. **Log all bash commands** (PostToolUse + Bash matcher -> append to log)
6. **Quality gates** (Stop hook with agent type -> run test suite before completion)

### 6.7 Advanced Features

- **PreToolUse input modification** (v2.0.10+): Hooks can modify tool inputs before execution
- **Parallel execution**: All matching hooks run in parallel
- **Deduplication**: Identical hook commands are automatically deduplicated
- **Regex matchers**: Full regex support for filtering (e.g., `mcp__github__.*`)

---

## 7. Conversation Management

### 7.1 Session Persistence

- Conversations are stored as JSONL transcript files at `~/.claude/projects/{project}/{sessionId}/`
- Sessions can be resumed with `/resume` command
- Subagent transcripts stored separately at `subagents/agent-{agentId}.jsonl`
- Automatic cleanup based on `cleanupPeriodDays` setting (default: 30 days)

### 7.2 Context Window Strategy

The 200K token context window is managed through:

1. **Fixed overhead**: System prompt (~5-15K), CLAUDE.md (~1-10K), tool schemas
2. **Conversation history**: User messages, assistant responses, tool results
3. **Auto-compaction**: Triggers at configurable threshold (default ~95% for subagents)
4. **Manual compaction**: `/compact` command for strategic compaction
5. **Server-side compaction**: Beta feature for Opus 4.6

### 7.3 Conversation Flow

```
Session Start
  -> Load CLAUDE.md files
  -> Load auto-memory (MEMORY.md first 200 lines)
  -> Load MCP server connections
  -> SessionStart hooks fire
  -> User prompt loop:
       -> UserPromptSubmit hooks fire
       -> Agent processes prompt (ReAct loop)
       -> Tool calls with Pre/Post hooks
       -> Auto-compact if threshold exceeded
       -> Stop hooks fire on completion
  -> SessionEnd hooks fire on termination
```

---

## 8. Agent / Subagent Architecture

### 8.1 Overview

Subagents are separate agent instances spawned by the main agent to handle focused subtasks. Each runs in its own context window with custom configuration.

### 8.2 Built-in Subagent Types

| Type | Model | Tools | Purpose |
|------|-------|-------|---------|
| **Explore** | Haiku (fast) | Read-only (Glob, Grep, Read, safe Bash) | Codebase search and analysis |
| **Plan** | Inherits from main | Read-only | Research for plan mode |
| **General-purpose** | Inherits from main | All tools | Complex multi-step tasks |
| **Bash** | Inherits | Shell execution | Terminal commands in separate context |
| **Claude Code Guide** | Haiku | Read-only | Feature questions about Claude Code |

### 8.3 Custom Subagent Configuration

Subagents are defined as Markdown files with YAML frontmatter:

```yaml
---
name: code-reviewer
description: Reviews code for quality and best practices
tools: Read, Grep, Glob, Bash
model: sonnet
permissionMode: default
maxTurns: 50
memory: user
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/validate-command.sh"
---

System prompt content goes here in the markdown body.
```

### 8.4 Subagent Configuration Fields

| Field | Description |
|-------|-------------|
| `name` | Unique identifier (lowercase, hyphens) |
| `description` | When Claude should delegate to this subagent |
| `tools` | Allowed tools (inherits all if omitted) |
| `disallowedTools` | Tools to deny |
| `model` | sonnet, opus, haiku, or inherit |
| `permissionMode` | default, acceptEdits, delegate, dontAsk, bypassPermissions, plan |
| `maxTurns` | Maximum agentic turns |
| `skills` | Skills to preload into context |
| `mcpServers` | MCP servers available to this subagent |
| `hooks` | Lifecycle hooks scoped to this subagent |
| `memory` | Persistent memory scope: user, project, or local |

### 8.5 Subagent Scope and Storage

| Location | Scope | Priority |
|----------|-------|----------|
| `--agents` CLI flag | Current session | 1 (highest) |
| `.claude/agents/` | Current project | 2 |
| `~/.claude/agents/` | All projects | 3 |
| Plugin `agents/` directory | Where plugin is enabled | 4 (lowest) |

### 8.6 Execution Model

- **Foreground**: Blocks main conversation; permission prompts pass through to user
- **Background**: Runs concurrently (Ctrl+B to background a running task); pre-approves permissions at launch; MCP tools unavailable
- **Stateless invocations**: Each invocation is fresh context unless explicitly resumed
- **No nesting**: Subagents cannot spawn other subagents
- **Auto-compaction**: Subagents support independent auto-compaction at ~95% capacity

### 8.7 Persistent Memory for Subagents

Subagents can have persistent memory that survives across conversations:

| Scope | Location | Use When |
|-------|----------|----------|
| `user` | `~/.claude/agent-memory/<name>/` | Cross-project learnings |
| `project` | `.claude/agent-memory/<name>/` | Project-specific, shareable via VCS |
| `local` | `.claude/agent-memory-local/<name>/` | Project-specific, not committed |

When enabled, the first 200 lines of the agent's `MEMORY.md` are injected into its system prompt.

### 8.8 Key Design Patterns

1. **Isolate high-volume operations**: Tests, log processing, documentation fetch
2. **Parallel research**: Multiple subagents exploring different aspects simultaneously
3. **Chain subagents**: Sequential workflow where each agent's output feeds the next
4. **Cost control**: Route simple tasks to Haiku, complex tasks to Opus

---

## 9. Agent Teams (Multi-Agent Orchestration)

### 9.1 Overview

Agent Teams coordinate multiple Claude Code instances working as a team. Unlike subagents (which report back to the main agent), teammates can communicate directly with each other.

**Status**: Experimental, disabled by default. Enabled via `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS` setting.

### 9.2 Architecture Components

| Component | Role |
|-----------|------|
| **Team Lead** | Main session that creates team, spawns teammates, coordinates work |
| **Teammates** | Separate Claude Code instances working on assigned tasks |
| **Task List** | Shared list with dependencies; tasks can be pending, in_progress, or completed |
| **Mailbox** | Messaging system for inter-agent communication |

### 9.3 Storage

- **Team config**: `~/.claude/teams/{team-name}/config.json`
- **Task list**: `~/.claude/tasks/{team-name}/`
- Task claiming uses **file locking** to prevent race conditions

### 9.4 Communication Model

| Mechanism | Description |
|-----------|-------------|
| **message** | Send to one specific teammate |
| **broadcast** | Send to all teammates (costly, use sparingly) |
| **Automatic message delivery** | Messages delivered automatically to recipients |
| **Idle notifications** | Teammates auto-notify lead when stopping |
| **Shared task list** | All agents see task status and claim available work |

### 9.5 Display Modes

| Mode | Description |
|------|-------------|
| **In-process** | All teammates in main terminal; Shift+Up/Down to navigate |
| **Split panes** | Each teammate in own tmux/iTerm2 pane |

### 9.6 Coordination Features

- **Delegate mode**: Restricts lead to coordination-only tools (Shift+Tab to enable)
- **Plan approval**: Teammates plan in read-only mode until lead approves
- **Quality gates via hooks**: `TeammateIdle` and `TaskCompleted` hooks enforce standards
- **Self-claiming**: Teammates auto-claim next unblocked task after completion
- **Task dependencies**: Automatic unblocking when prerequisite tasks complete

### 9.7 Subagents vs Agent Teams

| Aspect | Subagents | Agent Teams |
|--------|-----------|-------------|
| Context | Own window; results return to caller | Own window; fully independent |
| Communication | Report back to main agent only | Teammates message each other directly |
| Coordination | Main agent manages all work | Shared task list with self-coordination |
| Best for | Focused tasks where only result matters | Complex work requiring discussion |
| Token cost | Lower (summarized back) | Higher (each teammate is separate instance) |

### 9.8 Best Use Cases

1. **Research and review**: Multiple teammates investigate different aspects in parallel
2. **New modules/features**: Each teammate owns a separate piece
3. **Debugging with competing hypotheses**: Parallel theory testing
4. **Cross-layer coordination**: Frontend, backend, and tests each owned by different teammate

---

## 10. MCP (Model Context Protocol)

### 10.1 Overview

MCP is an open standard (introduced by Anthropic, November 2024) that enables seamless integration between LLM applications and external data sources/tools. It uses a **client-server architecture** inspired by LSP (Language Server Protocol) with **JSON-RPC 2.0** as the message format.

### 10.2 Architecture

```
AI Agent (MCP Client) <-- JSON-RPC 2.0 --> MCP Server <--> External Tools/Data
```

- **MCP Host**: The application (Claude Code) that wants to access tools
- **MCP Client**: Protocol client that maintains connection to MCP servers
- **MCP Server**: Lightweight program exposing tools, resources, and prompts

### 10.3 Protocol Capabilities

| Capability | Description |
|-----------|-------------|
| **Tools** | Executable functions the agent can call |
| **Resources** | Data sources the agent can read |
| **Prompts** | Reusable prompt templates |
| **Sampling** | Server-initiated LLM requests |

### 10.4 Key Specifications

- **Transport**: stdio (local) or HTTP with SSE (remote)
- **Authentication**: OAuth 2.0 based auth (June 2025 update)
- **Async execution**: Long-running tasks (November 2025 update)
- **Governance**: Donated to Agentic AI Foundation (AAIF) in December 2025

### 10.5 MCP Tool Naming in Claude Code

MCP tools follow the naming convention: `mcp__<server>__<tool>`
- Example: `mcp__github__search_repositories`
- Example: `mcp__filesystem__read_file`

### 10.6 Configuration

MCP servers are configured in `.mcp.json`:

```json
{
  "mcpServers": {
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_TOKEN": "${GITHUB_TOKEN}"
      }
    }
  }
}
```

---

## 11. Extension Points (Skills, Plugins, Custom Agents)

### 11.1 Skills System

Skills are **model-invoked** capabilities that Claude automatically uses based on task context.

#### Skill Structure
```
.claude/skills/
  code-review/
    SKILL.md
  api-conventions/
    SKILL.md
```

#### SKILL.md Format
```yaml
---
name: code-review
description: Reviews code for best practices. Use when reviewing code or checking PRs.
disable-model-invocation: false
---

Skill instructions in markdown...
```

#### Skill Behavior
- Claude compares user requests against registered skill descriptions
- Matching skills are invoked automatically without explicit user action
- Skills can be preloaded into subagent contexts via the `skills` field
- User-invocable skills use slash commands (e.g., `/commit`, `/review-pr`)

### 11.2 Plugin Architecture

Plugins bundle multiple extension types into distributable packages.

#### Plugin Structure
```
my-plugin/
  .claude-plugin/
    plugin.json          # Manifest (name, description, version, author)
  commands/              # Slash commands (user-invocable skills)
  agents/                # Custom subagent definitions
  skills/                # Agent skills (model-invocable)
  hooks/
    hooks.json           # Event handlers
  .mcp.json              # MCP server configurations
  .lsp.json              # LSP server configurations
```

#### Plugin Distribution
- Installed via marketplaces or `--plugin-dir` flag for development
- Namespaced skills: `/plugin-name:skill-name` prevents conflicts
- Version controlled with semantic versioning

#### Plugin Scopes
| Approach | Skill Names | Best For |
|----------|------------|----------|
| Standalone (`.claude/`) | `/hello` | Personal, project-specific |
| Plugins | `/plugin-name:hello` | Sharing, distribution, reuse |

### 11.3 Custom Slash Commands

Commands are user-invocable skills defined as Markdown files:
```
.claude/commands/
  review.md
  deploy.md
```

Invoked with `/command-name`. Support `$ARGUMENTS` placeholder for dynamic input.

### 11.4 LSP Integration

Plugins can provide LSP (Language Server Protocol) servers for code intelligence:
```json
{
  "go": {
    "command": "gopls",
    "args": ["serve"],
    "extensionToLanguage": { ".go": "go" }
  }
}
```

### 11.5 Extension Hierarchy

```
Plugins (distributable packages)
  |-- Commands (user-invocable slash commands)
  |-- Skills (model-invocable capabilities)
  |-- Agents (custom subagent definitions)
  |-- Hooks (lifecycle event handlers)
  |-- MCP Servers (external tool connections)
  |-- LSP Servers (code intelligence)
```

---

## 12. OpenClaw Specifics

### 12.1 Overview

OpenClaw is a free, open-source (MIT license) autonomous AI agent created by Peter Steinberger. It focuses on **personal automation** rather than coding specifically.

- **Language**: TypeScript
- **Stars**: 157K+ on GitHub
- **Primary interface**: Messaging platforms (WhatsApp, Telegram, Slack, Discord, Signal, iMessage, Teams, etc.)

### 12.2 Architecture Components

| Component | Description |
|-----------|-------------|
| **Gateway** | WebSocket control plane at `ws://127.0.0.1:18789`; single control plane for clients, tools, and events |
| **Pi Agent** | RPC-based agent runtime |
| **CLI** | Command-line interface |
| **WebChat UI** | Browser-based chat interface |
| **macOS App** | Native desktop application |
| **iOS/Android Nodes** | Mobile platform integrations |

### 12.3 Key Features

- **Browser Control**: Chrome/Chromium managed via CDP (Chrome DevTools Protocol)
- **Canvas + A2UI**: Agent-driven visual workspace
- **ClawHub**: Minimal skill registry for automatic skill discovery and installation
- **Multi-LLM Support**: Claude, DeepSeek, OpenAI GPT, and local models
- **Self-hosted**: Runs entirely on user's own devices

### 12.4 Security Concerns

Critical vulnerabilities documented within 48 hours of going viral (January 2026):
- Hundreds of publicly accessible installations leaking API keys
- Prompt injection attacks
- Malicious skills containing credential stealers
- Unrestricted shell command execution

**Lesson for Chelava**: Security must be a first-class concern from day one, not an afterthought.

---

## 13. Key Takeaways for Chelava

### 13.1 Architecture Recommendations

1. **Adopt the ReAct loop pattern**: The gather-context -> act -> verify cycle is proven and effective
2. **Build a rich, typed tool system**: Dedicated tools (Read, Edit, Grep, Glob) outperform raw shell commands for safety and control
3. **Layer the architecture**: Core agent loop -> Delegation layer (subagents) -> Extension layer (plugins, MCP, hooks)
4. **Design for concurrent tool execution**: Parallel tool calls are critical for performance

### 13.2 Context Management

5. **Implement a CLAUDE.md equivalent**: Hierarchical project configuration files are essential
6. **Auto-memory is high value**: Persistent, session-crossing memory significantly improves long-term effectiveness
7. **Plan for compaction**: Design context compression from the start; 200K tokens fill fast with tool results
8. **Separate stable context from conversation**: Move architectural decisions and conventions into persistent files

### 13.3 Security and Permissions

9. **Implement OS-level sandboxing**: Filesystem and network isolation are non-negotiable
10. **Multi-modal permission system**: Different modes for different trust levels (plan-only, accept-edits, full-auto)
11. **Read-before-write enforcement**: Prevent blind overwrites
12. **Learn from OpenClaw's failures**: Default-deny for network, validate all skill/plugin sources

### 13.4 Extensibility

13. **Hook system is critical**: Deterministic lifecycle hooks (pre/post tool execution) enable powerful automation
14. **MCP is the standard**: Full MCP client support is table stakes for 2026
15. **Plugin architecture enables ecosystem**: Distributable bundles of commands + agents + hooks + MCP configs
16. **Subagent delegation reduces context pressure**: Isolated context windows prevent main conversation bloat

### 13.5 Multi-Agent Coordination

17. **Task-based coordination**: Shared task lists with dependencies and file-locking for concurrent access
18. **Inter-agent messaging**: Direct teammate-to-teammate communication enables complex collaboration
19. **Delegate mode**: Team leads should be restricted to coordination when managing large teams
20. **Quality gates**: Hook-based enforcement at task completion and teammate idle points

### 13.6 Java-Specific Considerations

21. **JVM advantages**: Strong concurrency primitives (virtual threads, CompletableFuture) for parallel tool execution
22. **Type safety**: Java's type system can enforce tool input/output contracts at compile time
23. **GraalVM potential**: Native compilation for fast CLI startup (critical for developer experience)
24. **Rich ecosystem**: Leverage existing Java libraries for JSON-RPC, WebSocket, process management
25. **Enterprise alignment**: Java is dominant in enterprise environments where AI coding agents have high value

---

## 14. OpenClaw Infrastructure Components (Deep Dive)

This section documents the infrastructure patterns found in OpenClaw that Chelava needs Java-native equivalents for.

### 14.1 Gateway / Control Plane

OpenClaw's gateway (`ws://127.0.0.1:18789`) is a **WebSocket-based control plane** that serves as the single entry point for all client connections. Key characteristics:

- **WebSocket hub**: All clients (CLI, WebChat UI, macOS app, iOS/Android nodes) connect to the same WebSocket endpoint
- **Message routing**: The gateway routes JSON-RPC messages between clients and the Pi Agent runtime
- **Connection multiplexing**: Multiple clients can connect simultaneously and share the agent's state
- **Event forwarding**: Agent events (tool execution, responses, status changes) are forwarded to all connected clients
- **Single process**: The gateway runs in the same Node.js process as the agent runtime

**Chelava Equivalent**: The `chelava-infra` Gateway uses virtual thread-backed ConnectionManager with sealed interface-typed connections (stdio, WebSocket, in-process). Request routing uses pattern matching on sealed GatewayRequest types for compile-time exhaustiveness.

### 14.2 Heartbeat / Health Monitoring

OpenClaw implements heartbeat and health monitoring through several mechanisms:

- **Service heartbeat**: Components periodically send heartbeat messages through the gateway to confirm liveness
- **Connection monitoring**: The WebSocket gateway detects disconnected clients via ping/pong frames
- **Agent health**: The Pi Agent runtime tracks its own health (memory usage, active tasks, error rates)
- **Platform node health**: Mobile and desktop nodes report their connectivity status
- **Reconnection logic**: When a connection drops, clients attempt automatic reconnection with exponential backoff

**Chelava Equivalent**: The `HealthMonitor` uses `StructuredTaskScope` to check all components in parallel. `HeartbeatSender` uses `ScheduledExecutorService` with virtual thread factory. Health status uses sealed interfaces (`Healthy`, `Degraded`, `Unhealthy`, `Unknown`) for exhaustive handling.

### 14.3 Cron / Scheduler

OpenClaw uses periodic scheduling for several maintenance tasks:

- **Skill registry refresh**: Periodically checks ClawHub for updated skills
- **Memory consolidation**: Merges and optimizes conversation history
- **Cache cleanup**: Removes expired cached data (web fetches, tool results)
- **Health checks**: Regular health status polling
- **Session timeouts**: Closes inactive sessions after configurable timeout

The implementation uses Node.js `setInterval` and `setTimeout`, which are single-threaded. Long-running scheduled tasks can delay other scheduled work.

**Chelava Equivalent**: The `VirtualThreadScheduler` uses `ScheduledExecutorService` with virtual thread factory, enabling truly concurrent scheduled task execution. Each scheduled task runs on its own virtual thread, so a slow task cannot delay others.

### 14.4 Event System

OpenClaw uses a Node.js EventEmitter-based event system for internal communication:

- **Event types**: Agent events (response, tool_use, error), system events (startup, shutdown), platform events (message_received, connection_changed)
- **Pub/sub pattern**: Components publish events; multiple listeners can subscribe
- **Synchronous dispatch**: EventEmitter dispatches synchronously in Node.js (blocking the event loop during handler execution)
- **No type safety**: Event names are strings; payloads are untyped objects
- **No backpressure**: Fast publishers can overwhelm slow subscribers

**Chelava Equivalent**: The `InProcessEventBus` uses sealed interface event types for compile-time type safety. Each subscriber has its own `BlockingQueue` and virtual thread, providing natural backpressure via queue capacity. Events are dispatched asynchronously - the publisher uses `offer()` (non-blocking, drops if queue full) and each subscriber runs on its own virtual thread doing `queue.take()` in a loop. Simple try/catch error handling replaces reactive onError callbacks.

### 14.5 Graceful Shutdown

OpenClaw handles process termination through:

- **SIGTERM/SIGINT handlers**: Registered via Node.js `process.on('SIGTERM', ...)`
- **Connection draining**: Gateway stops accepting new connections, waits for in-flight requests
- **State persistence**: Saves conversation state and memory before exit
- **Platform disconnection**: Notifies connected platforms (WhatsApp, Telegram, etc.) of shutdown
- **Timeout enforcement**: Forces exit after a maximum wait period

**Chelava Equivalent**: The `GracefulShutdownManager` uses ordered `ShutdownParticipant` list with priority-based execution. Each participant runs on a virtual thread with a time budget from the overall shutdown timeout. JVM shutdown hooks and `Signal` handling provide the entry points.

### 14.6 Error Recovery

OpenClaw handles errors through:

- **Try/catch with retry**: API calls (LLM, platform APIs) wrapped in retry logic
- **Reconnection**: Automatic reconnection for WebSocket and platform connections
- **Fallback models**: Can fall back from one LLM provider to another on failure
- **Error logging**: Errors logged to structured output for debugging
- **No circuit breakers**: OpenClaw does not implement circuit breaker patterns; failed services are retried indefinitely

**Chelava Equivalent**: The `CircuitBreaker` adds a state machine (CLOSED -> OPEN -> HALF_OPEN) to prevent cascading failures. `FailoverLLMClient` chains multiple providers with circuit breaker protection. `RetryPolicy` provides configurable exponential backoff.

### 14.7 Key Differences: OpenClaw vs Chelava Infrastructure

| Aspect | OpenClaw (TypeScript) | Chelava (Java) |
|--------|----------------------|----------------|
| Gateway | WebSocket hub, single-threaded | Virtual thread ConnectionManager, multi-protocol |
| Events | EventEmitter (string keys, untyped) | Sealed interface events, BlockingQueue + virtual thread per subscriber |
| Health | Basic heartbeat over WebSocket | StructuredTaskScope parallel checks, typed status |
| Scheduler | setInterval (blocks event loop) | ScheduledExecutorService + virtual threads |
| Shutdown | process.on('SIGTERM') + timeouts | Ordered ShutdownParticipant with priority |
| Error recovery | Try/catch + retry | Circuit breaker + failover + typed retry policies |
| Type safety | Runtime validation only | Compile-time sealed interface exhaustiveness |
| Concurrency | Single-threaded event loop | Virtual threads (true parallelism) |

---

## 15. Key Takeaways for Chelava (Updated)

### 15.1 Infrastructure Recommendations (New)

26. **Implement a gateway control plane**: Single entry point for all client types (CLI, IDE, MCP); use virtual thread-backed connection management
27. **Health monitoring is essential**: Parallel health checks via StructuredTaskScope; sealed status types; heartbeat for agent teams
28. **Scheduler with virtual threads**: Use ScheduledExecutorService with virtual thread factory for truly concurrent periodic tasks
29. **Type-safe event bus**: Sealed interface events with BlockingQueue per subscriber + virtual threads; natural backpressure via queue capacity
30. **Graceful shutdown**: Ordered participant shutdown with priority and time budgets; persist state before releasing resources
31. **Circuit breakers for external calls**: State machine (CLOSED/OPEN/HALF_OPEN) for LLM APIs and MCP servers; prevent cascading failures
32. **LLM provider failover**: Chain multiple providers with circuit breaker protection; automatic fallback on failure

---

## 16. Skills and Communication: OpenClaw/Claude Code vs Chelava

### 16.1 Skill Registry: Static (ClawHub) vs Adaptive (Chelava)

| Aspect | OpenClaw (ClawHub) | Claude Code | Chelava |
|--------|-------------------|-------------|---------|
| **Skill format** | YAML + JS/TS handlers | SKILL.md (Markdown + YAML frontmatter) | SKILL.md + JSON metrics sidecar |
| **Discovery** | ClawHub registry (static, pull-based) | File system scan at startup | File system scan + auto-generated proposals |
| **Learning** | None - skills are static | None - skills are static | Continuous: tracks success/failure/corrections per skill |
| **Auto-generation** | Not supported | Not supported | Proposes new skills when auto-memory detects repeated patterns |
| **Refinement** | Manual update only | Manual update only | Automatic: LLM analyzes failure patterns and improves instructions |
| **Metrics** | None | None | Per-skill: invocation count, success rate, correction rate, avg turns |
| **Scoring** | None (alphabetical) | Description matching only | Composite score (success + recency + frequency) with time decay |
| **Versioning** | Git-based (external) | None | Built-in version history with rollback support |
| **Lifecycle** | Install / Uninstall | Active / Disabled | Draft -> Active -> Deprecated -> Disabled (sealed interface) |
| **Memory link** | None | None | Skills linked to auto-memory entries (PATTERN, MISTAKE, STRATEGY) |
| **Security** | Minimal (led to credential stealers) | Plugin validation | HMAC-signed memory, content sanitization, Draft requires user approval |

**Key insight**: OpenClaw's ClawHub is a **distribution channel** (like npm for skills), while Chelava's adaptive system is a **learning engine** that evolves skills based on usage. Claude Code's skills are purely static Markdown files. Chelava bridges all three: static definitions (SKILL.md), distribution (plugins), and adaptive learning (metrics + refinement).

### 16.2 Inter-Agent Communication: Claude Code Faithful Port

Chelava's team communication faithfully ports Claude Code's file-based inbox model to Java, with a dual-mode transport layer that selects in-process (BlockingQueue) or file-based (JSON + FileLock) delivery based on the teammate's execution backend. The message types are ported as a sealed interface hierarchy for compile-time exhaustiveness.

| Aspect | OpenClaw | Claude Code (Agent Teams) | Chelava |
|--------|---------|--------------------------|---------|
| **Pattern** | Gateway WebSocket hub | File-based inbox per agent | Dual-mode: BlockingQueue (in-process) or file-based inbox (cross-process) |
| **Coupling** | Platform-coupled (adapters) | Agent-coupled (know recipient name) | Agent-coupled (know recipient name), same as Claude Code |
| **1:1 messaging** | Via platform API | Append to `inboxes/{name}.json` | `TeamMessageRouter.send(DirectMessage)` -> inbox delivery |
| **Broadcast** | Gateway event forwarding | Copy to all teammate inboxes | `TeamMessageRouter.broadcast(Broadcast)` -> all inboxes |
| **Message types** | Untyped JSON blobs | Typed JSON: message, broadcast, shutdown_request/response, plan_approval_request/response, idle_notification | Sealed interface: DirectMessage, Broadcast, ShutdownRequest/Response, PlanApprovalRequest/Response, IdleNotification |
| **Delivery model** | Push via WebSocket | Between turns, injected as user turn (consumes tokens) | Between turns, injected as user turn (consumes tokens) - same as Claude Code |
| **Idle notifications** | Not applicable | Auto-sent when teammate finishes turn; includes peer DM summaries | Auto-sent when teammate finishes turn; includes PeerDmSummary list |
| **Peer DM visibility** | Not applicable | Lead sees summary of peer DMs via idle notification | Lead subscribes to peer-dms topic for PeerDmSummary records |
| **Persistence** | None (transient) | JSON files on disk | In-process: transient (BlockingQueue). Cross-process: JSON files with atomic write |
| **Concurrency safety** | Single-threaded | Atomic write (tempfile + rename) + .lock files | In-process: BlockingQueue. Cross-process: `FileLock` + `Files.move(ATOMIC_MOVE)` |
| **Inbox watching** | Not applicable | File polling between turns | In-process: `BlockingQueue.poll()`. Cross-process: NIO `WatchService` |
| **Backpressure** | None | Implicit (file system) | Bounded BlockingQueue capacity (in-process) |
| **Ordering** | No guarantees | FIFO per inbox file | FIFO per inbox (both modes) |
| **Dependencies** | External (WebSocket libs) | Node.js fs, process management | Zero (java.base: BlockingQueue, ConcurrentHashMap, FileChannel, FileLock) |
| **Type safety** | Runtime only | Runtime only (JSON parsing) | Compile-time sealed interface exhaustiveness + JSON serialization |
| **Health monitoring** | WebSocket ping/pong | 5-minute heartbeat timeout | HeartbeatPing on dedicated topic; same 5-minute timeout |

**Key insight**: Chelava follows Claude Code's communication design closely - same inbox-per-agent model, same between-turns delivery, same idle notification with peer DM summaries. The Java advantage is dual-mode transport: in-process teammates use zero-copy `BlockingQueue` delivery (no file I/O, no serialization), while cross-process teammates use the same file-based protocol as Claude Code but with Java's `FileLock` and `Files.move(ATOMIC_MOVE)` for safer concurrent access.

### 16.3 Agent Team Architecture Comparison

```
OpenClaw:
  Gateway (WebSocket) <---> Platform Adapters <---> External Messaging
  (single control plane, all communication routes through gateway)

Claude Code:
  Team Lead <--- File-based Inboxes (JSON + .lock) ---> Teammate A
       |                                                 Teammate B
       +--- Per-task JSON files (file-locked) ---------- Teammate C
       |
       +--- config.json (member registry)
       +--- Idle notifications (auto, with peer DM summaries)
  (identity-based messaging, file system coordination, between-turn delivery)

Chelava (faithful Claude Code port with Java advantages):
  Team Lead
       |
       |--- [In-Process Mode] ---+--- Teammate A (virtual thread)
       |    BlockingQueue/agent  |--- Teammate B (virtual thread)
       |    InMemoryTaskStore    |    (zero-copy, ~40% less resources)
       |    ReentrantReadWriteLock
       |
       |--- [Cross-Process Mode] ---+--- Teammate C (external JVM)
       |    File inboxes + FileLock  |   (full isolation, plugin support)
       |    FileTaskStore            |
       |    NIO WatchService         |
       |
       +--- config.json (member registry, same as Claude Code)
       +--- Per-task JSON files (same as Claude Code)
       +--- Idle notifications with PeerDmSummary (same as Claude Code)
       +--- Sealed TeamMessage hierarchy (Java compile-time safety)
```

### 16.4 Task Coordination Comparison

| Aspect | OpenClaw | Claude Code | Chelava |
|--------|---------|-------------|---------|
| **Task storage** | Not applicable | Per-task JSON files in `~/.claude/tasks/{team}/` | Per-task JSON files in `~/.chelava/tasks/{team}/` (same) |
| **Task model** | Not applicable | id, subject, description, status, owner, activeForm, blockedBy, blocks, metadata, createdAt, updatedAt | Identical: `AgentTask` record with same fields |
| **Status lifecycle** | Not applicable | pending -> in_progress -> completed (+ deleted) | `TaskStatus` enum: PENDING -> IN_PROGRESS -> COMPLETED (+ DELETED) |
| **ID generation** | Not applicable | Auto-incrementing integer (counter file) | Auto-incrementing integer (counter.txt or AtomicInteger) |
| **Concurrency** | Not applicable | File locks (.lock files) | In-process: `ReentrantReadWriteLock`. Cross-process: `FileLock` |
| **Auto-unblocking** | Not applicable | Completing a task removes it from dependents' blockedBy | Same: `unblockDependents()` on completion |
| **Claiming priority** | Not applicable | Lowest ID first | Same: sorted by `Integer.parseInt(id)` ascending |
| **Plan approval** | Not applicable | plan_approval_request/response protocol | Same: `PlanApprovalRequest`/`PlanApprovalResponse` sealed records |

---

## Sources

- [Claude Code Documentation](https://code.claude.com/docs/)
- [Claude Agent SDK](https://www.anthropic.com/engineering/building-agents-with-the-claude-agent-sdk)
- [MCP Specification](https://modelcontextprotocol.io/specification/2025-11-25)
- [OpenClaw GitHub](https://github.com/openclaw/openclaw)
- [Claude Code Tools and System Prompt](https://gist.github.com/wong2/e0f34aac66caf890a332f7b6f9e2ba8f)
- [Internal Claude Code Tools Implementation](https://gist.github.com/bgauryy/0cdb9aa337d01ae5bd0c803943aa36bd)
- [Claude Code Hooks Guide](https://code.claude.com/docs/en/hooks-guide)
- [Claude Code Subagents](https://code.claude.com/docs/en/sub-agents)
- [Claude Code Agent Teams](https://code.claude.com/docs/en/agent-teams)
- [Claude Code Plugins](https://code.claude.com/docs/en/plugins)
- [Claude Code Memory](https://code.claude.com/docs/en/memory)
- [Claude Code Sandboxing](https://code.claude.com/docs/en/sandboxing)
- [OpenCode - AI Coding Agent (InfoQ)](https://www.infoq.com/news/2026/02/opencode-coding-agent/)
- [OpenClaw vs Claude Code (DataCamp)](https://www.datacamp.com/blog/openclaw-vs-claude-code)
