# AceClaw Agent Teams Architecture

> System Architecture Document — Background Agents, Communication, Orchestration, and Summary Learning
> Author: System Architect | Date: 2026-02-17

---

## Table of Contents

1. [Design Philosophy](#1-design-philosophy)
2. [Sub-Agent System](#2-sub-agent-system)
3. [Agent Teams (Multi-Agent Orchestration)](#3-agent-teams-multi-agent-orchestration)
4. [Inter-Agent Communication](#4-inter-agent-communication)
5. [Main Agent Orchestration](#5-main-agent-orchestration)
6. [Background Execution](#6-background-execution)
7. [Summary Learning](#7-summary-learning)
8. [Module Assignments](#8-module-assignments)
9. [Interface Definitions](#9-interface-definitions)
10. [Data Flow Diagrams](#10-data-flow-diagrams)
11. [Implementation Roadmap](#11-implementation-roadmap)

---

## 1. Design Philosophy

### 1.1 Core Principles

AceClaw implements a **two-tier delegation architecture** inspired by Claude Code's proven patterns, adapted for AceClaw's daemon-first, Java 21, virtual-thread-based runtime. The architecture follows these principles:

| Principle | Description |
|-----------|-------------|
| **Depth-1 delegation** | Sub-agents cannot spawn sub-agents; teams cannot spawn sub-teams. This prevents uncontrolled resource proliferation and keeps the execution model predictable. |
| **File-based coordination** | Teams coordinate via JSON files on disk (task lists, inboxes). This survives daemon restarts, enables debugging via standard tools, and avoids in-memory state loss. |
| **Virtual thread concurrency** | All agent execution uses virtual threads (`Thread.ofVirtual()`), not platform threads. Sub-agents and teammates run concurrently without OS thread exhaustion. |
| **Sealed type safety** | All message types, task statuses, and agent configurations use sealed interfaces with exhaustive pattern matching. |
| **Daemon-native teams** | Unlike Claude Code (which spawns separate Node.js processes or tmux panes), AceClaw runs teammates as in-daemon virtual threads sharing the same JVM. This dramatically reduces resource overhead while maintaining context isolation. |
| **Separation of concerns** | Sub-agents are for focused, result-returning tasks. Agent Teams are for complex, multi-step projects requiring peer discussion and shared work coordination. |

### 1.2 Two-Tier Architecture

```
+------------------------------------------------------------------+
|                    AceClaw Delegation Model                        |
+------------------------------------------------------------------+
|                                                                    |
|  TIER 1: Sub-Agents (Task Tool)                                   |
|  ┌────────────────────────────────────────────────────────┐      |
|  │ - Spawned via TaskTool from main agent loop             │      |
|  │ - Own context window (fresh conversation history)       │      |
|  │ - Report result back to parent as tool_result           │      |
|  │ - Cannot communicate with each other                    │      |
|  │ - Cannot spawn further sub-agents (no-nesting)          │      |
|  │ - Foreground (blocking) or background (concurrent)      │      |
|  │ - Low overhead: ~1 virtual thread per sub-agent         │      |
|  └────────────────────────────────────────────────────────┘      |
|                                                                    |
|  TIER 2: Agent Teams (TeamCreate + TaskList)                      |
|  ┌────────────────────────────────────────────────────────┐      |
|  │ - Created via TeamManager from main agent loop          │      |
|  │ - Each teammate is a full agent session (own AgentLoop) │      |
|  │ - Teammates communicate via SendMessage (direct/bcast)  │      |
|  │ - Shared task list with dependency tracking              │      |
|  │ - Self-coordinating: teammates claim and complete tasks  │      |
|  │ - File-based persistence (survives daemon restarts)     │      |
|  │ - Higher overhead: full LLM calls per teammate          │      |
|  └────────────────────────────────────────────────────────┘      |
|                                                                    |
+------------------------------------------------------------------+
```

### 1.3 When to Use Each Tier

| Criterion | Use Sub-Agent | Use Agent Team |
|-----------|--------------|----------------|
| Task needs isolated context | Yes | Yes (but more expensive) |
| Only the result matters | Yes | No (use sub-agent) |
| Agents need to discuss | No | Yes |
| Work can be decomposed into independent subtasks | Yes (fan-out) | Yes (shared task list) |
| Complex multi-step project with dependencies | No | Yes |
| Cost sensitivity | Lower | Higher (each teammate = full LLM session) |
| Need to resume individual agents | Yes (via agent ID) | Yes (file-based persistence) |

---

## 2. Sub-Agent System

### 2.1 Architecture Overview

```
Parent AgentLoop (StreamingAgentLoop)
  │
  ├── TaskTool.execute(agentType="explore", prompt="Find auth files")
  │     │
  │     ├── AgentTypeRegistry.get("explore")
  │     │     → SubAgentConfig(model=haiku, tools=[read_file,glob,grep], maxTurns=15)
  │     │
  │     ├── SubAgentRunner.run(config, prompt, llmClient)
  │     │     │
  │     │     ├── Create fresh ToolRegistry (filtered, Task excluded)
  │     │     ├── Create fresh StreamingAgentLoop with:
  │     │     │   - Sub-agent's system prompt (from config body)
  │     │     │   - Sub-agent's model (or inherited from parent)
  │     │     │   - Sub-agent's maxTurns
  │     │     │   - Sub-agent's compaction config (95% trigger)
  │     │     ├── Run ReAct loop
  │     │     └── Return Turn result
  │     │
  │     └── Extract text from Turn → ToolResult
  │
  └── Parent receives ToolResult, continues its own loop
```

### 2.2 SubAgentConfig

```java
public record SubAgentConfig(
    String name,               // e.g., "explore", "plan", "general"
    String description,        // when to delegate to this agent
    String systemPrompt,       // markdown body from config file
    String model,              // "inherit", "sonnet", "haiku", "opus"
    List<String> allowedTools, // empty = all tools (minus Task)
    List<String> disallowedTools,
    String permissionMode,     // "default", "plan", "dontAsk", "bypassPermissions"
    int maxTurns,              // max ReAct iterations
    List<String> preloadedSkills,
    List<String> mcpServers,
    String memoryScope         // null, "user", "project", "local"
) {}
```

### 2.3 Built-in Agent Types

| Agent Type | Model | Tools | Max Turns | Purpose |
|------------|-------|-------|-----------|---------|
| `explore` | haiku | read_file, glob, grep, list_directory | 15 | Fast read-only codebase exploration |
| `plan` | inherit | read_file, glob, grep, list_directory | 20 | Research for plan mode |
| `general` | inherit | All (minus Task) | 25 | Complex multi-step tasks |
| `bash` | inherit | bash | 10 | Isolated shell command execution |

### 2.4 Custom Agent Definition Format

Custom agents are defined as `.md` files with YAML frontmatter:

**Location priority (higher overrides lower):**
1. `{project}/.aceclaw/agents/*.md` (project-specific)
2. `~/.aceclaw/agents/*.md` (personal/global)

**Format:**

```yaml
---
name: code-reviewer
description: Reviews code for bugs, security issues, and best practices.
  Use proactively after code changes.
tools: read_file, glob, grep, bash
disallowedTools: write_file, edit_file
model: sonnet
permissionMode: default
maxTurns: 20
memory: user
skills:
  - security-audit
---

You are a senior code reviewer. When invoked:

1. Read the changed files
2. Analyze for bugs, security vulnerabilities, and code quality
3. Report findings with file:line references
4. Rate each finding by severity (Critical/High/Medium/Low)

Only report findings with confidence >= 80%.
```

### 2.5 AgentTypeRegistry

```java
public final class AgentTypeRegistry {

    private final Map<String, SubAgentConfig> builtIn;    // explore, plan, general, bash
    private final Map<String, SubAgentConfig> custom;     // from .aceclaw/agents/*.md

    // Built-in agents initialized at construction
    public AgentTypeRegistry() { ... }

    // Load custom agents from project and user directories
    public void loadCustomAgents(Path projectDir) { ... }

    // Lookup by name (custom overrides built-in)
    public Optional<SubAgentConfig> get(String agentType) { ... }

    // List all available agent types
    public List<SubAgentConfig> listAll() { ... }
}
```

### 2.6 No-Nesting Rule Enforcement

Sub-agents cannot spawn further sub-agents. This is enforced at two levels:

1. **ToolRegistry filtering**: `SubAgentRunner` creates a filtered `ToolRegistry` that excludes the `TaskTool` and `TeamCreateTool`.
2. **Agent system prompt**: Sub-agent system prompts do not include instructions about delegation.

```java
// In SubAgentRunner
private ToolRegistry createFilteredRegistry(SubAgentConfig config, ToolRegistry parentRegistry) {
    var filtered = new ToolRegistry();
    for (var tool : parentRegistry.all()) {
        // Enforce no-nesting
        if (tool.name().equals("task") || tool.name().equals("team_create")) {
            continue;
        }
        // Apply allowlist/denylist
        if (!config.allowedTools().isEmpty() && !config.allowedTools().contains(tool.name())) {
            continue;
        }
        if (config.disallowedTools().contains(tool.name())) {
            continue;
        }
        filtered.register(tool);
    }
    return filtered;
}
```

### 2.7 Sub-Agent Transcript Persistence

Each sub-agent's conversation is persisted as a JSONL transcript for resumption:

```
~/.aceclaw/sessions/{parentSessionId}/subagents/agent-{agentId}.jsonl
```

**JSONL format:**
```json
{"type":"user","content":"Find auth patterns in src/","timestamp":"2026-02-17T10:00:00Z","uuid":"abc-123"}
{"type":"assistant","content":"I'll search for authentication-related files...","timestamp":"2026-02-17T10:00:01Z","uuid":"def-456"}
{"type":"tool_use","tool":"grep","input":{"pattern":"auth","path":"src/"},"timestamp":"2026-02-17T10:00:02Z","uuid":"ghi-789"}
{"type":"tool_result","toolUseId":"ghi-789","output":"Found 12 matches...","isError":false,"timestamp":"2026-02-17T10:00:03Z"}
```

**Resumption:** Pass the agent ID to `TaskTool` with `resume=true`. The `SubAgentRunner` reloads the transcript and creates a new `StreamingAgentLoop` with the restored conversation history.

### 2.8 Sub-Agent Context Sharing

**What sub-agents receive:**
- Their own system prompt (from agent config markdown body)
- Working directory, platform, date/time context
- ACECLAW.md files (project instructions, loaded via MemoryTierLoader)
- Preloaded skill content (if specified in config)
- MCP server connections (if specified)
- Persistent memory (if `memoryScope` is set)

**What sub-agents do NOT receive:**
- Parent conversation history
- Full AceClaw system prompt
- Other sub-agents' results or state
- Access to the Task tool (no-nesting)

---

## 3. Agent Teams (Multi-Agent Orchestration)

### 3.1 Architecture Overview

```
+-------------------------------------------------------------------+
|                        ACECLAW AGENT TEAM                          |
|                                                                    |
|  Daemon JVM (single process, virtual threads)                     |
|                                                                    |
|  +------------------+     +------------------+                    |
|  |   Team Lead      |     |   Task List      |                    |
|  |   (main session) |<--->|   (JSON files)   |                    |
|  +--------+---------+     +--------+---------+                    |
|           |                        |                               |
|    +------+------+          +------+------+                       |
|    |             |          |             |                        |
|    v             v          v             v                        |
| +--------+  +--------+  +--------+  +--------+                   |
| |Teammate|  |Teammate|  |Teammate|  |Teammate|                   |
| |   A    |  |   B    |  |   C    |  |   D    |                   |
| |(vthread)|  |(vthread)|  |(vthread)|  |(vthread)|                |
| +---+----+  +---+----+  +---+----+  +---+----+                   |
|     |            |            |            |                       |
|     |  Each teammate has:                  |                       |
|     |  - Own AgentSession (conversation)   |                       |
|     |  - Own StreamingAgentLoop            |                       |
|     |  - Own ToolRegistry                  |                       |
|     |  - Inbox (file-based message queue)  |                       |
|     |                                      |                       |
|     +------+-----+------+----+------+-----+                      |
|            |             |          |                              |
|      +-----v------+ +---v---+ +----v-----+                       |
|      | Inbox A    | |Inbox B| | Inbox C  |    ...                 |
|      | (.json)    | |(.json)| | (.json)  |                        |
|      +------------+ +-------+ +----------+                       |
+-------------------------------------------------------------------+

File System Layout:
~/.aceclaw/teams/{team-name}/
  config.json                    # Team metadata + members array
  inboxes/{agent-name}.json      # Per-agent message queue

~/.aceclaw/tasks/{team-name}/
  1.json, 2.json, 3.json...     # Shared task files
```

### 3.2 Team Lifecycle

```
SETUP PHASE (initiated by main agent or user):
  1. TeamManager.create(teamName, description)
     → Creates ~/.aceclaw/teams/{team-name}/config.json
     → Creates ~/.aceclaw/tasks/{team-name}/

  2. TaskManager.create(tasks...) × N
     → Creates task JSON files with dependencies

  3. TeamManager.spawnTeammate(config) × N
     → Creates AgentSession per teammate
     → Starts virtual thread per teammate
     → Each teammate runs its own StreamingAgentLoop
     → Registers in config.json members array

EXECUTION PHASE (self-coordinating):
  Each teammate independently:
    a. TaskManager.list() → find unclaimed, unblocked tasks
    b. TaskManager.claim(taskId) → set owner, status=in_progress
    c. [Execute work using tools]
    d. TaskManager.complete(taskId) → status=completed
    e. MessageRouter.send(leader, "Task X complete: findings...")
    f. Auto-unblock dependent tasks
    g. TaskManager.list() → find next task
    h. If no tasks available → go idle (wait for messages)

TEARDOWN PHASE (initiated by lead):
  1. MessageRouter.send(teammate, shutdownRequest) × N
  2. Teammates approve → virtual threads complete
  3. TeamManager.delete(teamName) → cleanup files
```

### 3.3 Team Configuration

**Team config file** (`~/.aceclaw/teams/{team-name}/config.json`):

```json
{
  "name": "feature-auth",
  "description": "Implement authentication module",
  "leadSessionId": "session-abc-123",
  "createdAt": "2026-02-17T10:00:00Z",
  "members": [
    {
      "name": "researcher",
      "agentType": "explore",
      "model": "haiku",
      "sessionId": "session-def-456",
      "status": "idle",
      "joinedAt": "2026-02-17T10:00:01Z"
    },
    {
      "name": "implementer",
      "agentType": "general",
      "model": "inherit",
      "sessionId": "session-ghi-789",
      "status": "working",
      "joinedAt": "2026-02-17T10:00:02Z"
    }
  ]
}
```

### 3.4 Task Data Structure

**Task file** (`~/.aceclaw/tasks/{team-name}/{id}.json`):

```json
{
  "id": "1",
  "subject": "Analyze existing auth patterns",
  "description": "Search src/ for existing authentication and authorization patterns. Report file paths, frameworks used, and current approach.",
  "status": "completed",
  "owner": "researcher",
  "activeForm": "Analyzing auth patterns",
  "blockedBy": [],
  "blocks": ["3", "4"],
  "metadata": {},
  "createdAt": "2026-02-17T10:00:00Z",
  "updatedAt": "2026-02-17T10:05:00Z"
}
```

**Task status lifecycle:**

```
  pending ──→ in_progress ──→ completed
     │                            │
     └──→ deleted ←──────────────┘
                    (cleanup only)
```

**Task claiming rules:**
- Only tasks with `status=pending`, `owner=null`, and empty `blockedBy` can be claimed
- `FileLock` prevents race conditions when multiple teammates claim simultaneously
- When a task completes, it is removed from all dependents' `blockedBy` arrays
- Priority: lowest ID first (earlier tasks often set up context for later ones)

### 3.5 In-Daemon Teammate Execution

Unlike Claude Code (separate OS processes), AceClaw runs teammates as virtual threads within the daemon JVM:

```java
public final class TeammateRunner {

    private final LlmClient llmClient;
    private final ToolRegistry baseToolRegistry;
    private final TeamConfig teamConfig;
    private final MessageRouter messageRouter;
    private final TaskManager taskManager;

    /**
     * Starts a teammate as a virtual thread within the daemon.
     * Returns a handle for lifecycle management.
     */
    public TeammateHandle start(TeammateDef definition) {
        var session = sessionManager.createSession(workingDir);
        var loop = createTeammateLoop(definition, session);

        var thread = Thread.ofVirtual()
            .name("teammate-" + definition.name())
            .start(() -> runTeammateLoop(definition, session, loop));

        return new TeammateHandle(definition.name(), session.id(), thread);
    }

    private void runTeammateLoop(TeammateDef def, AgentSession session,
                                  StreamingAgentLoop loop) {
        // Teammate main loop: process messages → work on tasks → go idle → repeat
        while (!Thread.currentThread().isInterrupted()) {
            // 1. Check inbox for new messages
            var messages = messageRouter.receive(def.name());

            // 2. Process shutdown requests
            if (containsShutdown(messages)) {
                handleShutdownRequest(messages);
                break;
            }

            // 3. Find next task or process messages
            var nextPrompt = buildTeammatePrompt(messages, taskManager);

            if (nextPrompt == null) {
                // No work available — go idle
                messageRouter.sendIdleNotification(def.name());
                messageRouter.waitForMessage(def.name()); // blocks virtual thread
                continue;
            }

            // 4. Run one turn of the agent loop
            var turn = loop.runTurn(nextPrompt, toMessages(session.messages()), handler);

            // 5. Update session history
            updateSession(session, turn);

            // 6. Send idle notification (turn complete)
            messageRouter.sendIdleNotification(def.name());
        }
    }
}
```

**Key advantages of in-daemon execution:**
- No process spawn overhead (virtual threads start in microseconds)
- Shared LLM client connection pool
- Direct access to ToolRegistry, PermissionManager, AutoMemoryStore
- Lower memory footprint (~1MB per virtual thread vs ~50MB per process)
- No IPC serialization cost for tool results

**Tradeoff:**
- Teammates share daemon's JVM — a crash affects all
- No visual isolation (no separate terminal panes)
- Must be careful about thread safety in shared state

---

## 4. Inter-Agent Communication

### 4.1 Message Types

```java
public sealed interface TeamMessage permits
    TeamMessage.DirectMessage,
    TeamMessage.Broadcast,
    TeamMessage.ShutdownRequest,
    TeamMessage.ShutdownResponse,
    TeamMessage.IdleNotification,
    TeamMessage.TaskCompletedNotification {

    Instant timestamp();
    String sender();

    record DirectMessage(
        String sender,
        String recipient,
        String content,
        String summary,    // 5-10 word preview
        Instant timestamp
    ) implements TeamMessage {}

    record Broadcast(
        String sender,
        String content,
        String summary,
        Instant timestamp
    ) implements TeamMessage {}

    record ShutdownRequest(
        String sender,
        String recipient,
        String requestId,
        String reason,
        Instant timestamp
    ) implements TeamMessage {}

    record ShutdownResponse(
        String sender,
        String requestId,
        boolean approved,
        String reason,    // if rejected
        Instant timestamp
    ) implements TeamMessage {}

    record IdleNotification(
        String sender,
        String lastAction,    // brief summary of what was done
        Instant timestamp
    ) implements TeamMessage {}

    record TaskCompletedNotification(
        String sender,
        String taskId,
        String taskSubject,
        Instant timestamp
    ) implements TeamMessage {}
}
```

### 4.2 MessageRouter

The MessageRouter handles all inter-agent communication using file-based inboxes with in-memory notification via `BlockingQueue`:

```java
public final class MessageRouter {

    private final Path teamDir;   // ~/.aceclaw/teams/{team-name}/
    private final ObjectMapper mapper;

    // In-memory notification channels (one per teammate)
    private final ConcurrentHashMap<String, LinkedBlockingQueue<TeamMessage>> channels;

    /**
     * Sends a direct message to a specific teammate.
     * Writes to file inbox for persistence + notifies in-memory channel.
     */
    public void send(String recipient, TeamMessage message) {
        // 1. Persist to file inbox for crash recovery
        appendToInbox(recipient, message);

        // 2. Notify in-memory channel to wake the teammate
        var channel = channels.get(recipient);
        if (channel != null) {
            channel.offer(message);
        }
    }

    /**
     * Broadcasts a message to all teammates.
     * Iterates all registered teammates and sends individually.
     */
    public void broadcast(TeamMessage.Broadcast message) {
        for (var name : channels.keySet()) {
            if (!name.equals(message.sender())) {
                send(name, message);
            }
        }
    }

    /**
     * Receives all pending messages for a teammate.
     * Drains the in-memory channel (non-blocking).
     */
    public List<TeamMessage> receive(String agentName) {
        var channel = channels.get(agentName);
        if (channel == null) return List.of();
        var messages = new ArrayList<TeamMessage>();
        channel.drainTo(messages);
        return messages;
    }

    /**
     * Blocks until a message arrives for this teammate.
     * Uses virtual thread — blocks without consuming OS thread.
     */
    public TeamMessage waitForMessage(String agentName) throws InterruptedException {
        var channel = channels.get(agentName);
        return channel.take();  // blocks virtual thread
    }

    /**
     * Writes a message to the file-based inbox for persistence.
     * Uses FileLock to prevent concurrent writes.
     */
    private void appendToInbox(String agentName, TeamMessage message) {
        var inboxPath = teamDir.resolve("inboxes").resolve(agentName + ".json");
        // Append JSON line with FileLock
        ...
    }
}
```

### 4.3 Message Delivery Model

```
Teammate A                    MessageRouter                     Teammate B
    |                              |                                |
    |-- send(B, "Found 3 bugs")-->|                                |
    |                              |                                |
    |                              |-- appendToInbox(B, msg) ------>|
    |                              |   (file persistence)           |
    |                              |                                |
    |                              |-- channel.offer(msg) --------->|
    |                              |   (in-memory notification)     |
    |                              |                                |
    |                              |                     [B wakes up]
    |                              |                     [processes msg]
    |                              |                                |
    |                              |<-- send(A, "Got it, fixing")----|
    |                              |                                |
    |<-- appendToInbox(A, msg) ---|                                |
    |    channel.offer(msg)        |                                |
    |                              |                                |
    [A processes response]         |                                |
```

### 4.4 Idle State Management

Teammates go idle after every turn — this is normal, not an error:

1. Teammate completes a turn (task work, message processing)
2. `IdleNotification` auto-sent to the team lead
3. Teammate calls `messageRouter.waitForMessage()` — blocks the virtual thread
4. When a new message arrives (task assignment, direct message), the teammate wakes
5. Heartbeat timeout: if a teammate is idle for >5 minutes with no pending tasks, the lead may shut it down

```
Timeline:

  A: [working on task 1] → idle → [wake: "task 2 assigned"] → [working on task 2] → idle
  B: [working on task 3] → idle → [wake: message from A] → [respond] → idle
  Lead: [monitors idle/active states, assigns tasks, coordinates]
```

### 4.5 Shutdown Protocol

```
Lead                          Teammate
  |                              |
  |--- shutdownRequest -------->|
  |    (requestId="sr-123")      |
  |                              |
  |                   [Teammate checks:]
  |                   [Am I in the middle of work?]
  |                              |
  |                     ┌────────┴────────┐
  |                     |                  |
  |                   [No]               [Yes]
  |                     |                  |
  |<-- approved ------- |                  |
  |    (requestId=sr-123)                  |
  |                     |                  |
  |    [Thread exits]   |     [Finish current turn]
  |                     |                  |
  |                     |     [Then send approved]
  |                     |                  |
  |<-- approved ----------------------- --|
```

---

## 5. Main Agent Orchestration

### 5.1 Team Leader Pattern

The main agent session acts as team leader. It has exclusive access to team management tools:

| Tool | Purpose |
|------|---------|
| `team_create` | Create a new team with description |
| `team_task_create` | Add tasks to the shared task list |
| `team_task_list` | View all tasks and their status |
| `team_task_update` | Update task status, owner, dependencies |
| `team_spawn` | Spawn a new teammate (virtual thread) |
| `team_send_message` | Send message to a teammate |
| `team_broadcast` | Send message to all teammates |
| `team_shutdown` | Request teammate shutdown |
| `team_delete` | Delete team and clean up files |

### 5.2 Task Decomposition

The team leader decomposes complex requests into a task graph:

```
User Request: "Add JWT authentication to the API"
                    │
                    ▼
           Task Decomposition
                    │
    ┌───────┬───────┼───────┬───────┐
    │       │       │       │       │
    ▼       ▼       ▼       ▼       ▼
  Task 1  Task 2  Task 3  Task 4  Task 5
  Research Analyze Design  Implement Test
  JWT libs existing write   auth     auth
           auth    JWT      module   flows
           code    flow
    │       │       │       │       │
    └───┬───┘  blocks ──────┤       │
        │              blocks ──────┤
        │                     blocks┤
        │                           │
        └─ unblocked ──────────────┘
```

Task dependencies are expressed via `blockedBy` arrays:

```json
[
  {"id": "1", "subject": "Research JWT libraries", "blockedBy": []},
  {"id": "2", "subject": "Analyze existing auth code", "blockedBy": []},
  {"id": "3", "subject": "Design JWT auth flow", "blockedBy": ["1", "2"]},
  {"id": "4", "subject": "Implement auth module", "blockedBy": ["3"]},
  {"id": "5", "subject": "Write auth integration tests", "blockedBy": ["4"]}
]
```

### 5.3 Team Monitoring

The team leader monitors team progress via:

1. **Task list polling**: Periodically check `TaskManager.list()` for completed/blocked tasks
2. **Message inbox**: Process teammate messages (findings, questions, blockers)
3. **Idle notifications**: Track which teammates are idle vs working
4. **Auto-reassignment**: If a teammate is stuck (idle with in_progress task for >N minutes), reassign the task

### 5.4 Delegate Mode

In delegate mode, the team leader restricts itself to coordination-only:

**Allowed tools in delegate mode:**
- `team_create`, `team_task_*`, `team_spawn`, `team_send_message`, `team_broadcast`, `team_shutdown`, `team_delete`

**Disallowed tools in delegate mode:**
- `read_file`, `write_file`, `edit_file`, `bash`, `glob`, `grep` (all direct work tools)

This forces the leader to decompose work and delegate, never doing implementation directly.

```java
public enum AgentMode {
    NORMAL,       // Full tool access
    DELEGATE,     // Coordination tools only
    PLAN          // Read-only tools only
}
```

### 5.5 Plan Approval Flow

When a teammate operates in plan mode, it produces a plan and requests approval from the leader:

```
Teammate (plan mode)              Leader
    |                                |
    | [researches codebase]          |
    | [writes plan]                  |
    |                                |
    |--- planApprovalRequest ------->|
    |    (planContent="...")          |
    |                                |
    |         [Leader reviews plan]  |
    |                                |
    |<-- planApprovalResponse -------|
    |    (approved=true)             |
    |                                |
    | [exits plan mode]              |
    | [implements plan]              |
```

This is implemented as a special message type in the TeamMessage hierarchy. The teammate blocks (virtual thread wait) until the leader responds.

---

## 6. Background Execution

### 6.1 Foreground vs Background Sub-Agents

| Aspect | Foreground (blocking) | Background (concurrent) |
|--------|----------------------|------------------------|
| Main conversation | Blocked | Continues working |
| Permission prompts | Pass through to CLI | Pre-approved before launch |
| Output | Inline (returned as tool_result) | Via TaskOutputTool |
| MCP tools | Available | Not available |
| Context | Own window | Own window |
| Resumption | Via agent ID | Via agent ID |

### 6.2 Background Sub-Agent Flow

```
Main Agent Loop
  │
  ├── 1. Pre-approve permissions
  │    PermissionManager.preApprove(["edit_file", "bash"])
  │
  ├── 2. TaskTool.execute(agentType, prompt, background=true)
  │    │
  │    ├── SubAgentRunner.startBackground(config, prompt)
  │    │   → Starts virtual thread
  │    │   → Returns agentId immediately
  │    │   → Sub-agent runs with pre-approved perms
  │    │   → Auto-deny anything not pre-approved
  │    │
  │    └── Returns ToolResult("Background agent started: agent-xyz")
  │
  ├── 3. [Main agent continues other work]
  │
  ├── 4. TaskOutputTool.execute(agentId="agent-xyz")
  │    │
  │    └── SubAgentRunner.getResult("agent-xyz")
  │        → Returns completed result or "still running"
  │
  └── 5. [Process background agent result]
```

### 6.3 Background Agent Registry

```java
public final class BackgroundAgentRegistry {

    private final ConcurrentHashMap<String, BackgroundAgent> agents = new ConcurrentHashMap<>();

    public record BackgroundAgent(
        String agentId,
        String agentType,
        Thread thread,          // virtual thread
        AgentSession session,
        Instant startedAt,
        volatile AgentStatus status,
        volatile String result  // final text output when complete
    ) {}

    public enum AgentStatus {
        RUNNING, COMPLETED, FAILED
    }

    public String start(SubAgentConfig config, String prompt, ...) {
        var agentId = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        var session = sessionManager.createSession(workingDir);

        var thread = Thread.ofVirtual()
            .name("bg-agent-" + agentId)
            .start(() -> {
                try {
                    var turn = loop.runTurn(prompt, List.of(), handler);
                    agent.result = turn.text();
                    agent.status = AgentStatus.COMPLETED;
                } catch (Exception e) {
                    agent.result = "Error: " + e.getMessage();
                    agent.status = AgentStatus.FAILED;
                }
            });

        var agent = new BackgroundAgent(agentId, config.name(), thread, session,
            Instant.now(), AgentStatus.RUNNING, null);
        agents.put(agentId, agent);
        return agentId;
    }

    public Optional<BackgroundAgent> get(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }
}
```

---

## 7. Summary Learning

### 7.1 Post-Task Knowledge Extraction

When a team completes its work, the team leader extracts knowledge from the collaborative session and persists it to auto-memory. This is the "learning from teamwork" mechanism.

```
+------------------------------------------------------------------+
|                    SUMMARY LEARNING PIPELINE                       |
+------------------------------------------------------------------+
|                                                                    |
|  Team Completion                                                   |
|  ┌──────────────────────────────────────────────────────┐        |
|  │ All tasks completed (or team shutting down)           │        |
|  │     │                                                 │        |
|  │     ▼                                                 │        |
|  │ 1. Collect all teammate final messages                │        |
|  │     │                                                 │        |
|  │     ▼                                                 │        |
|  │ 2. Collect all completed task descriptions + results  │        |
|  │     │                                                 │        |
|  │     ▼                                                 │        |
|  │ 3. TeamSummaryExtractor.extract(tasks, messages)      │        |
|  │     │                                                 │        |
|  │     ├── Error patterns encountered by teammates       │        |
|  │     ├── Successful strategies used                    │        |
|  │     ├── Codebase insights discovered                  │        |
|  │     ├── Tool usage patterns (which tools for which)   │        |
|  │     └── Collaboration patterns (what worked)          │        |
|  │     │                                                 │        |
|  │     ▼                                                 │        |
|  │ 4. Persist to AutoMemoryStore                         │        |
|  │     ├── Error patterns → RECOVERY_STRATEGY            │        |
|  │     ├── Success patterns → SUCCESSFUL_STRATEGY        │        |
|  │     ├── Codebase insights → PROJECT_INSIGHT           │        |
|  │     ├── Tool patterns → TOOL_USAGE_PATTERN            │        |
|  │     └── Team patterns → PATTERN                       │        |
|  └──────────────────────────────────────────────────────┘        |
|                                                                    |
+------------------------------------------------------------------+
```

### 7.2 TeamSummaryExtractor

```java
public final class TeamSummaryExtractor {

    /**
     * Extracts learnings from a completed team session.
     *
     * @param tasks     all tasks from the team's task list
     * @param messages  all messages exchanged between teammates
     * @return list of insights to persist to memory
     */
    public List<TeamInsight> extract(List<AgentTask> tasks,
                                      List<TeamMessage> messages) {
        var insights = new ArrayList<TeamInsight>();

        // 1. Error patterns from task descriptions and messages
        insights.addAll(extractErrorPatterns(tasks, messages));

        // 2. Successful strategies from completed tasks
        insights.addAll(extractSuccessPatterns(tasks));

        // 3. Codebase insights from research tasks
        insights.addAll(extractCodebaseInsights(tasks, messages));

        // 4. Effectiveness metrics
        insights.add(extractEffectivenessMetrics(tasks));

        return insights;
    }
}

public sealed interface TeamInsight permits
    TeamInsight.ErrorPattern,
    TeamInsight.SuccessPattern,
    TeamInsight.CodebaseInsight,
    TeamInsight.EffectivenessMetrics {

    String summary();
    List<String> tags();
    MemoryEntry.Category targetCategory();

    record ErrorPattern(
        String errorType,
        String resolution,
        String teammateName,
        String summary,
        List<String> tags
    ) implements TeamInsight {
        public MemoryEntry.Category targetCategory() {
            return MemoryEntry.Category.RECOVERY_STRATEGY;
        }
    }

    record SuccessPattern(
        String taskType,
        String approach,
        int taskCount,
        String summary,
        List<String> tags
    ) implements TeamInsight {
        public MemoryEntry.Category targetCategory() {
            return MemoryEntry.Category.SUCCESSFUL_STRATEGY;
        }
    }

    record CodebaseInsight(
        String insight,
        String discoveredBy,
        String summary,
        List<String> tags
    ) implements TeamInsight {
        public MemoryEntry.Category targetCategory() {
            return MemoryEntry.Category.PROJECT_INSIGHT;
        }
    }

    record EffectivenessMetrics(
        int totalTasks,
        int completedTasks,
        int failedTasks,
        Duration totalDuration,
        int totalTeammates,
        double avgTasksPerTeammate,
        String summary,
        List<String> tags
    ) implements TeamInsight {
        public MemoryEntry.Category targetCategory() {
            return MemoryEntry.Category.PATTERN;
        }
    }
}
```

### 7.3 Effectiveness Metrics

The system tracks team effectiveness for future team composition decisions:

```java
public record TeamEffectivenessReport(
    String teamName,
    int totalTasks,
    int completedTasks,
    int failedTasks,
    Duration totalDuration,
    int totalTeammates,
    Map<String, TeammateStats> teammateStats,
    List<String> blockerChains,         // task dependency chains that caused delays
    List<String> communicationBottlenecks // teammates that received many messages
) {}

public record TeammateStats(
    String name,
    String agentType,
    int tasksCompleted,
    int messagesSent,
    int messagesReceived,
    Duration totalActiveTime,
    Duration totalIdleTime
) {}
```

These metrics are persisted to auto-memory and used by the agent to:
1. Choose optimal team sizes for similar future tasks
2. Select appropriate agent types for different work
3. Identify and avoid dependency bottlenecks
4. Improve task decomposition strategies

### 7.4 Cross-Session Learning from Teams

When the agent encounters a similar task in a future session, it can recall:

```
User: "Add OAuth authentication to the API"

Agent (consulting auto-memory):
  Found relevant team experience:
  - Previous team "feature-auth" completed JWT auth in 3 tasks
  - Researcher + Implementer pattern was effective
  - Bottleneck: design task blocked implementation
  - Lesson: run research and design in parallel when possible

  Applying learnings to new team composition...
```

---

## 8. Module Assignments

### 8.1 Module Map

```
aceclaw-core (ENHANCED)
  ├── agent/
  │   ├── Tool (existing, unchanged)
  │   ├── ToolRegistry (existing, unchanged)
  │   ├── StreamingAgentLoop (existing, unchanged)
  │   └── AgentMode (NEW: NORMAL, DELEGATE, PLAN)
  └── llm/ (existing, unchanged)

aceclaw-tools (ENHANCED)
  ├── [existing 14 tools, unchanged]
  ├── TaskTool (NEW: spawns sub-agents)
  ├── TaskOutputTool (NEW: retrieves background task results)
  ├── TeamCreateTool (NEW: creates agent teams)
  ├── TeamTaskTool (NEW: manages team task list)
  ├── TeamSpawnTool (NEW: spawns teammates)
  ├── TeamMessageTool (NEW: sends messages to teammates)
  └── TeamDeleteTool (NEW: deletes team and cleans up)

aceclaw-daemon (ENHANCED)
  ├── [existing classes, unchanged]
  ├── SubAgentRunner (NEW: creates and runs sub-agent loops)
  ├── SubAgentConfig (NEW: record for agent type configuration)
  ├── AgentTypeRegistry (NEW: built-in + custom agent configs)
  ├── BackgroundAgentRegistry (NEW: tracks background sub-agents)
  ├── SubAgentTranscriptStore (NEW: JSONL persistence + resume)
  ├── TeamManager (NEW: team lifecycle management)
  ├── TeamConfig (NEW: team configuration record)
  ├── TeammateRunner (NEW: runs teammate loops on virtual threads)
  ├── TeammateHandle (NEW: handle for teammate lifecycle)
  ├── TeammateDef (NEW: teammate definition record)
  ├── TaskManager (NEW: shared task list management)
  ├── AgentTask (NEW: task record with dependencies)
  ├── TaskStatus (NEW: PENDING, IN_PROGRESS, COMPLETED, DELETED)
  ├── MessageRouter (NEW: inter-agent messaging)
  ├── TeamMessage (NEW: sealed interface for message types)
  ├── TeamSummaryExtractor (NEW: post-team knowledge extraction)
  ├── TeamInsight (NEW: sealed interface for team learnings)
  └── TeamEffectivenessReport (NEW: metrics record)

aceclaw-security (ENHANCED)
  ├── [existing classes, unchanged]
  └── PermissionPreApproval (NEW: pre-approved permissions for background agents)
```

### 8.2 New Class Count by Module

| Module | Existing Classes | New Classes | Total |
|--------|-----------------|-------------|-------|
| aceclaw-core | 10 | 1 | 11 |
| aceclaw-tools | 14 | 7 | 21 |
| aceclaw-daemon | 16 | 19 | 35 |
| aceclaw-security | 7 | 1 | 8 |
| **Total** | **47** | **28** | **75** |

### 8.3 Combined with Self-Learning Architecture

When combined with the self-learning architecture (Task #3), the total new class count:

| Module | Task #3 (Self-Learning) | Task #4 (Agent Teams) | Combined New |
|--------|------------------------|-----------------------|--------------|
| aceclaw-memory | 5 | 0 | 5 |
| aceclaw-core | 2 | 1 | 3 |
| aceclaw-tools | 3 | 7 | 10 |
| aceclaw-mcp | 1 | 0 | 1 |
| aceclaw-daemon | 6 | 19 | 25 |
| aceclaw-security | 1 | 1 | 2 |
| **Total** | **18** | **28** | **46** |

---

## 9. Interface Definitions

### 9.1 Sub-Agent Interfaces

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
    List<String> mcpServers,
    String memoryScope             // null, "user", "project", "local"
) {}

public final class SubAgentRunner {

    /**
     * Runs a sub-agent synchronously (foreground, blocking).
     * Returns the final text result.
     */
    public Turn runForeground(SubAgentConfig config, String prompt,
                               LlmClient llmClient, StreamEventHandler handler) { ... }

    /**
     * Starts a sub-agent asynchronously (background).
     * Returns the agent ID immediately.
     */
    public String startBackground(SubAgentConfig config, String prompt,
                                   LlmClient llmClient) { ... }

    /**
     * Resumes a previously run sub-agent by loading its transcript.
     */
    public Turn resume(String agentId, String additionalPrompt,
                        LlmClient llmClient, StreamEventHandler handler) { ... }
}

public record SubAgentTranscriptStore(Path baseDir) {

    /**
     * Saves a turn's messages to the agent's JSONL transcript file.
     */
    public void append(String parentSessionId, String agentId, Turn turn) { ... }

    /**
     * Loads the full conversation history from a transcript file.
     */
    public List<Message> load(String parentSessionId, String agentId) { ... }
}
```

### 9.2 Team Management Interfaces

```java
// --- aceclaw-daemon ---

public record TeamConfig(
    String name,
    String description,
    String leadSessionId,
    Instant createdAt,
    List<TeamMember> members
) {
    public record TeamMember(
        String name,
        String agentType,
        String model,
        String sessionId,
        String status,      // "idle", "working", "shutdown"
        Instant joinedAt
    ) {}
}

public final class TeamManager {

    /**
     * Creates a new team with the given name and description.
     * Sets up the file system layout (config.json, tasks/, inboxes/).
     */
    public TeamConfig create(String teamName, String description,
                              String leadSessionId) { ... }

    /**
     * Spawns a new teammate as a virtual thread.
     * Registers in config.json and starts the teammate loop.
     */
    public TeammateHandle spawnTeammate(String teamName, TeammateDef definition) { ... }

    /**
     * Requests a teammate to shut down gracefully.
     */
    public void requestShutdown(String teamName, String teammateName, String reason) { ... }

    /**
     * Deletes a team and all associated files.
     * Fails if any teammates are still running.
     */
    public void delete(String teamName) { ... }

    /**
     * Gets the current team configuration.
     */
    public Optional<TeamConfig> get(String teamName) { ... }
}

public record TeammateDef(
    String name,
    String agentType,            // built-in or custom agent type
    String model,                // "inherit", "sonnet", etc.
    String systemPromptOverride  // null = use agent type default
) {}

public record TeammateHandle(
    String name,
    String sessionId,
    Thread thread                // virtual thread
) {
    public boolean isAlive() { return thread.isAlive(); }
}
```

### 9.3 Task Management Interfaces

```java
// --- aceclaw-daemon ---

public record AgentTask(
    String id,
    String subject,
    String description,
    TaskStatus status,
    String owner,
    String activeForm,       // present continuous form for UI spinner
    List<String> blockedBy,
    List<String> blocks,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt
) {}

public enum TaskStatus {
    PENDING, IN_PROGRESS, COMPLETED, DELETED
}

public final class TaskManager {

    private final Path tasksDir;   // ~/.aceclaw/tasks/{team-name}/

    /**
     * Creates a new task. Returns the assigned task ID.
     */
    public AgentTask create(String subject, String description,
                             String activeForm, List<String> blockedBy) { ... }

    /**
     * Lists all non-deleted tasks.
     */
    public List<AgentTask> list() { ... }

    /**
     * Gets a task by ID.
     */
    public Optional<AgentTask> get(String taskId) { ... }

    /**
     * Claims a task (sets owner, status=IN_PROGRESS).
     * Uses FileLock to prevent race conditions.
     * Returns false if already claimed or blocked.
     */
    public boolean claim(String taskId, String owner) { ... }

    /**
     * Marks a task as completed and auto-unblocks dependents.
     */
    public void complete(String taskId) { ... }

    /**
     * Updates task fields (owner, status, description, dependencies).
     */
    public void update(String taskId, AgentTask updated) { ... }
}
```

### 9.4 Tool Interfaces for Teams

```java
// --- aceclaw-tools ---

public final class TaskTool implements Tool {

    @Override
    public String name() { return "task"; }

    @Override
    public String description() {
        return """
            Launch a sub-agent to handle a focused task autonomously.
            Sub-agents have their own context window and report results back.

            Parameters:
            - agent_type (required): built-in type (explore, plan, general, bash) or custom
            - prompt (required): instructions for the sub-agent
            - description: short 3-5 word summary
            - background: true to run concurrently (default: false)
            - resume: agent ID to continue from previous execution
            - model: override model (sonnet, opus, haiku)
            - max_turns: maximum agentic turns
            """;
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        // Parse parameters, lookup agent type, run sub-agent
        ...
    }
}

public final class TeamCreateTool implements Tool {
    @Override public String name() { return "team_create"; }
    // Creates a team, sets up file system layout
}

public final class TeamTaskTool implements Tool {
    @Override public String name() { return "team_task"; }
    // CRUD operations on team task list (create, list, update, complete)
}

public final class TeamSpawnTool implements Tool {
    @Override public String name() { return "team_spawn"; }
    // Spawns a teammate virtual thread
}

public final class TeamMessageTool implements Tool {
    @Override public String name() { return "team_message"; }
    // Sends direct message or broadcast
}

public final class TeamDeleteTool implements Tool {
    @Override public String name() { return "team_delete"; }
    // Deletes team and cleans up files
}

public final class TaskOutputTool implements Tool {
    @Override public String name() { return "task_output"; }
    // Retrieves result from background sub-agent
}
```

### 9.5 Security Interfaces

```java
// --- aceclaw-security ---

/**
 * Tracks pre-approved permissions for background sub-agents.
 * Background agents auto-deny anything not pre-approved.
 */
public final class PermissionPreApproval {

    private final ConcurrentHashMap<String, Set<String>> approvals = new ConcurrentHashMap<>();

    /**
     * Pre-approves a set of tools for a background agent.
     */
    public void preApprove(String agentId, Set<String> toolNames) {
        approvals.put(agentId, Set.copyOf(toolNames));
    }

    /**
     * Checks if a tool is pre-approved for this agent.
     */
    public boolean isApproved(String agentId, String toolName) {
        var approved = approvals.get(agentId);
        return approved != null && approved.contains(toolName);
    }

    /**
     * Removes pre-approvals for a completed agent.
     */
    public void revoke(String agentId) {
        approvals.remove(agentId);
    }
}
```

---

## 10. Data Flow Diagrams

### 10.1 Sub-Agent Foreground Flow

```
┌──────────────────────────────────────────────────────────────────┐
│                  SUB-AGENT FOREGROUND FLOW                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  CLI → agent.prompt("Use explore to find auth files")             │
│       │                                                            │
│       ▼                                                            │
│  StreamingAgentHandler → StreamingAgentLoop.runTurn()              │
│       │                                                            │
│       ▼                                                            │
│  LLM → tool_use: task(agent_type="explore", prompt="Find auth")   │
│       │                                                            │
│       ▼                                                            │
│  TaskTool.execute()                                                │
│       │                                                            │
│       ├── AgentTypeRegistry.get("explore")                         │
│       │   → SubAgentConfig(model=haiku, tools=[read,glob,grep])   │
│       │                                                            │
│       ├── SubAgentRunner.runForeground(config, prompt)             │
│       │   │                                                        │
│       │   ├── Create filtered ToolRegistry (no task tool)          │
│       │   ├── Create fresh StreamingAgentLoop                      │
│       │   ├── Run ReAct loop on virtual thread                     │
│       │   │   ├── LLM call → grep → LLM call → read → ...        │
│       │   │   └── end_turn                                         │
│       │   ├── Save transcript to JSONL                             │
│       │   └── Return Turn                                          │
│       │                                                            │
│       └── Return ToolResult(text=sub-agent's output)               │
│       │                                                            │
│       ▼                                                            │
│  Parent loop continues with sub-agent's result                     │
│                                                                    │
└──────────────────────────────────────────────────────────────────┘
```

### 10.2 Agent Team Full Lifecycle

```
┌──────────────────────────────────────────────────────────────────┐
│                  AGENT TEAM FULL LIFECYCLE                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  PHASE 1: SETUP                                                    │
│  ┌──────────────────────────────────────────────────┐             │
│  │ Lead: team_create("auth-feature", "Add JWT auth") │             │
│  │   → ~/.aceclaw/teams/auth-feature/config.json     │             │
│  │   → ~/.aceclaw/tasks/auth-feature/                │             │
│  │                                                    │             │
│  │ Lead: team_task(create, "Research JWT libs")       │             │
│  │ Lead: team_task(create, "Analyze auth code")       │             │
│  │ Lead: team_task(create, "Implement JWT", blockedBy=[1,2]) │    │
│  │                                                    │             │
│  │ Lead: team_spawn("researcher", "explore")          │             │
│  │ Lead: team_spawn("implementer", "general")         │             │
│  └──────────────────────────────────────────────────┘             │
│                                                                    │
│  PHASE 2: EXECUTION (concurrent virtual threads)                   │
│  ┌──────────────────────────────────────────────────┐             │
│  │ researcher thread:                                 │             │
│  │   TaskManager.list() → claim task 1                │             │
│  │   [read_file, glob, grep — research JWT]           │             │
│  │   TaskManager.complete(1)                          │             │
│  │   MessageRouter.send(lead, "JWT research done")    │             │
│  │   TaskManager.list() → claim task 2                │             │
│  │   [grep, read_file — analyze existing auth]        │             │
│  │   TaskManager.complete(2)                          │             │
│  │   → idle (no more unblocked tasks)                 │             │
│  │                                                    │             │
│  │ implementer thread:                                │             │
│  │   TaskManager.list() → no unblocked tasks          │             │
│  │   → idle (waiting)                                 │             │
│  │   [tasks 1,2 complete → task 3 unblocked]          │             │
│  │   TaskManager.list() → claim task 3                │             │
│  │   [edit_file, write_file, bash — implement JWT]    │             │
│  │   TaskManager.complete(3)                          │             │
│  │   MessageRouter.send(lead, "JWT implemented")      │             │
│  │   → idle                                           │             │
│  └──────────────────────────────────────────────────┘             │
│                                                                    │
│  PHASE 3: LEARNING & TEARDOWN                                      │
│  ┌──────────────────────────────────────────────────┐             │
│  │ Lead receives completion messages                  │             │
│  │ Lead: TeamSummaryExtractor.extract(tasks, messages)│             │
│  │   → Insights persisted to auto-memory              │             │
│  │                                                    │             │
│  │ Lead: team_shutdown("researcher")                  │             │
│  │ Lead: team_shutdown("implementer")                 │             │
│  │ [teammates approve, threads complete]              │             │
│  │                                                    │             │
│  │ Lead: team_delete("auth-feature")                  │             │
│  │   → Remove ~/.aceclaw/teams/auth-feature/          │             │
│  │   → Remove ~/.aceclaw/tasks/auth-feature/          │             │
│  └──────────────────────────────────────────────────┘             │
│                                                                    │
└──────────────────────────────────────────────────────────────────┘
```

### 10.3 Background Sub-Agent + TaskOutput

```
Main Agent Loop                         Background Sub-Agent
    │                                        │
    ├── TaskTool(background=true) ──────────>│
    │   returns agentId="agent-xyz"          │
    │                                        │
    ├── [continues other work]               │
    │   read_file, edit_file, etc.           ├── [running independently]
    │                                        │   LLM → tool → LLM → tool
    │                                        │
    ├── TaskOutputTool("agent-xyz")          │
    │   → "Agent still running..."           │
    │                                        │
    ├── [more work]                          ├── [completes]
    │                                        │   status = COMPLETED
    │                                        │   result = "Found 5 issues..."
    │                                        │
    ├── TaskOutputTool("agent-xyz")          │
    │   → "Found 5 issues: ..."             │
    │                                        │
    └── [processes result]                   │
```

---

## 11. Implementation Roadmap

### Phase 1: Sub-Agent Infrastructure (P0)

| Task | Module | Effort | Dependencies |
|------|--------|--------|--------------|
| Implement SubAgentConfig record | aceclaw-daemon | Low | None |
| Implement AgentTypeRegistry (4 built-in types) | aceclaw-daemon | Medium | SubAgentConfig |
| Implement SubAgentRunner (foreground only) | aceclaw-daemon | High | AgentTypeRegistry |
| Implement TaskTool | aceclaw-tools | Medium | SubAgentRunner |
| No-nesting enforcement (filter Task from sub-agent tools) | aceclaw-daemon | Low | SubAgentRunner |
| Sub-agent system prompt assembly (working dir, ACECLAW.md) | aceclaw-daemon | Medium | None |
| Integration tests for sub-agent lifecycle | aceclaw-daemon | Medium | All above |

### Phase 2: Custom Agents + Persistence (P1)

| Task | Module | Effort | Dependencies |
|------|--------|--------|--------------|
| Custom agent config parser (YAML frontmatter + markdown) | aceclaw-daemon | Medium | AgentTypeRegistry |
| SubAgentTranscriptStore (JSONL persistence) | aceclaw-daemon | Medium | None |
| Sub-agent resumption via agent ID | aceclaw-daemon | Medium | TranscriptStore |
| Background sub-agent execution | aceclaw-daemon | High | SubAgentRunner |
| BackgroundAgentRegistry | aceclaw-daemon | Medium | None |
| TaskOutputTool | aceclaw-tools | Low | BackgroundAgentRegistry |
| PermissionPreApproval for background agents | aceclaw-security | Medium | None |

### Phase 3: Agent Teams Core (P2)

| Task | Module | Effort | Dependencies |
|------|--------|--------|--------------|
| TeamConfig record + file I/O | aceclaw-daemon | Medium | None |
| TeamManager (create, spawn, shutdown, delete) | aceclaw-daemon | High | TeamConfig |
| AgentTask record + TaskManager (CRUD + FileLock) | aceclaw-daemon | High | None |
| TaskStatus enum + dependency resolution | aceclaw-daemon | Medium | TaskManager |
| TeammateRunner (virtual thread lifecycle) | aceclaw-daemon | High | TeamManager |
| TeamCreateTool | aceclaw-tools | Low | TeamManager |
| TeamTaskTool | aceclaw-tools | Medium | TaskManager |
| TeamSpawnTool | aceclaw-tools | Low | TeamManager |
| TeamDeleteTool | aceclaw-tools | Low | TeamManager |

### Phase 4: Inter-Agent Communication (P2)

| Task | Module | Effort | Dependencies |
|------|--------|--------|--------------|
| TeamMessage sealed interface (all message types) | aceclaw-daemon | Medium | None |
| MessageRouter (file + in-memory channels) | aceclaw-daemon | High | TeamMessage |
| TeamMessageTool (direct + broadcast) | aceclaw-tools | Medium | MessageRouter |
| Idle state management + heartbeat | aceclaw-daemon | Medium | MessageRouter |
| Shutdown protocol (request/response) | aceclaw-daemon | Medium | MessageRouter |
| Delegate mode (coordination-only tool restriction) | aceclaw-daemon | Low | None |

### Phase 5: Summary Learning (P3)

| Task | Module | Effort | Dependencies |
|------|--------|--------|--------------|
| TeamInsight sealed interface | aceclaw-daemon | Low | None |
| TeamSummaryExtractor | aceclaw-daemon | High | TeamInsight |
| TeamEffectivenessReport | aceclaw-daemon | Medium | None |
| Integration with AutoMemoryStore | aceclaw-daemon | Low | TeamSummaryExtractor |
| Cross-session team learning (memory recall) | aceclaw-daemon | Medium | AutoMemoryStore |

---

## Appendix A: Comparison with Claude Code

| Aspect | Claude Code | AceClaw |
|--------|-------------|---------|
| **Sub-agent runtime** | Separate context within same Node.js process | Virtual thread within daemon JVM |
| **Team runtime** | Separate OS processes (tmux/iTerm2/in-process) | Virtual threads in same JVM |
| **Communication** | File-based inboxes | File-based + in-memory BlockingQueue |
| **Task coordination** | JSON files on disk | JSON files on disk (identical) |
| **Agent config format** | Markdown + YAML frontmatter | Markdown + YAML frontmatter (compatible) |
| **Persistence** | JSONL transcripts | JSONL transcripts (compatible) |
| **No-nesting rule** | Task tool excluded from sub-agents | Task + TeamCreate excluded from sub-agents |
| **Background agents** | Pre-approved permissions | Pre-approved permissions (identical) |
| **Delegate mode** | Shift+Tab toggle | AgentMode.DELEGATE enum |
| **Idle management** | Automatic notifications | Automatic notifications (identical) |

### Key Advantage: In-Daemon Virtual Threads

Claude Code spawns separate OS processes for teammates, which means:
- ~50MB per process (Node.js runtime)
- IPC serialization overhead
- Process spawn latency (~100ms)
- Complex process lifecycle management

AceClaw uses virtual threads:
- ~1MB per virtual thread
- Direct Java method calls (no IPC)
- Microsecond spawn latency
- Simple thread lifecycle (interrupted → done)
- Shared connection pools (LLM, MCP)
- Shared ToolRegistry and PermissionManager

**Tradeoff:** All teammates share one JVM. A bug in one teammate's tool execution could potentially affect others. Mitigation: `StructuredTaskScope` for bounded tool execution.

## Appendix B: Security Considerations

1. **Sub-agent isolation**: Each sub-agent has its own conversation history. No access to parent history or other sub-agents' state.
2. **No-nesting rule**: Prevents resource exhaustion from recursive agent spawning. Enforced at ToolRegistry level.
3. **Background agent permissions**: Pre-approved before launch. Auto-deny anything not pre-approved. No interactive permission prompts.
4. **File-based coordination**: Task files and inboxes use `FileLock` to prevent race conditions. Atomic writes via temp-file-then-rename pattern.
5. **Team isolation**: Teams are scoped by name. Different teams cannot read each other's task lists or inboxes.
6. **Shutdown protocol**: Teammates can reject shutdown requests if they're in the middle of critical work.
7. **Memory isolation**: Each teammate's conversation is independent. Summary learning extracts only high-level insights, not raw conversation content.
8. **Daemon crash recovery**: File-based task lists and inboxes survive daemon restarts. Tasks in `IN_PROGRESS` state can be re-claimed after restart.

## Appendix C: Performance Considerations

1. **Virtual thread overhead**: ~1MB per thread, no OS thread exhaustion. A team of 10 teammates uses ~10MB total.
2. **LLM connection sharing**: All teammates share the daemon's HTTP client pool. Connection reuse reduces latency.
3. **File I/O for tasks**: JSON files are small (~1KB each). FileLock adds ~1ms overhead per operation. Acceptable for coordination (not in the critical path).
4. **Message delivery**: In-memory BlockingQueue for immediate delivery. File persistence is async (append-only JSONL).
5. **Transcript storage**: JSONL append-only. Loaded on-demand for resumption. Cleaned up after configurable retention period.
6. **Team cleanup**: File deletion on team delete. No dangling resources after proper teardown.
7. **Context window per teammate**: Each teammate has its own 200K context window. Total LLM API cost scales linearly with team size.
