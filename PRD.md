# AceClaw - Product Requirements Document

## A High-Performance, Secure, Self-Learning AI Coding Agent for Java

**Version**: 1.2
**Date**: 2026-04-28
**Status**: Draft
**Team**: AceClaw PRD Team (Architect, OpenClaw Expert, PO, Security Expert, Engineer, Frontend Developer)
**Change Log**:
- v1.2 (2026-04-28): Explicit swarm trigger (`/swarm`, `/team`), DAG-only-at-swarm-tier architecture decision, three-tier execution model (§4.9.3), auto-escalation from Plan to Swarm
- v1.1 (2026-04-28): Added research-backed Multi-Agent Swarm Architecture (§4.9.2–4.9.6), Meta-Agent concept, Worker lifecycle, real-time Swarm Dashboard, updated Roadmap Phase 4–5
- v1.0 (2026-02-16): Initial PRD

---

> This PRD is future-facing product design, not the canonical current-state implementation spec.
> For what is implemented on `main`, use `README.md`, `docs/self-learning.md`, `docs/memory-system-design.md`, and the continuous-learning runbooks.

---

## Table of Contents

1. [Product Vision](#1-product-vision)
2. [Problem Statement](#2-problem-statement)
3. [Target Users](#3-target-users)
4. [Core Features](#4-core-features)
5. [Java Differentiation](#5-java-differentiation)
6. [System Architecture](#6-system-architecture)
7. [Security Design](#7-security-design)
8. [Technology Stack](#8-technology-stack)
9. [Frontend / CLI Design](#9-frontend--cli-design)
10. [Memory and Self-Learning Strategy](#10-memory-and-self-learning-strategy)
11. [MVP Scope](#11-mvp-scope)
12. [Success Metrics](#12-success-metrics)
13. [Risks and Mitigations](#13-risks-and-mitigations)
14. [Research Foundation — Multi-Agent Swarm](#14-research-foundation--multi-agent-swarm)

---

## 1. Product Vision

**AceClaw** reimagines the AI coding agent experience by leveraging the Java ecosystem's strengths: type safety, concurrency, enterprise maturity, and GraalVM native compilation.

While existing agents like Claude Code (TypeScript/Node.js) and OpenClaw (TypeScript) have proven the interactive AI coding paradigm, AceClaw delivers the same powerful workflow with the performance characteristics, security guarantees, and operational maturity that enterprise environments demand.

### Why Java?

- **Startup performance**: GraalVM native image delivers <50ms startup, eliminating JVM cold start
- **True parallelism**: Virtual threads (Project Loom) enable genuine concurrent tool execution, not just async interleaving
- **Type safety**: Sealed interfaces, records, and pattern matching create self-documenting, error-resistant code
- **Enterprise ecosystem**: Decades of production-grade libraries for logging, monitoring, security, and integration
- **Security by design**: JPMS module encapsulation, sealed class hierarchies, and mature cryptography libraries

### Vision Statement

> AceClaw is a persistent, always-on AI agent that turns your device into an intelligent coding companion. Built on Java with daemon-first architecture, it delivers reliability, security, and speed — running as a system service that learns, acts proactively, and serves all your development tools.

---

## 2. Problem Statement

Existing AI coding agents have proven the paradigm is powerful, but current implementations have gaps:

| Problem | Current State | AceClaw Solution |
|---------|--------------|-----------------|
| Slow startup | Node.js agents take 500-800ms to start | GraalVM native image: <50ms startup |
| High memory usage | 80-120MB idle, 200-400MB active | 20-30MB idle, 60-120MB active |
| Limited parallelism | Single-threaded event loop; CPU-bound tools block everything | Virtual threads enable true parallel tool execution |
| Security vulnerabilities | OpenClaw had critical security failures within 48 hours of going viral (API key leaks, prompt injection, malicious skills) | Security-first design with sealed permission types, OS-level sandbox, HMAC-signed memory |
| No type safety | Runtime-only guarantees in JS/TS | Compile-time exhaustive checks via sealed interfaces |
| Enterprise gaps | Limited monitoring, no audit trail, weak credential management | Micrometer metrics, structured audit logging, OS keyring integration |
| Distribution complexity | Requires Node.js runtime + npm install | Single binary download |

---

## 3. Target Users

### Solo Developer ("Alex")
- Full-stack developer, freelancer or startup engineer
- Wants fast AI assistance without heavy IDE plugins
- **AceClaw Value**: Instant startup, multi-LLM switching, single binary, lightweight resource usage

### Team Lead ("Jordan")
- Engineering team lead at a mid-size company (20-50 engineers)
- Needs consistent tooling and coding standard enforcement
- **AceClaw Value**: Project-level `.aceclaw/` config, hook system for CI/CD, permission model, team-wide policies

### Enterprise Architect ("Morgan")
- Principal engineer at a Fortune 500 company
- Security compliance, audit requirements, on-premise LLM deployment
- **AceClaw Value**: Enterprise-grade security, JVM sandbox, JPMS encapsulation, Micrometer metrics, Ollama support

---

## 4. Core Features

### 4.0 Daemon-First Architecture — Device as Agent

AceClaw runs as a **persistent system daemon**, turning your device into an always-on AI agent. The CLI, IDE plugins, and other clients are all thin clients that connect to this daemon.

**Startup Modes**:

| Command | Mode | Description |
|---------|------|-------------|
| `aceclaw` | Interactive CLI | Auto-starts daemon if needed, connects via Unix Domain Socket, opens REPL |
| `aceclaw <prompt>` | One-shot | Sends prompt, waits for response, exits. Daemon stays alive |
| `aceclaw daemon start` | Explicit daemon | Foreground start (for systemd/launchd) |
| `aceclaw daemon --background` | Background daemon | Fork and daemonize, write PID to `~/.aceclaw/aceclaw.pid` |
| `aceclaw daemon stop` | Shutdown | Graceful 15-step shutdown with state persistence |
| `aceclaw daemon status` | Health check | Shows daemon health, active sessions, memory usage |
| `aceclaw daemon install` | System service | Register as launchd (macOS) / systemd (Linux) login service |

**Client-Daemon Protocol**: JSON-RPC 2.0 over Unix Domain Socket (primary) or WebSocket (IDE/remote).

**Why Daemon-First**:
- Sessions survive client disconnects; Agent Teams run in background
- Proactive capabilities: cron tasks (HEARTBEAT.md), memory consolidation, codebase indexing
- Multiple concurrent sessions: CLI + IDE + Web UI simultaneously
- Warm daemon = instant CLI connect (<10ms vs cold-start)
- Background learning: skill refinement and memory optimization during idle periods

**Instance Locking**: File-based PID lock + Unix socket probe, stale lock detection (same as OpenClaw's `gateway-lock.ts` pattern).

**Proactive Agent Capabilities**:
- **HEARTBEAT.md**: Periodic agent health-check tasks (with active hours / quiet hours)
- **Cron jobs**: Persistent scheduled tasks (`~/.aceclaw/cron/jobs.json`) — daily test runs, dependency audits, etc.
- **Background memory consolidation**: Deduplication, pruning, skill proposal during idle time
- **Codebase indexing**: Incremental indexing of active projects during idle periods

**Platform Integration**: macOS launchd plist / Linux systemd user service, auto-restart on failure.

See [research/daemon-architecture.md](research/daemon-architecture.md) for detailed design.

### 4.1 Interactive CLI with Rich Terminal UI

A responsive, keyboard-driven terminal interface with streaming LLM output, markdown rendering, syntax highlighting, and rich interactive components.

- **Picocli** for command-line parsing with GraalVM native image support
- **JLine3** for terminal I/O: line editing (Emacs/Vi), history, tab completion, multi-line input
- **commonmark-java** with custom ANSI renderer for markdown terminal output
- Syntax highlighting for 11+ languages
- Streaming token display, progress spinners, diff viewer, permission dialogs
- Slash commands: `/help`, `/clear`, `/compact`, `/model`, `/tools`, `/undo`, `/exit`

### 4.2 Multi-LLM Support

First-class support for multiple LLM providers:

- **Anthropic Claude**: Opus, Sonnet, Haiku (primary)
- **OpenAI**: GPT-4o, o1/o3 series
- **Local Models via Ollama**: Llama, DeepSeek Coder, Qwen
- **Enterprise**: AWS Bedrock, Azure OpenAI

Features: unified chat completion interface, streaming support, automatic fallback chains, cost tracking, secure API key management.

### 4.3 Tool System

14+ built-in tools with a typed, extensible framework:

| Tool | Description | Permission Level |
|------|-------------|-----------------|
| ReadFile | Read files (multimodal: images, PDFs) | Auto-allow |
| WriteFile | Create/overwrite files (enforces read-before-write) | Requires approval |
| EditFile | Exact string replacement with uniqueness validation | Requires approval |
| BashExec | Shell commands with timeout and persistent session | Requires approval |
| GlobSearch | Fast file pattern matching | Auto-allow |
| GrepSearch | Content search with regex and multiline support | Auto-allow |
| WebFetch | Retrieve and process web content | Requires approval |
| WebSearch | Web queries with domain filtering | Requires approval |
| AskUser | Structured Q&A with multiple options | Auto-allow |

Key design:
- Tools defined as **sealed interfaces** with typed input/output records
- **Parallel execution** via virtual threads (independent tools run concurrently)
- Read-before-write enforcement, edit uniqueness validation
- Execution audit logging, timeout/resource limits

### 4.4 MCP (Model Context Protocol) Support

Full MCP client implementation for external tool integration:
- stdio and SSE transports
- Server discovery and capability negotiation
- Tool, resource, and prompt template support
- TLS 1.3, OAuth2 with PKCE authentication
- Input validation with size limits

### 4.5 Conversation Management (ReAct Loop)

The agent follows a proven **ReAct (Reason + Act)** loop:

```
User Query -> Plan -> Reason -> Act (Tool Execution) -> Observe -> Repeat
```

- Conversation history persistence as JSONL transcripts
- Automatic context compression at configurable threshold (~92%)
- Manual compaction via `/compact`
- Deterministic task resume capability
- Multi-turn tool use with result incorporation

### 4.5.1 Task Resume Routing (Deterministic, Isolation-Safe)

Resume behavior for natural-language inputs like `continue` / `resume` must follow a deterministic routing policy that preserves session/workspace isolation.

**Routing Priority (strict order)**:
1. **Current Session Scope**: resume unfinished task in current `sessionId`.
2. **Client-Instance Scope**: if no match in current session, search unfinished tasks under the same `clientInstanceId` and same workspace.
3. **Workspace Scope**: if still no match, search unfinished tasks in the same workspace and require deterministic tie-break.
4. **Fallback**: if no safe/unique match exists, return a disambiguation prompt instead of guessing.

**Deterministic tie-break policy**:
- Prefer foreground task
- Else most recently active (`updatedAt`)
- If still ambiguous, ask user to choose task ID

**Isolation hard constraints**:
- Default behavior MUST NOT auto-resume across workspaces.
- Default behavior MUST NOT auto-resume across client instances (for example CLI vs Web) unless explicitly requested by the user.
- Resume checkpoints must carry at least: `sessionId`, `taskId`, `workspaceHash`, `clientType`, `clientInstanceId`, `status`, `resumeHint`.

**Observability requirements**:
- Emit `resume.detected`, `resume.bound_task`, `resume.injected`, `resume.fallback`.
- Include `sessionId`, `taskId`, `workspaceHash`, and `clientInstanceId` in resume audit payloads.

### 4.5.2 Task Planner (Autonomous Planning & Execution)

For complex, multi-step tasks, AceClaw provides an **autonomous Task Planner** that decomposes user goals into structured, dependency-aware task graphs (DAGs) before execution begins — going beyond step-by-step ReAct reasoning.

**Core Capabilities**:
- **LLM-driven task decomposition**: Analyzes the user's goal and generates a DAG of sub-tasks with dependency ordering, execution strategies, and risk assessment
- **Automatic parallel branch detection**: Identifies independent sub-tasks that can execute simultaneously on virtual threads
- **Memory-informed planning**: Auto-memory patterns (STRATEGY, MISTAKE, PATTERN) directly influence plan generation — avoiding known pitfalls and reusing proven decomposition patterns
- **Adaptive replanning**: When a task fails mid-execution, the planner adjusts remaining tasks while preserving completed work (max 3 replan attempts before escalating to user)
- **Agent Teams integration**: Auto-materializes plan tasks into the shared TaskStore with dependency relationships, enabling team-based execution

**Complexity-Triggered Activation**:
- A `ComplexityEstimator` scores incoming requests using heuristics (multiple files, research needed, refactoring, ambiguity) and memory context
- Simple tasks proceed through the standard ReAct loop; complex tasks trigger upfront planning

**Execution Strategies** (sealed interface, compile-time exhaustive):
- `AgentLoop`: Execute within the main agent's ReAct loop (for tasks needing <5 tool calls)
- `Subagent`: Delegate to an isolated subagent (Explore, General, Plan)
- `AgentTeam`: Assign to a team member with optional plan approval
- `UserAction`: Requires human judgment or external setup

**Autonomy Levels**:
| Level | Plan Behavior |
|-------|---------------|
| **Conservative** | Always show plan and wait for explicit user approval |
| **Balanced** (default) | Show plan, auto-execute low-risk, prompt for high-risk |
| **Autonomous** | Auto-generate and execute; only pause for UserAction tasks |

**Plan Lifecycle**: `Draft -> AwaitingApproval -> Approved -> Executing -> Completed/Failed` (sealed `PlanStatus` interface)

**Post-Execution Learning**: Every plan outcome (success, failure, replan) feeds back into auto-memory as STRATEGY and MISTAKE entries, enabling progressively smarter planning over time.

See [research/task-planner-architecture.md](research/task-planner-architecture.md) for detailed design.

### 4.5.3 Current Implementation Snapshot (2026-03)

The sections above describe the target design. The current codebase has already implemented substantial parts of the roadmap, but several major areas remain intentionally partial:

- **Resume routing**: current implementation supports `session -> workspace` routing. `client-instance` scope and the full audit payload model remain planned work.
- **Task planner**: current implementation supports complexity estimation, LLM-generated plans, sequential execution, and replanning hooks. Full DAG execution, automatic parallel branch scheduling, and Agent Teams integration remain future work.
- **Learning maintenance**: current implementation performs session-close extraction and historical indexing immediately, then runs consolidation, cross-session mining, and trend detection via a deferred maintenance scheduler with time/session-count/size/idle triggers.
- **Security**: current implementation has permission policy and approval gating. OS-level sandboxing, trust-level content sandboxing, and encryption at rest remain roadmap items.

### 4.6 Project Configuration (.aceclaw/ Directory)

```
.aceclaw/
  ACECLAW.md          # Project instructions (team-shareable)
  config.json         # Model, tool, and behavior settings
  mcp-servers.json    # MCP server configurations
  hooks/              # Pre/post execution hooks
  memory/             # Agent memory store
  permissions.json    # Tool permission overrides
```

Configuration hierarchy: System defaults < Global (~/.aceclaw/) < Project (.aceclaw/) < Environment vars < CLI flags

### 4.7 Permission System

Multi-modal permission system balancing autonomy with safety:

| Mode | Behavior |
|------|----------|
| **Normal** (default) | Prompts for every dangerous operation |
| **Accept Edits** | Auto-accepts file edits, prompts for other ops |
| **Plan Mode** | Read-only operations only |
| **Auto-accept** | Eliminates permission prompts for the session |
| **Delegate** | Coordination-only (for multi-agent team leads) |

Four-tier permission decisions: **AutoAllow** -> **PromptOnce** -> **AlwaysAsk** -> **Deny**, implemented as sealed interfaces for compile-time exhaustiveness.

### 4.8 Hook System

Event-driven automation hooks for deterministic control:

| Event | Use Case |
|-------|----------|
| SessionStart | Re-inject context, setup environment |
| PreToolUse | Validation, block dangerous commands |
| PostToolUse | Auto-format, notifications, logging |
| Stop | Quality gates, test running |
| PreCompact | Re-inject critical context |
| TaskCompleted | Verification hooks |

Three hook types: **command** (shell scripts), **prompt** (single-turn LLM), **agent** (multi-turn verification).

### 4.9 Subagent Delegation

Separate agent instances for focused subtasks:

| Type | Model | Purpose |
|------|-------|---------|
| **Explore** | Fast (Haiku) | Read-only codebase search |
| **Plan** | Inherits | Research for plan mode |
| **General** | Inherits | Complex multi-step tasks |

Custom subagent definitions via Markdown + YAML frontmatter. Independent context windows, persistent memory, foreground/background execution.

### 4.9.1 Agent Teams (Multi-Agent Orchestration)

Agent Teams enable multiple AceClaw agent instances to work collaboratively on complex tasks. Faithfully ported from Claude Code's agent team design with Java enhancements.

**Architecture** (matching Claude Code):
- **Team Lead**: Main session that creates team, spawns teammates, coordinates work
- **Teammates**: Independent agent sessions with own context windows, communicating via typed messages
- **Shared Task List**: Per-task JSON files with auto-incrementing IDs, dependency tracking, and concurrent access via file locking
- **Inbox-based Messaging**: Sealed `TeamMessage` hierarchy (DirectMessage, Broadcast, ShutdownRequest/Response, PlanApprovalRequest/Response, IdleNotification) delivered between agent turns

**Java Advantages**:
- **In-process teammates**: Virtual threads in same JVM (~40% less resources), sharing LLM client pools and using `BlockingQueue` delivery (zero-copy, no file I/O)
- **Dual-mode transport**: `TeamMessageRouter` auto-selects `BlockingQueue` (in-process) or file-based (`FileLock` + `Files.move(ATOMIC_MOVE)`) transport per teammate
- **Sealed interface exhaustiveness**: All 7 message types enforced at compile time
- **In-memory task store**: `ReentrantReadWriteLock` for in-process teams (no file locking overhead)

**Coordination Patterns**: Delegate mode, plan approval, self-claiming, quality gates (hooks), parallel specialists, pipeline, competing hypotheses

### 4.9.2 Multi-Agent Swarm Architecture (Research-Backed)

> Based on synthesis of 7 core papers on Multi-Agent Systems (2025-2026): Multi-Agent Collaboration Survey (arXiv:2501.06322), SwarmAgentic (arXiv:2506.15672), LLM Swarm Intelligence (arXiv:2503.03800), SwarmSys (arXiv:2510.10047), Beyond Frameworks — ACL 2025 (arXiv:2505.12467), LLM-based MAS Survey (arXiv:2412.17481), IEEE SMC MAS×LLM (June 2025).

The current Agent Teams design (§4.9.1) covers in-process collaborative execution. This section extends it with **research-validated swarm intelligence patterns** for large-scale, observable, self-organizing multi-agent systems.

**Core Research Findings Driving This Design**:

| # | Finding | Confidence | Source | Design Impact |
|---|---------|------------|--------|---------------|
| 1 | Collaboration > Model Capability | 7/7 consensus | All papers | Invest in coordination architecture, not larger models |
| 2 | Architecture is task-dependent | 7/7 consensus | P1, P4, P5 | Hybrid: centralized planning + decentralized execution |
| 3 | Agent count saturates at 8-14 for reasoning | Strong | P4 (SwarmSys) | Cap worker count; parallelize I/O tasks, not reasoning |
| 4 | Fully automated agent team design is possible | Emerging | P2 (SwarmAgentic) | Meta-Agent auto-designs teams from goals |
| 5 | Centralized + instructor summarization = best cost/quality | Strong | P5 (ACL 2025) | Meta-Agent as instructor/summarizer |
| 6 | Adaptive profiles (memory units) enable specialization | Strong | P4 (SwarmSys) | Workers evolve profiles through task history |

### 4.9.3 Meta-Agent Architecture

The **Meta-Agent** is the central coordination intelligence that replaces static orchestration with **dynamic, research-informed swarm management**. It operates within the existing daemon process.

**Key Distinction**: Traditional Agent Teams (§4.9.1) use a Team Lead that is itself an agent running a ReAct loop. The Meta-Agent is a **coordination-only controller** — it does not execute tools directly; it plans, delegates, monitors, and adapts.

```
┌─────────────────────────────────────────────────────────────────┐
│                        AceClaw Daemon                           │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                     Meta-Agent                            │  │
│  │                                                           │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  │  │
│  │  │ Goal Decomp  │  │ Team Designer │  │ Cost Optimizer │  │  │
│  │  │ (Task DAG)   │  │ (SwarmAgentic │  │ (P5 patterns)  │  │  │
│  │  │              │  │  inspired)    │  │                │  │  │
│  │  └──────────────┘  └──────────────┘  └────────────────┘  │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  │  │
│  │  │ Worker Pool  │  │ Progress     │  │ Replan Engine  │  │  │
│  │  │ Manager      │  │ Monitor      │  │                │  │  │
│  │  └──────────────┘  └──────────────┘  └────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │ Worker 1 │  │ Worker 2 │  │ Worker 3 │  │ Worker N │      │
│  │ (VThread)│  │ (VThread)│  │ (VThread)│  │ (VThread)│      │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘      │
│       │              │              │              │            │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │               Shared State (JSON on Filesystem)          │  │
│  │  swarm-state.json:  worker profiles, task assignments,   │  │
│  │                     progress, metrics                    │  │
│  └─────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
         │
         ▼ WebSocket (localhost:PORT)
┌─────────────────────────────────────────────────────────────────┐
│                    Swarm Dashboard (Web)                         │
│  Real-time: Task DAG → Progress → Worker Status → Cost          │
└─────────────────────────────────────────────────────────────────┘
```

**Meta-Agent Responsibilities**:

| Responsibility | Implementation | Research Basis |
|----------------|----------------|----------------|
| **Goal Decomposition** | Parse user goal → generate Task DAG with dependencies | P1: structured decomposition |
| **Team Design** | Auto-select worker count, roles, and models based on task complexity | P2: SwarmAgentic joint optimization |
| **Cost Optimization** | Apply instructor-summarization pattern: Meta-Agent filters context for each worker, reducing tokens by up to 93% | P5: ACL 2025 pattern |
| **Worker Dispatch** | Spawn virtual-thread workers, assign tasks, manage lifecycle | P4: embedding-based matching |
| **Progress Monitoring** | Track task status, detect stalls, trigger replanning | P4: pheromone reinforcement |
| **Adaptive Replanning** | When workers fail, reassign or decompose further; max 3 replans | P4: Explorer/Worker/Validator cycle |

**Decision Flow — Three-Tier Execution Model**:

> **Architecture Decision**: DAG parallel execution is **only** implemented at the Swarm tier (Tier 3). The Plan tier (Tier 2) remains strictly linear. This is a deliberate design choice — see §4.9.7 for rationale.

```
User Input
  │
  ├── Explicit swarm trigger? ────────────────────────────→ Tier 3: Swarm Mode
  │   (/swarm, /team prefix, or                                │
  │    "agent team"/"agent swarm" in text)                     ▼
  │                                                    Meta-Agent (§4.9.3)
  │                                                    DAG parallel execution
  │
  ├── plannerEnabled && score ≥ 5? ───────────────────→ Tier 2: Plan Mode
  │   (ComplexityEstimator regex heuristic)                    │
  │                                                            ▼
  │                                                    SequentialPlanExecutor
  │                                                    Linear steps + fallback + replan
  │                                                            │
  │                                                    Plan fails 2+ times?
  │                                                    ──→ Auto-escalate to Tier 3 ↑
  │
  └── Default ────────────────────────────────────────→ Tier 1: ReAct Mode
                                                               │
                                                               ▼
                                                       AgentLoop (single agent)
                                                       Tool-level parallel only
```

**Explicit Swarm Trigger**:

The primary entry to Swarm Mode is **user-initiated**, not automatic score-based routing. This is a deliberate UX decision:

| Trigger | Syntax | Example |
|---------|--------|---------|
| **Slash command** (primary) | `/swarm <goal>` or `/team <goal>` | `/swarm refactor the auth module, add tests, deploy` |
| **Natural language** (fallback) | Include "agent team" or "agent swarm" in prompt | `用 agent team 帮我重构 auth 模块` |
| **Auto-escalation** | Plan Mode fails 2+ consecutive replans | *(automatic, notifies user)* |

Why explicit > automatic:
- `ComplexityEstimator` is English-only regex — **Chinese prompts always score 0**, making automatic triggering impossible for CJK users
- Short prompts for complex tasks (e.g., "重构整个项目") get low scores despite high actual complexity
- User intent is **transparent** — you know exactly which execution tier you're in
- Zero false positives — no accidental swarm spawning on simple tasks

**Swarm command options** (v1 = minimal, future = extended):

```bash
# v1 (Phase 5 launch)
/swarm <goal>              # Meta-Agent decides workers, roles, DAG
/team <goal>               # Alias for /swarm

# Future extensions
/swarm --workers=3 <goal>          # Hint: max 3 workers
/swarm --strategy=parallel <goal>  # Hint: prefer parallel branches
/swarm --model=haiku <goal>        # Force cheap model for workers
/swarm --dry-run <goal>            # Show plan without executing
```

**Routing implementation** (in `StreamingAgentHandler`):

```java
// Priority-ordered routing
if (SwarmCommandParser.isSwarmTrigger(prompt)) {        // /swarm, /team, "agent team"
    String goal = SwarmCommandParser.extractGoal(prompt);
    return swarmMode(goal, session);
    
} else if (plannerEnabled) {
    var score = complexityEstimator.estimate(prompt);
    if (score.shouldPlan()) {                            // score ≥ 5
        return planMode(prompt, session);                // linear, may auto-escalate
    }
}
return reactMode(prompt, session);                       // default
```

### 4.9.4 Worker Lifecycle and Spawning

**Critical Design Decision**: Workers are **in-process virtual threads** within the daemon JVM, not separate terminal sessions or processes. This directly addresses the observability problem — all workers share the daemon's event bus, state, and monitoring infrastructure.

```java
// Worker spawning — all within the same daemon JVM
try (var scope = StructuredTaskScope.open()) {
    for (PlannedTask task : readyTasks) {
        scope.fork(() -> {
            var worker = WorkerAgent.create(task, workerProfile, llmClient);
            worker.execute();  // Runs ReAct loop on virtual thread
            return worker.result();
        });
    }
    scope.join();
}
```

**Worker Profile Evolution** (inspired by P4 SwarmSys Adaptive Profiles):

| Profile Field | Description | Update Trigger |
|---------------|-------------|----------------|
| `role` | Specialized role (coder, reviewer, researcher, tester) | Assigned by Meta-Agent |
| `capabilities` | Tool/language/framework strengths | Updated after task outcomes |
| `performance` | Success rate, avg completion time, token efficiency | Updated per task |
| `workload` | Current task count, estimated remaining work | Real-time |
| `history` | Recent task IDs + outcomes | Append per task |

Over time, Worker Profiles enable **intelligent task assignment**: the Meta-Agent routes code generation tasks to workers with high coding success rates, and review tasks to workers with strong validation track records.

**Worker Communication**:
- Workers communicate **only through shared state** (JSON files or in-memory `ConcurrentHashMap`)
- No direct worker-to-worker messaging (avoids N² communication complexity)
- Meta-Agent summarizes and distributes relevant context to each worker (instructor pattern, P5)
- Workers emit structured events via the daemon's EventBus for real-time monitoring

### 4.9.5 Swarm Dashboard (Real-Time Observability)

**Problem Statement**: Agent swarms are opaque — the user has one console but N workers executing in parallel. Without observability, the user cannot understand, control, or trust the swarm.

**Solution**: A lightweight web-based dashboard served by the daemon, rendering the Task DAG and worker status in real-time.

```
┌──────────────────────────────────────────────────────────────┐
│  AceClaw Swarm Dashboard                    [Stop] [Pause]   │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  Goal: "Refactor auth module to use OAuth2 + add tests"      │
│                                                              │
│  ┌─────────────────── Task DAG ───────────────────┐         │
│  │                                                 │         │
│  │  [✅ Research OAuth2 libs]                       │         │
│  │       │                                         │         │
│  │       ├── [🔄 Implement OAuth2 client] W1       │         │
│  │       │         │                               │         │
│  │       │         ├── [⏳ Write unit tests] W3     │         │
│  │       │         └── [⏳ Update API routes] W4    │         │
│  │       │                                         │         │
│  │       └── [🔄 Refactor auth middleware] W2      │         │
│  │                 │                               │         │
│  │                 └── [⏳ Integration tests] W5    │         │
│  │                                                 │         │
│  └─────────────────────────────────────────────────┘         │
│                                                              │
│  Workers                                                     │
│  ┌──────────┬──────────┬───────────┬──────────┬──────────┐  │
│  │ W1:Coder │ W2:Coder │ W3:Tester │ W4:Coder │ W5:Test  │  │
│  │ Haiku    │ Haiku    │ Haiku     │ Haiku    │ Sonnet   │  │
│  │ ●Active  │ ●Active  │ ○Waiting  │ ○Waiting │ ○Waiting │  │
│  │ 12/15tok │ 8/15tok  │ —         │ —        │ —        │  │
│  └──────────┴──────────┴───────────┴──────────┴──────────┘  │
│                                                              │
│  Cost: $0.12 | Tokens: 24.3k | Elapsed: 1m 32s | ETA: ~2m   │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│  Event Log                                                   │
│  19:23:15  W1  Edit: src/auth/oauth2-client.java (+142 -0)   │
│  19:23:08  W2  Read: src/middleware/auth.java                 │
│  19:22:54  META Spawned W1(Coder), W2(Coder)                 │
│  19:22:51  META Plan approved (5 tasks, 2 parallel branches)  │
└──────────────────────────────────────────────────────────────┘
```

**Dashboard Technical Design**:

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Server | Javalin (embedded in daemon) | Lightweight, virtual-thread native, ~100KB |
| Protocol | WebSocket (Server-Sent Events fallback) | Real-time push, low overhead |
| Frontend | Vanilla HTML/JS + D3.js (for DAG rendering) | No build step, no framework dependency |
| State Source | Daemon EventBus → WebSocket bridge | Workers emit events, dashboard renders |

**Dashboard Endpoints**:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `GET /dashboard` | HTTP | Serve the single-page dashboard HTML |
| `WS /ws/swarm` | WebSocket | Real-time event stream (task status, worker events, cost) |
| `POST /api/swarm/pause` | HTTP | Pause all workers |
| `POST /api/swarm/stop` | HTTP | Graceful stop of swarm execution |
| `POST /api/swarm/approve/{taskId}` | HTTP | Approve a pending task |
| `GET /api/swarm/state` | HTTP | Current swarm state snapshot (JSON) |

**Key Feature**: The plan is shown **before execution** — since AceClaw pre-plans via the Task Planner (§4.5.2), the full Task DAG is visible in the dashboard the moment it's created, before any worker starts. The user sees the complete workflow upfront and can approve, modify, or reject it.

### 4.9.6 Swarm Cost Model and Scaling Rules

Based on research findings, AceClaw enforces the following swarm scaling rules:

| Rule | Value | Source | Rationale |
|------|-------|--------|-----------|
| Max reasoning workers | 14 | P4 SwarmSys | Performance saturates beyond this for reasoning tasks |
| Max parallel I/O workers | 50 | P6 survey | I/O tasks (file search, web fetch) scale linearly |
| Default worker model | Haiku (fast) | Cost | Workers use cheapest model; Meta-Agent uses Sonnet/Opus for planning |
| Token budget per worker | Configurable | P5 | Instructor-summarization reduces per-worker context |
| Cost guard | Configurable $/swarm | Safety | Auto-pause if total swarm cost exceeds threshold |
| Replan limit | 3 per swarm | P4 | Avoid infinite replan loops |

**Cost Optimization via Instructor-Summarization (P5 Pattern)**:

```
Traditional approach:
  Each worker gets full context → N × full_context_tokens → expensive

Meta-Agent as Instructor (P5 pattern):
  Meta-Agent reads full context once
  → Summarizes relevant subset for each worker
  → Each worker gets only its task-relevant context
  → Up to 93% token reduction (measured by P5)
```

This is AceClaw's **primary competitive advantage** over naive multi-agent systems: the Meta-Agent acts as an intelligent context filter, not just a task router.

### 4.9.7 Architecture Decision: DAG Only at Swarm Tier

> **Decision**: DAG parallel execution is implemented **exclusively** at the Swarm tier (Tier 3). The Plan tier (Tier 2, `SequentialPlanExecutor`) remains strictly **linear**. This is a deliberate architectural choice, not a missing feature.

**Rationale — why DAG at Plan tier is the wrong abstraction**:

| Issue | Plan Tier (Tier 2) | Swarm Tier (Tier 3) |
|-------|-------------------|---------------------|
| **Context model** | All steps share one `allMessages` conversation history. Parallel branches would require merging two divergent conversation streams — non-deterministic, high token cost | Each Worker has an **isolated** `AgentLoop` with independent conversation history. No merge needed |
| **Failure propagation** | One step failure in a DAG branch affects the entire plan's shared context | One Worker failure is contained — Meta-Agent reassigns or decomposes |
| **Replan complexity** | Replanning mid-DAG requires re-wiring dependency graph while preserving partial shared context | Meta-Agent simply re-decomposes remaining goals from scratch |
| **Cost control** | Shared context accumulates across all branches — context window bloat | Instructor-summarization (P5) gives each Worker only its relevant subset — up to 93% token savings |
| **Observability** | Parallel branches in a single console = interleaved output, unreadable | Swarm Dashboard renders DAG visually with per-Worker status |

**What Tier 2 already provides without DAG**:

```
Scenario: "Read 3 files, fix each one, run tests"

Tier 2 (Linear Plan):
  Step 1: "Read and understand files a, b, c"
    └─ AgentLoop turn: read_file(a) ┐
                       read_file(b) ├─ StructuredTaskScope ← tool-level parallel!
                       read_file(c) ┘
  Step 2: "Fix file a"  → AgentLoop
  Step 3: "Fix file b"  → AgentLoop
  Step 4: "Fix file c"  → AgentLoop
  Step 5: "Run tests"   → AgentLoop

Steps 2-4 are sequential, but for score 3-6 tasks this is acceptable.
Tool-level parallelism (Tier 1's StructuredTaskScope) already handles
the most common "read multiple files" pattern within a single step.
```

**Only genuinely parallel workloads need Swarm**:

```
Scenario: "Refactor auth, add tests, update docs — all independently"

Tier 3 (Swarm DAG):
  Worker 1: Refactor auth  ─┐
  Worker 2: Write tests     ├─ parallel, isolated AgentLoops
  Worker 3: Update docs     ─┘
                             │
                    Meta-Agent joins results
```

**Three-tier summary**:

```
┌─────────────────────────────────────────────────────────────────────┐
│                       AceClaw Execution Tiers                       │
├──────────────┬────────────────────────┬─────────────────────────────┤
│   Tier 1     │   Tier 2               │   Tier 3                    │
│   ReAct      │   Linear Plan          │   Swarm (DAG)               │
├──────────────┼────────────────────────┼─────────────────────────────┤
│              │                        │                             │
│  Trigger:    │  Trigger:              │  Trigger:                   │
│  Default     │  score ≥ 5 (auto)      │  /swarm or /team (explicit) │
│              │  or /plan (explicit)   │  or auto-escalation         │
│              │                        │                             │
│  Execution:  │  Execution:            │  Execution:                 │
│  Single      │  Sequential steps      │  DAG with parallel branches │
│  ReAct loop  │  Shared context        │  Isolated Workers           │
│              │  fallback + replan     │  Meta-Agent coordination    │
│              │                        │                             │
│  Parallel:   │  Parallel:             │  Parallel:                  │
│  Tool-level  │  Tool-level (per step) │  Task-level (Worker DAG)    │
│  (same turn) │  Steps are linear      │  + tool-level (per Worker)  │
│              │                        │                             │
│  Context:    │  Context:              │  Context:                   │
│  Shared      │  Shared (allMessages)  │  Isolated per Worker        │
│  (cumulative)│  (cumulative)          │  (instructor-summarized)    │
│              │                        │                             │
│  Status: ✅   │  Status: ✅             │  Status: Phase 5            │
└──────────────┴────────────────────────┴─────────────────────────────┘
```

**Auto-escalation** (Plan → Swarm):

When the Plan tier fails repeatedly, the system can automatically escalate to Swarm mode:

```java
// In SequentialPlanExecutor — after replan also fails
if (replanAttempt >= MAX_REPLAN_ATTEMPTS) {
    var escalation = AdaptiveReplanner.Escalated("Plan exhausted 3 replans");
    
    if (swarmEnabled && SwarmEscalationPolicy.shouldEscalate(plan, failureHistory)) {
        eventBus.publish(new PlanEvent.EscalatedToSwarm(plan.planId()));
        notifyUser("Plan mode exhausted — escalating to swarm mode");
        return swarmMode(plan.originalGoal(), session);  // hand off to Meta-Agent
    }
    return PlanResult.failed(escalation.reason());
}
```

This creates a natural **safety net**: complex tasks that were misjudged as medium-complexity (score 3-6) will auto-recover by escalating to the more powerful Swarm tier.

### 4.10 Adaptive Skills and Slash Commands

- **Skills**: Model-invoked capabilities auto-matched to user requests (`.aceclaw/skills/`), with **adaptive learning**:
  - **Skill Learning Loop**: Track execution outcomes (success/failure/user correction) -> auto-memory records insights -> skill refinement engine analyzes -> update SKILL.md -> next invocation is smarter
  - **Auto-Generated Skills**: When auto-memory detects repeated patterns (e.g., user always performs X in a certain way), automatically propose a new skill draft; user approves before activation
  - **Skill Metrics**: Per-skill tracking of invocation count, success rate, user correction rate, average turn count; score-based ranking with time decay for auto-invocation priority
  - **Skill Refinement**: After threshold failures or corrections, trigger LLM-powered refinement that analyzes failure patterns and improves skill instructions; version history supports rollback
  - **Skill Lifecycle**: `Draft -> Active -> Deprecated -> Disabled` (sealed interface states)
  - **Persistence**: SKILL.md (Markdown + YAML frontmatter) + JSON metrics sidecar files
- **Commands**: User-invoked slash commands (`.aceclaw/commands/`)
- **Plugins** (post-MVP): Distributable bundles of commands + skills + agents + hooks + MCP configs

---

## 5. Java Differentiation

### 5.1 Performance: GraalVM Native Image

| Metric | Node.js (Claude Code) | AceClaw (GraalVM Native) |
|--------|----------------------|--------------------------|
| Cold Start | ~500-800ms | **<50ms** |
| Memory (idle) | ~80-120MB | **~20-30MB** |
| Memory (active) | ~200-400MB | **~60-120MB** |
| Distribution | Requires Node.js + npm | **Single binary download** |
| Binary Size | N/A | **~50-80MB** |

### 5.2 Concurrency: Virtual Threads

True parallel tool execution via Java 21+ virtual threads:

```java
try (var scope = StructuredTaskScope.open()) {
    var fileSearch = scope.fork(() -> searchFiles(pattern));
    var codeGrep = scope.fork(() -> grepContent(query));
    var gitStatus = scope.fork(() -> getGitStatus());
    scope.join(); // All execute truly in parallel
}
```

- **Parallel file search**: Thousands of files searched simultaneously
- **Concurrent tool execution**: 5+ tools running in true parallel
- **Structured concurrency**: Automatic cancellation propagation, no orphaned threads
- **Scoped values**: Zero-cost context propagation (vs ThreadLocal/AsyncLocalStorage)

### 5.3 Type Safety

```java
sealed interface ToolResult permits ToolSuccess, ToolError, ToolTimeout {
    record ToolSuccess(String output) implements ToolResult {}
    record ToolError(String message, Throwable cause) implements ToolResult {}
    record ToolTimeout(Duration elapsed) implements ToolResult {}
}

// Compiler enforces exhaustive handling - adding a new variant
// causes compile errors everywhere it's used
switch (result) {
    case ToolSuccess s -> display(s.output());
    case ToolError e -> handleError(e);
    case ToolTimeout t -> retryOrAbort(t);
}
```

### 5.4 Enterprise Readiness

| Capability | Implementation |
|-----------|---------------|
| Modular Architecture | JPMS (Java Module System) |
| Logging | SLF4J + Logback |
| Monitoring | Micrometer + Prometheus |
| Audit Trail | Structured JSON audit logging with HMAC integrity |
| Configuration | Hierarchical config with type-safe validation |
| Testing | JUnit 5 + Mockito + Testcontainers + ArchUnit |

### 5.5 Self-Learning Memory and Adaptive Skills

Advanced memory architecture with Java-specific advantages:
- **Parallel memory retrieval** across all stores using virtual threads
- **Typed memory stores**: Separate stores for patterns, mistakes, preferences, codebase insights
- **HMAC-signed memory files** for tamper detection
- Pattern recognition, automatic memory consolidation, relevance scoring
- **Auto-memory feeds adaptive skills**: Accumulated PATTERN and STRATEGY memories trigger auto-generated skill proposals; MISTAKE memories drive skill refinement; per-skill metrics (success rate, correction rate) with time-decay scoring enable intelligent auto-invocation priority

---

## 6. System Architecture

### 6.1 Module Structure

```
aceclaw/
  aceclaw-bom/              # Bill of Materials (dependency management)
  aceclaw-daemon/           # Daemon process, boot sequence, lifecycle, instance lock
  aceclaw-core/             # Agent loop, task planner, tool system, LLM client abstractions
  aceclaw-infra/            # Gateway, event bus, message queue, health, scheduler, shutdown
  aceclaw-llm/              # LLM provider implementations
  aceclaw-tools/            # Built-in tools (file, bash, search, web, git)
  aceclaw-memory/           # Context management, auto-memory, self-learning
  aceclaw-security/         # Permission system, sandbox, audit logging
  aceclaw-mcp/              # MCP protocol client/server
  aceclaw-cli/              # Thin client: Terminal UI, CLI parsing, REPL (connects to daemon)
  aceclaw-sdk/              # Extension API for plugins and custom tools
  aceclaw-server/           # WebSocket listener for IDE/remote clients
  aceclaw-test/             # Test utilities and fixtures
```

### 6.2 Module Dependency Graph

```
aceclaw-cli ──> aceclaw-daemon (thin client connects via UDS)
                     |
                     v
              aceclaw-core ──> aceclaw-sdk (API contracts)
                     |                  ^
                     |                  |
                     v                  |
              aceclaw-llm        aceclaw-tools
                     |                  |
                     v                  v
              aceclaw-memory     aceclaw-security
                     |
                     v
              aceclaw-infra (gateway, events, health, scheduler)
                     ^
                     |
              aceclaw-daemon (orchestrates all components)
                     |
              aceclaw-server (WebSocket listener, optional)
              aceclaw-mcp    (MCP protocol)
```

### 6.3 Core Abstractions

**Daemon**: Persistent system process — boot, lock, session management, proactive capabilities

**Agent Loop**: ReAct cycle - prompt -> LLM -> check stop reason -> if tool_use: execute tools -> add results -> repeat

**Task Planner**: Goal decomposition -> DAG generation -> parallel execution -> replanning on failure

**Key interfaces** (all using sealed types):
- `AceClawDaemon`, `DaemonLock`, `DaemonConfig`, `SessionManager`, `AgentSession`
- `HeartbeatRunner`, `CronScheduler`, `CronJob`, `CronSchedule` (sealed: Interval, CronExpression, EventTriggered)
- `BootSystem`, `StateSerializer`, `LifecycleManager`
- `Agent`, `AgentLoop`, `Turn`, `StopReason`
- `TaskPlanner`, `PlanExecutor`, `ComplexityEstimator`, `PlanLearner`
- `TaskPlan`, `PlannedTask`, `TaskDependency`, `TaskCategory` (sealed), `ExecutionStrategy` (sealed)
- `PlanStatus` (sealed: Draft, AwaitingApproval, Approved, Executing, Replanning, Completed, Failed, Cancelled)
- `PlanEvent` (sealed: PlanCreated, PlanApproved, TaskStarted, TaskCompleted, TaskFailed, PlanReplanned, PlanCompleted, PlanFailed)
- `ReplanTrigger` (sealed: TaskFailure, NewInformation, UserFeedback, ResourceExhausted)
- `PlanValidation`, `PlanIssue` (sealed: CircularDependency, MissingTool, PermissionConflict, FileConflict, TokenBudgetExceeded)
- `Message` (sealed: UserMessage, AssistantMessage, ToolResultMessage, SystemMessage)
- `Tool`, `ToolResult`, `ToolPermission`, `ToolExecutionContext`
- `LLMClient`, `StreamSession`, `StreamEvent` (sealed: TextDelta, ToolUseDelta, ThinkingDelta, StreamComplete)
- `MemoryStore`, `ConversationContext`, `ContextWindow`, `AutoMemory`
- `PermissionPolicy`, `PermissionDecision`, `Sandbox`, `AuditEvent`

**Infrastructure interfaces** (aceclaw-infra):
- `Gateway`, `ConnectionManager`, `GatewayRequest` (sealed: Agent, Tool, MCP, Health, Admin)
- `EventBus`, `AceClawEvent` (sealed hierarchy: Agent, Tool, Health, Session, Team, Scheduler, System events)
- `MessageQueue`, `AgentMessage` (sealed: TaskAssignment, TaskResult, ChatMessage, BroadcastMessage, ShutdownRequest, PlanApproval, HeartbeatPing), `MessageHandler`, `MessageSubscription`
- `HealthMonitor`, `HealthCheckable`, `HealthStatus` (sealed: Healthy, Degraded, Unhealthy, Unknown)
- `Scheduler`, `ScheduledTask`, `SchedulePolicy`
- `CircuitBreaker`, `RetryPolicy`, `FailoverLLMClient`
- `GracefulShutdownManager`, `ShutdownParticipant`

**Agent team interfaces** (aceclaw-core):
- `TeamCommand` (sealed: CreateTeam, SpawnTeammate, ShutdownTeammate, DeleteTeam), `TeamManager`, `TeamHandle`, `TeammateHandle`
- `TeamMessage` (sealed: DirectMessage, Broadcast, ShutdownRequest, ShutdownResponse, PlanApprovalRequest, PlanApprovalResponse, IdleNotification), `PeerDmSummary`
- `TeamMessageRouter`, `InProcessMessageTransport`, `FileBasedMessageTransport`, `TeamMessageInjector`
- `AgentTask`, `TaskStatus` (PENDING, IN_PROGRESS, COMPLETED, DELETED), `TaskStore`, `TaskUpdate`
- `TeamConfig`, `TeamMember`, `TeammateConfig`, `TeammateBackend` (IN_PROCESS, EXTERNAL_PROCESS, TMUX)
- `TeamContext` (ScopedValues: TEAM_NAME, AGENT_ID, AGENT_NAME, AGENT_TYPE, PLAN_MODE_REQUIRED)

**Swarm interfaces** (aceclaw-core, Phase 5):
- `SwarmCommandParser` (detects `/swarm`, `/team`, "agent team" triggers; extracts goal and options)
- `SwarmEscalationPolicy` (decides when Plan tier auto-escalates to Swarm tier)
- `MetaAgent`, `GoalDecomposer`, `TeamDesigner`, `CostOptimizer`, `ProgressMonitor`, `ReplanEngine`
- `WorkerPool`, `WorkerAgent`, `WorkerProfile` (role, capabilities, performance, workload, history)
- `SwarmState`, `SwarmConfig`, `SwarmCostGuard`
- `SwarmEvent` (sealed: SwarmCreated, WorkerSpawned, TaskAssigned, TaskCompleted, TaskFailed, ReplanTriggered, SwarmCompleted, CostLimitReached, **EscalatedFromPlan**)
- `SwarmDashboard`, `DashboardWebSocket`, `DashboardState`

**Adaptive skills interfaces** (aceclaw-memory / aceclaw-core):
- `SkillState` (sealed: Draft, Active, Deprecated, Disabled), `SkillDefinition`, `SkillMetadata`, `SkillMetrics`
- `SkillRegistry`, `SkillMatch`, `SkillOutcomeTracker`, `SkillOutcome` (sealed: Success, Failure, UserCorrected)
- `SkillRefinementEngine`, `RefinementDecision` (sealed: NoActionNeeded, RefinementRecommended, DisableRecommended)
- `SkillProposalEngine` (auto-generates skill drafts from detected patterns)

### 6.4 Concurrency Architecture

- **Virtual threads** for parallel tool execution (StructuredTaskScope)
- **Scoped values** for request context propagation
- **Virtual thread blocking patterns** for LLM streaming responses (BlockingQueue-backed StreamSession) and event bus (BlockingQueue per subscriber with virtual thread per subscriber) - no reactive framework needed. Flow API is available for external library interop but is not used internally
- **In-process agent teams** via virtual threads (~40% resource reduction vs separate processes), with dual-mode transport: `BlockingQueue` for in-process teammates (zero-copy), file-based inbox with `FileLock` for cross-process teammates; sealed `TeamMessage` hierarchy for compile-time exhaustive handling of all 7 message types (DirectMessage, Broadcast, ShutdownRequest/Response, PlanApprovalRequest/Response, IdleNotification)
- **ScheduledExecutorService** with virtual thread factory for periodic tasks (health checks, memory consolidation)
- **Circuit breakers** with virtual thread-safe state machines for external service resilience

> **Virtual Threads over Reactive Streams**: Project Loom eliminates the need for reactive programming patterns (Flow, RxJava, Reactor). AceClaw uses simple blocking code on virtual threads instead. This results in simpler, more debuggable code with natural backpressure via BlockingQueue capacity. Flow API is available for external library interop but is not used internally.

### 6.4.1 Infrastructure Architecture

The infrastructure layer provides the operational backbone:
- **Gateway**: Central control plane routing requests from CLI, IDE, and MCP clients via virtual thread-backed connection management
- **Event Bus**: Type-safe internal communication using sealed interface events, dispatched asynchronously on virtual threads
- **Message Queue**: In-process, zero-dependency message queue for decoupled inter-agent communication; supports point-to-point queues, pub/sub topics, request-reply with correlation IDs, dead letter queues, message TTL, and bounded-queue backpressure; uses `BlockingQueue` + virtual threads (consistent with event bus pattern); serves as transport layer for agent team `TeamMessageRouter` with dual-mode delivery (in-process `BlockingQueue` or file-based inbox per teammate)
- **Health Monitor**: Parallel component health checks using StructuredTaskScope, heartbeat system for agent teams (5-minute timeout for crash detection)
- **Scheduler**: Periodic task execution with virtual threads; concurrent scheduled tasks (unlike Node.js single-threaded setInterval)
- **Circuit Breaker**: Fault tolerance for LLM API calls and MCP server communication; automatic failover between providers
- **Graceful Shutdown**: Ordered component shutdown with priority-based participant list and time budgets

### 6.5 GraalVM Strategy

Dual-mode build: native image (primary for CLI) + JVM fallback (for plugins).

| Component | Native Image | Rationale |
|-----------|-------------|-----------|
| aceclaw-core | Yes | Reflection-free by design (sealed types, records) |
| aceclaw-tools | Yes | File I/O, process execution - straightforward |
| aceclaw-llm | Yes | HTTP client + JSON serialization |
| aceclaw-cli | Yes | Primary distribution mode |
| aceclaw-sdk (plugins) | No | Plugin classloading requires JVM subprocess |

### 6.6 Plugin System

SPI-based tool discovery with classloader isolation:
- Plugins discovered via `ServiceLoader`
- Each plugin in isolated classloader (shared SDK API packages only)
- When plugins present, spawn lightweight JVM subprocess
- Preserves native image startup for common case (no plugins)

---

## 7. Security Design

### 7.1 Threat Model

Six threat actors identified:
1. **Malicious file content** - prompt injection via source files
2. **Malicious MCP servers** - tool poisoning, credential theft
3. **Compromised dependencies** - supply chain attacks
4. **Malicious repositories** - indirect prompt injection via git artifacts
5. **Local attackers** - session hijacking, credential theft
6. **The agent itself** - acting beyond intended scope

### 7.2 Permission System

Four-tier sealed hierarchy:

```java
sealed interface PermissionDecision permits AutoAllow, PromptOnce, AlwaysAsk, Deny
```

- Compiler enforces exhaustive handling of all permission types
- Tool-level and resource-level permission checks
- Session-scoped caching with configurable expiration
- Enterprise managed settings (read-only, cannot be overridden)

### 7.3 Execution Sandbox

OS-level process isolation:
- **macOS**: Seatbelt (sandbox-exec) - zero additional dependencies
- **Linux**: bubblewrap (bwrap) - namespace-based isolation
- Filesystem isolation: read-only root, writable project directory only
- Network isolation: proxy-based domain allowlist, block private IP ranges, require HTTPS
- Resource limits: execution time, memory, processes, file size

### 7.4 Credential Security

- **Platform-native keyring** via java-keyring (macOS Keychain, Linux DBus Secret Service, Windows Credential Manager)
- **Environment variable filtering**: default-deny with explicit allowlist
- **SecretString type**: custom `toString()` returns `***REDACTED***`, explicit `destroy()` zeroes memory
- **Credential redaction**: pattern-based detection in all logs and context

### 7.5 Audit & Compliance

- Structured JSON audit logging with **HMAC-SHA256 integrity** signatures
- Full session recording for review/replay
- SOC 2 / GDPR compliance mapping
- External log shipping support (syslog, SIEM)

### 7.6 Java Security Advantages

- **Sealed interfaces**: Compile-time exhaustive permission handling
- **JPMS modules**: Strong encapsulation, internal APIs truly hidden
- **ByteBuddy instrumentation**: Runtime security monitoring
- **JFR (Java Flight Recorder)**: Low-overhead security event recording
- **Memory safety**: No buffer overflows, use-after-free, or memory corruption
- **HMAC-signed memory files**: Novel tamper detection for self-learning memory (not in Claude Code or OpenClaw)

### 7.7 Lessons from OpenClaw

OpenClaw suffered critical failures within 48 hours of going viral (January 2026):
- Hundreds of publicly accessible installations leaking API keys
- Prompt injection attacks
- Malicious skills containing credential stealers
- Unrestricted shell command execution

AceClaw addresses all of these with: default-deny permissions, OS-level sandbox, skill/plugin validation, credential isolation, and audit logging.

---

## 8. Technology Stack

### 8.1 Framework Decision: Plain Java + Picocli (No Quarkus/Micronaut)

| Factor | Plain Java | Quarkus | Micronaut |
|--------|-----------|---------|-----------|
| Startup | **10-30ms** | ~49ms | ~50ms |
| Binary size | **~30-50MB** | ~45-75MB | ~40-65MB |
| Framework overhead | **None** | CDI container | DI codegen |
| GraalVM config | **Simplest** | Good | Good |
| Dependencies | **~4MB** | ~15-25MB | ~10-20MB |

Virtual threads work without framework wrappers. For DI, manual constructor injection with `AppContext` class (Dagger 2 if complexity grows).

### 8.2 Key Dependencies

| Dependency | Version | Size | Purpose |
|-----------|---------|------|---------|
| Picocli | 4.7.6 | ~400KB | CLI parsing + GraalVM annotation processor |
| JLine3 | 3.27.1 | ~1.5MB | Terminal I/O, line editing, history |
| Jackson | 2.18.2 | ~1.8MB | JSON serialization |
| commonmark-java | 0.23.0 | ~200KB | Markdown parsing |
| **Total runtime** | | **~4MB** | |

### 8.3 Build System

**Gradle Kotlin DSL** with GraalVM native-build-tools plugin.

```bash
./gradlew run                    # Development (JVM mode)
./gradlew test                   # Run tests
./gradlew nativeCompile          # Build native image
./gradlew nativeTest             # Native image tests
```

### 8.4 Java Version

**Target: Java 21 (LTS)** with `--enable-preview` for structured concurrency and scoped values.

Forward-compatible design for Java 25+ when structured concurrency stabilizes.

### 8.5 Testing

- **JUnit 5**: Test framework
- **Mockito 5**: Mocking (GraalVM-compatible inline mock maker)
- **WireMock**: HTTP API mocking (LLM API simulation)
- **Testcontainers**: Integration tests
- **ArchUnit**: Architecture rule enforcement

---

## 9. Frontend / CLI Design

### 9.1 Architecture

```
User Input -> JLine3 LineReader -> Command Parser -> Agent
Agent Response -> Token Aggregator -> Markdown Parser -> ANSI Renderer -> Terminal
```

### 9.2 Terminal Output

| Markdown Element | Terminal Rendering |
|---|---|
| `# Heading` | Bold + color |
| `**bold**` | ANSI bold |
| `` `code` `` | Inverse/background highlight |
| ```` ```code```` | Bordered box with syntax highlighting |
| `> blockquote` | Indented with vertical bar |
| `- list item` | Bullet with indent |
| `\| table \|` | Unicode box-drawing characters |

### 9.3 Streaming

Incremental rendering with `MarkdownStreamBuffer`:
- Text paragraphs: flush word-by-word
- Code blocks: buffer until complete, re-highlight
- Tables: buffer until complete
- Tool calls: inline status indicator, then result

### 9.4 Interactive Components

- **Permission dialogs**: `[y] Allow once  [n] Deny  [a] Always allow`
- **Progress indicators**: Determinate bars + indeterminate spinners
- **Diff display**: Unified diff with ANSI coloring
- **Multi-select menus**: Arrow keys + space to toggle
- **Context indicator**: `[tokens: 12.4k/128k | cost: $0.23 | model: sonnet-4.5]`

### 9.5 Accessibility

- Screen reader mode: simplified output, no animations, semantic text
- Color modes: auto-detect, truecolor, 256, 16, none
- High contrast: WCAG AAA (7:1 minimum contrast ratio)
- Configurable symbols: Unicode or ASCII-only fallback

### 9.6 IDE Integration (Post-MVP)

Shared `AceClawUiAdapter` interface for all UI targets:
- **VS Code**: TypeScript extension via JSON-RPC over stdio
- **IntelliJ**: Kotlin plugin via Platform SDK
- **Web Dashboard** (v2): Javalin-based monitoring with WebSocket real-time updates

---

## 10. Memory and Self-Learning Strategy

### 10.1 Tiered Memory Model

```
+------------------------------------------------------------------+
|                    Context Window (LLM Request)                   |
|  System Prompt (cached)                                          |
|  Injected Memory (project instructions, auto-memory hits)        |
|  Compacted History (summaries of older turns)                    |
|  Recent Messages (last N turns, verbatim)                        |
|  Tool Definitions (available tools)                              |
+------------------------------------------------------------------+

         ^                    ^                    ^
  Short-term Memory    Medium-term Memory    Long-term Memory
  (conversation)       (session summaries)   (project + auto)
```

### 10.2 Project Memory (ACECLAW.md)

Analogous to CLAUDE.md - hierarchical project configuration:

| Location | Scope | Loading |
|----------|-------|---------|
| `~/.aceclaw/ACECLAW.md` | User-global | Always at launch |
| `.aceclaw/ACECLAW.md` | Project | Always at launch |
| `src/.aceclaw/ACECLAW.md` | Subdirectory | On-demand |

### 10.3 Auto-Memory (Self-Learning)

```java
public enum MemoryCategory {
    MISTAKE,          // "I tried X but Y was correct"
    PATTERN,          // "When user asks X, codebase uses pattern Y"
    PREFERENCE,       // "User prefers approach X over Y"
    CODEBASE_INSIGHT, // "Module X depends on Y, uses pattern Z"
    STRATEGY          // "For task type X, strategy Y works best"
}
```

After each turn, analyze asynchronously on a virtual thread:
- Detect mistakes (tool errors followed by corrections)
- Detect patterns (repeated code structures)
- Track user preferences (corrections, style choices)
- Record codebase insights (dependencies, conventions)

### 10.4 Parallel Memory Retrieval

Java advantage: retrieve from all memory stores simultaneously:

```java
try (var scope = StructuredTaskScope.open()) {
    var projectMemory = scope.fork(() -> projectStore.getInstructions(root));
    var autoMemory = scope.fork(() -> autoStore.retrieve(query, 5));
    var codebaseContext = scope.fork(() -> codebaseIndex.search(query, 10));
    scope.join();
    return new MemoryInjections(projectMemory.get(), autoMemory.get(), codebaseContext.get());
}
```

### 10.5 Context Compression

Three-phase compaction strategy:
1. **Clear verbose tool results** (safe, high token savings)
2. **Summarize old turns** (moderate savings)
3. **Drop irrelevant context** (aggressive, last resort)

Anchor preservation: key architectural decisions, user preferences, and error contexts are never compressed.

### 10.6 Agent Active Memory (P1)

The agent must be able to **actively decide** what to remember during a conversation, not just passively rely on compaction extraction. Three capabilities:

#### 10.6.1 Memory Management Tool

A built-in `memory` tool with three actions that the agent can call during any turn:

| Action | Parameters | Description |
|--------|-----------|-------------|
| `save` | content, category, tags[], global | Persist a new insight to auto-memory |
| `search` | query, category?, limit? | Search memories using hybrid ranking (TF-IDF + recency) |
| `list` | category?, limit? | List recent memories, optionally filtered by category |

The tool is auto-approved (PermissionLevel.READ for search/list, PermissionLevel.WRITE for save). The agent decides autonomously when to call it — e.g., after discovering a codebase pattern, receiving a user correction, or completing a complex debugging session.

#### 10.6.2 System Prompt Memory Guidance

The system prompt must teach the agent **when** and **how** to use the memory tool:

- After receiving a user correction → save as CORRECTION
- After discovering a codebase convention → save as PATTERN
- After a mistake + fix → save as MISTAKE
- When the user states a preference → save as PREFERENCE
- Before starting a complex task → search for relevant memories
- At the start of a session → memories are already injected via the 6-tier hierarchy

#### 10.6.3 Session-End Memory Extraction

When a session is destroyed (explicitly or via daemon shutdown), the system automatically extracts key learnings from the conversation:

1. Scan conversation history for: tool errors followed by corrections, user corrections of agent output, explicit preferences stated
2. Use heuristic extraction (no LLM call) to identify: files modified, commands that failed/succeeded, patterns observed
3. Store extracted insights to AutoMemoryStore with source `session-end:{sessionId}`
4. Append session summary to DailyJournal

This ensures no insight is lost even if the agent did not actively call `memory save` during the session.

### 10.7 Memory Security

- HMAC-SHA256 signed memory files for tamper detection
- Content sanitization for injection patterns
- Memory files read with `TOOL_UNTRUSTED` trust level
- Max file size limits (50KB per file, 500KB total)
- Directory permissions: `700` (owner only)

---

## 11. MVP Scope

### 11.1 In Scope (v1.0)

| Feature | Priority | Rationale |
|---------|----------|-----------|
| Daemon process (lock, PID, signal handling) | P0 | Foundation — everything else runs inside daemon |
| Unix Domain Socket listener | P0 | CLI-to-daemon communication |
| Auto-start daemon from CLI | P0 | Seamless user experience |
| `aceclaw daemon start/stop/status` | P0 | Daemon lifecycle management |
| Interactive CLI (thin client) | P0 | Core user experience |
| Claude API integration | P0 | Primary LLM provider |
| OpenAI API integration | P0 | Market expectation |
| Ollama integration | P0 | Privacy/offline use |
| File read/write/edit tools | P0 | Fundamental operations |
| Bash execution tool | P0 | Run tests, builds, git |
| Glob/Grep search tools | P0 | Code navigation |
| Permission system (4-tier) | P0 | Safety-critical |
| Project config (.aceclaw/) | P0 | Per-project customization |
| Conversation management | P0 | Context window handling |
| Event bus (basic) | P0 | Internal component communication |
| Graceful shutdown | P0 | Data integrity on exit |
| Retry policies / error recovery | P0 | Reliable LLM API interaction |
| MCP client support | P1 | Extensibility |
| Hook system (command type) | P1 | Automation workflows |
| Web fetch/search tools | P1 | Documentation lookup |
| Basic memory system | P1 | Project-level auto-memory |
| GraalVM native image | P1 | Performance differentiator |
| Subagent delegation | P1 | Context isolation |
| Slash commands | P1 | User-invocable shortcuts |
| Multi-session support | P1 | Concurrent CLI + IDE sessions in daemon |
| Session persistence/resume | P1 | Deterministic task resume with scope routing (session > client-instance > workspace) |
| Heartbeat runner (HEARTBEAT.md) | P1 | Proactive periodic agent tasks |
| Cron scheduler (persistent jobs) | P1 | Scheduled tasks, dependency audits |
| BOOT.md execution on daemon start | P1 | Boot-time initialization |
| Health monitoring | P1 | Component liveness tracking |
| Circuit breakers (LLM/MCP) | P1 | Fault tolerance for external services |
| Background memory consolidation | P1 | Idle-time learning and optimization |
| Task Planner (basic, single-branch) | P1 | Autonomous planning for complex tasks |
| Task Planner (full DAG, parallel execution) | P2 | Parallel branch execution + replanning |
| Task Planner (Agent Teams bridge) | P3 | Auto-materialize plans into team TaskStore |
| Swarm explicit trigger (`/swarm`, `/team`) | P3 | User-initiated swarm mode; language-agnostic routing |
| Swarm auto-escalation (Plan → Swarm) | P3 | Safety net for misjudged complexity; SwarmEscalationPolicy |
| DAG parallel execution (Swarm tier only) | P3 | Task-level parallelism via isolated Worker AgentLoops |
| Skills system (basic) | P2 | Model-invoked extensibility |
| Adaptive skills (learning loop, metrics) | P2 | Self-improving skill system |
| Auto-generated skills | P3 | Pattern-based skill proposal |
| Skill refinement engine | P3 | LLM-powered skill improvement |
| Message queue (point-to-point, pub/sub) | P2 | Decoupled inter-agent communication |
| Message queue (request-reply, DLQ, TTL) | P3 | Advanced messaging patterns |
| Token usage tracking | P2 | Cost management |
| WebSocket listener (IDE/remote) | P2 | IDE plugin and remote client support |
| JSON-RPC protocol (full method set) | P2 | Standardized client-daemon protocol |
| `aceclaw daemon install` (launchd/systemd) | P2 | System service registration |
| Codebase indexing (idle-time) | P2 | Incremental project indexing |
| Heartbeat system (teams) | P2 | Agent team liveness |

### 11.2 Out of Scope (v1.0)

| Feature | Rationale |
|---------|----------|
| Multi-agent team orchestration | Phase 4 (Weeks 13-18); infrastructure ready in v1.0-v2.0 |
| Multi-agent swarm intelligence | Phase 5 (Weeks 19-26); depends on Phase 4 Agent Teams |
| Plugin distribution system | Needs stable core API |
| Full web dashboard (project management) | CLI-first; Swarm Dashboard (Phase 5) is monitoring-only |
| IDE plugins | Requires stable core API |
| Enterprise SSO/RBAC | Needs organizational infrastructure |
| MCP server mode | Client-first |
| Full gateway with WebSocket | CLI-first; gateway enables multi-client in v2.0 |

### 11.3 Roadmap

| Phase | Timeline | Focus |
|-------|----------|-------|
| **Phase 1: Core Agent + Daemon** | Weeks 1-4 | **Daemon process (lock, PID, signal, UDS listener, auto-start)**, agent loop, LLM client (Claude), core tools, CLI thin client, basic permissions, event bus, graceful shutdown, retry policies |
| **Phase 2: Intelligence** | Weeks 5-8 | **Multi-session daemon, session persistence/resume, heartbeat runner (HEARTBEAT.md), cron scheduler, BOOT.md, background memory consolidation**, memory/compaction, OS-level sandbox, OpenAI/Ollama, hooks, health monitor, circuit breakers, LLM failover, Task Planner (ComplexityEstimator + basic single-branch plans) |
| **Phase 3: Extensibility** | Weeks 9-12 | **WebSocket listener (IDE), JSON-RPC full method set, codebase indexing**, MCP client, plugin SPI, auto-memory, rich terminal UI, subagents, basic skills system, full event type hierarchy, message queue (point-to-point + pub/sub), adaptive skills (metrics tracking + learning loop), Task Planner (full DAG, parallel execution, replanning, PlanEvent) |
| **Phase 4: Multi-Agent** | Weeks 13-18 | **`aceclaw daemon install` (launchd/systemd), Agent Teams persistence (survive client disconnect), file watchers, remote access (TLS + auth)**, agent teams (sealed TeamMessage, TeamManager, TaskStore, dual-mode transport, in-process/external teammates, plan approval), message queue (request-reply + DLQ + TTL), skill refinement engine, auto-generated skills, GraalVM native builds, Task Planner (Agent Teams bridge, adaptive plan templates) |
| **Phase 5: Swarm Intelligence** | Weeks 19-26 | **Explicit swarm trigger (`/swarm`, `/team` commands + natural language detection), SwarmCommandParser, three-tier routing (ReAct → Plan → Swarm), auto-escalation from Plan to Swarm (SwarmEscalationPolicy)**, Meta-Agent controller, Worker Pool Manager, DAG-only-at-swarm-tier execution, Swarm Dashboard (Javalin + WebSocket + D3.js), Worker Profile evolution, instructor-summarization cost optimization, swarm cost guard, adaptive replanning, goal decomposition engine, swarm state persistence, dashboard controls (pause/stop/approve), multi-model worker assignment (Haiku workers + Sonnet/Opus planner), research-backed scaling rules (14-worker cap for reasoning) |

---

## 12. Success Metrics

### Performance

| Metric | Target |
|--------|--------|
| Daemon boot time (native) | < 150ms |
| CLI-to-daemon connect (warm) | < 10ms |
| CLI cold start (auto-start daemon) | < 2s |
| Daemon idle memory | < 50MB |
| Concurrent sessions supported | > 10 |
| Daemon uptime between restarts | > 7 days |
| Session resume latency | < 50ms |
| Cold startup (native, one-shot) | < 50ms |
| First token latency | < 200ms (after LLM response starts) |
| File search (10K files) | < 500ms |
| Memory (idle) | < 30MB |
| Memory (active) | < 120MB |
| Binary size | < 60MB |

### Quality

| Metric | Target |
|--------|--------|
| Task completion rate | > 85% |
| Tool execution success rate | > 95% |
| Permission accuracy | 100% (no unauthorized operations) |
| Crash rate | < 0.1% |
| Plan generation latency | < 3 seconds (single LLM call) |
| Plan accuracy (no replan needed) | > 70% |
| Plan parallel speedup | > 1.5x vs sequential (2+ branch plans) |
| Auto-memory plan reuse rate | > 30% after 50 sessions |
| User plan approval rate | > 80% |
| Resume binding accuracy (`continue`) | >= 95% (replay set) |
| Cross-workspace auto-resume leakage | 0 |

### Swarm (Phase 5)

| Metric | Target |
|--------|--------|
| Meta-Agent plan generation | < 5 seconds |
| Worker spawn latency | < 50ms per worker (virtual thread) |
| Dashboard render latency | < 100ms (WebSocket push) |
| Token cost reduction (vs naive broadcast) | > 80% (instructor-summarization) |
| Swarm task completion rate | > 80% |
| Adaptive replan success rate | > 60% (within 3 replans) |
| Worker profile accuracy (task routing) | > 70% after 20 swarm runs |
| Dashboard uptime during swarm | 100% |

### User Experience

| Metric | Target |
|--------|--------|
| Time to first value | < 5 minutes (install to first task) |
| Session duration | > 15 minutes avg |
| Weekly return rate | > 60% |

### Competitive Benchmarks

| Benchmark | Claude Code | AceClaw Target |
|-----------|------------|----------------|
| Startup time | ~500-800ms | < 50ms (10x faster) |
| Memory footprint | ~80-120MB idle | < 30MB idle (4x less) |
| Parallel tool execution | Limited (async) | Full virtual thread parallelism |
| Distribution | Node.js + npm | Single binary |
| Type safety | Runtime (JS) | Compile-time (Java) |

---

## 13. Risks and Mitigations

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| **GraalVM native image limitations** (reflection, dynamic class loading) | Medium | High | Early prototyping, tracing agent metadata, JVM fallback mode |
| **Feature parity gap with Claude Code** | High | Medium | Focus on core workflow first, differentiate on performance/enterprise |
| **Structured concurrency API changes** (still preview) | Medium | Medium | Abstract behind internal interfaces, easy to update when stabilized |
| **Self-learning accuracy** | High | Medium | Start with simple heuristics, iterate on quality metrics |
| **Platform-specific sandbox complexity** | Medium | High | macOS: Seatbelt (built-in), Linux: bubblewrap, graceful degradation |
| **Plugin classloader isolation** | Medium-High | Medium | Hybrid approach (JVM subprocess), extensive testing |
| **Adoption challenge** | High | High | Clear performance benchmarks, enterprise features, easy migration |
| **LLM API changes** | Medium | Medium | Abstract provider interface, version pinning, integration tests |
| **Infrastructure complexity** | Medium | Medium | aceclaw-infra uses zero external dependencies (java.base only); progressive rollout across phases |
| **Skill refinement quality** | Medium | Medium | Start with simple heuristics (success rate thresholds); LLM refinement with user approval gate; version rollback on regression |
| **Auto-generated skill noise** | Medium | Low | Require 3+ matching patterns before proposing; user must approve all drafts; clear rejection path |
| **Message queue ordering edge cases** | Low | Medium | FIFO guaranteed per queue; correlation IDs for request-reply; thorough testing with concurrent producers |
| **Circuit breaker tuning** | Medium | Low | Conservative defaults (5 failures / 60s reset); configurable per provider; observability via event bus |
| **Swarm cost explosion** | Medium | High | Cost guard with configurable $/swarm threshold; auto-pause on breach; instructor-summarization reduces baseline cost by ~93% |
| **Worker coordination deadlock** | Low | High | Workers communicate only via shared state (no direct messaging); Meta-Agent detects stalls via progress monitoring; 5-minute timeout per task |
| **Emergent deceptive coordination** | Low | Medium | All worker actions gated by existing permission system; Meta-Agent audits all state changes; replan limit of 3 prevents runaway loops |
| **Dashboard as attack surface** | Low | Medium | Bind to localhost only; optional auth token; no external network access; read-only by default |
| **Meta-Agent single point of failure** | Medium | High | Meta-Agent runs on daemon's main thread; daemon crash recovery preserves swarm state to disk; workers resume from last checkpoint |

---

## Appendix: Reference Documents

All detailed research is available in the `research/` directory:

| Document | Author | Content |
|----------|--------|---------|
| `research/openclaw-architecture.md` | OpenClaw Expert | OpenClaw/Claude Code architecture deep dive, 25 recommendations |
| `research/product-features.md` | Product Owner | Full feature definitions, personas, competitive analysis |
| `research/system-architecture.md` | Architect | 11-module design, core abstractions, concurrency model, feasibility analysis |
| `research/security-architecture.md` | Security Expert | Threat model, permission system, sandbox, audit, 13 sections |
| `research/java-framework-stack.md` | Engineer | Framework evaluation, GraalVM strategy, build configuration |
| `research/frontend-cli-design.md` | Frontend Developer | Terminal UI design, components, IDE integration, accessibility |

---

---

## 14. Research Foundation — Multi-Agent Swarm

The Swarm Architecture design (§4.9.2–4.9.6) is grounded in a systematic review of 7 core academic papers published between 2025-2026. This section maps each research finding to its corresponding AceClaw design decision.

### 14.1 Source Papers

| ID | Paper | Institution | Published | Key Contribution |
|----|-------|-------------|-----------|------------------|
| P1 | Multi-Agent Collaboration Mechanisms Survey | Univ. College Cork | arXiv:2501.06322 | 5-dimensional collaboration framework |
| P2 | SwarmAgentic | LMU Munich | arXiv:2506.15672 | Fully automated agent system generation via PSO |
| P3 | LLM-driven Swarm Intelligence | CY Cergy Paris | arXiv:2503.03800 | LLM replacing hard-coded swarm rules in NetLogo |
| P4 | SwarmSys | HKUST-GZ, NYU | arXiv:2510.10047 | Decentralized pheromone-inspired reasoning (GPT-4o swarm → 70% of GPT-5) |
| P5 | Beyond Frameworks (ACL 2025) | Harbin IT | arXiv:2505.12467 | Fine-grained collaboration experiments: centralized + instructor = 93% cost reduction |
| P6 | LLM-based MAS Survey | Harbin IT | arXiv:2412.17481 | 125-paper application taxonomy |
| P7 | IEEE SMC MAS×LLM | CNR Italy | June 2025 | Architecture fusion of MAS and LLM |

### 14.2 Research → Design Mapping

| Research Finding | Evidence | AceClaw Design Decision |
|------------------|----------|-------------------------|
| **Collaboration > Model Capability** (7/7 consensus) | P4: GPT-4o swarm closed 70% of gap to GPT-5 purely through coordination | Meta-Agent invests in coordination architecture; workers use cheap models (Haiku) |
| **Centralized planning + decentralized execution = optimal hybrid** | P5: centralized + instructor → 93% token reduction; P4: decentralized execution → emergent specialization | Meta-Agent (centralized planning) + Worker Pool (decentralized execution) |
| **Reasoning tasks saturate at 8-14 agents** | P4: SwarmSys ablation — gains plateau at 14 workers | `MAX_REASONING_WORKERS = 14` hard cap |
| **Instructor-summarization is the best cost/quality pattern** | P5: ordered turns + instructor summarization = best token-to-accuracy ratio | Meta-Agent filters and summarizes context per worker (up to 93% token savings) |
| **Adaptive profiles enable specialization** | P4: Worker profiles (role, capability, performance) evolve through task history | `WorkerProfile` with role, capabilities, performance, history fields |
| **Automated agent team design is feasible** | P2: SwarmAgentic generates agents from scratch + self-optimizes | Meta-Agent Team Designer auto-selects worker count, roles, and models |
| **Communication pattern matters more than message content** | P5: simultaneous broadcast = worst; sequential ordered = best quality | Workers communicate only through shared state; no direct worker-worker messaging |
| **Memory must evolve from stateless to adaptive** | P4: stateless → three-layer → adaptive archive (frontier) | Worker Profiles are adaptive archives; Meta-Agent maintains swarm-level memory |

### 14.3 Open Research Questions Tracked

These unresolved questions from the literature will guide iterative improvements:

| Question | Status | Action |
|----------|--------|--------|
| Optimal agent count given budget + task complexity | Unresolved | Meta-Agent uses heuristic; collect telemetry to build empirical model |
| Standardized MAS benchmarks | Immature | Track SWE-bench, GAIA, TravelPlanner for swarm evaluation |
| Safety in emergent coordination | Theoretical | Cost guard + replan limit + human approval gate |
| Token cost prediction models | None exist | Instrument swarm runs; build regression model over time |
| Human-in-the-loop optimal insertion points | Underexplored | Dashboard approval gates at plan creation + high-risk tasks |

### 14.4 Competitive Positioning

| Capability | Claude Code | OpenClaw | CrewAI | LangGraph | AceClaw (Phase 5) |
|-----------|------------|---------|--------|-----------|-------------------|
| Sub-agents | ✅ (basic) | ✅ | ❌ | ✅ | ✅ |
| Multi-agent teams | ✅ (Team Lead) | ❌ | ✅ (static roles) | ✅ (graph) | ✅ (Meta-Agent + dynamic workers) |
| **Explicit swarm trigger** | ❌ | ❌ | ❌ | ❌ | **✅ (`/swarm`, `/team`, natural language)** |
| **Three-tier execution** | ❌ (ReAct only) | ❌ | ❌ | Partial | **✅ (ReAct → Plan → Swarm)** |
| **Auto-escalation** | ❌ | ❌ | ❌ | ❌ | **✅ (Plan fails → Swarm)** |
| Real-time dashboard | ❌ | ❌ | ❌ | ❌ | **✅ (Swarm Dashboard)** |
| Pre-execution plan visibility | ❌ | ❌ | ❌ | Partial | **✅ (full DAG before execution)** |
| Worker profile evolution | ❌ | ❌ | ❌ | ❌ | **✅ (adaptive profiles, P4)** |
| Instructor-summarization | ❌ | ❌ | ❌ | ❌ | **✅ (93% token reduction, P5)** |
| Automated team design | ❌ | ❌ | ❌ | ❌ | **✅ (SwarmAgentic-inspired, P2)** |
| Research-backed scaling rules | ❌ | ❌ | ❌ | ❌ | **✅ (7-paper synthesis)** |

---

*This PRD was compiled from research by the AceClaw PRD Team: Architect, OpenClaw Expert, Product Owner, Security Expert, Java Engineer, and Frontend Developer. Swarm Architecture sections (§4.9.2–4.9.6, §14) are informed by systematic review of 7 academic papers (2025-2026).*
