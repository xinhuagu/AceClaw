# AceClaw Execution Dashboard — Frontend PRD

## Real-Time Execution Tree Visualization for All Three Tiers

**Version**: 1.0
**Date**: 2026-04-28
**Status**: Draft
**Parent PRD**: PRD.md (AceClaw v1.2)
**Author**: AceClaw PRD Team
**Scope**: Standalone web frontend that visualizes AceClaw agent execution in real-time as a live, interactive tree.

---

> **Relationship to Main PRD**: The main PRD (§4.9.5) describes a "Swarm Dashboard" for Tier 3 only. This PRD **supersedes and extends** that concept — the Execution Dashboard covers **all three tiers** (ReAct, Plan, Swarm) with a single recursive tree component. The Swarm Dashboard becomes a special case (Tier 3 view) within this unified frontend.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Design Philosophy: Runtime-UI Separation](#2-design-philosophy-runtime-ui-separation)
3. [Product Vision](#3-product-vision)
4. [Core Concept: The Execution Tree](#4-core-concept-the-execution-tree)
5. [Architecture](#5-architecture)
6. [Event System (Backend → Frontend)](#6-event-system-backend--frontend)
7. [Frontend Technical Design](#7-frontend-technical-design)
8. [Interaction Model](#8-interaction-model)
9. [Visual Design](#9-visual-design)
10. [Implementation Phases](#10-implementation-phases)
11. [Technology Stack](#11-technology-stack)
12. [Performance Requirements](#12-performance-requirements)
13. [Open Questions](#13-open-questions)

---

## 1. Problem Statement

### Current State

AceClaw's CLI renders agent execution as **flat text stream** — tool invocations, text output, and plan steps are interleaved sequentially in the terminal. This works for simple tasks but breaks down as complexity increases:

| Tier | Current Experience | Problem |
|------|-------------------|---------|
| **Tier 1 (ReAct)** | Tool calls shown inline, parallel tools appear sequential | User cannot see parallel execution; no structural overview |
| **Tier 2 (Plan)** | Step names shown before execution, results inline | User cannot see remaining steps, progress, or replan events clearly |
| **Tier 3 (Swarm)** | Not yet implemented | Multiple workers in one console = unreadable interleaved output |

### Root Cause

The terminal is a **one-dimensional** output channel. Agent execution is a **tree** (turns contain tools, plans contain steps, steps contain turns, swarms contain workers containing plans). Projecting a tree onto a line loses structure.

### Impact

- Users cannot understand **what is happening** during complex executions
- Users cannot see **what will happen** (plan steps, DAG dependencies)
- Users cannot **control** execution (pause, approve, cancel) without CLI commands
- Users cannot distinguish **parallel** from **sequential** execution
- Users lose context when execution takes > 30 seconds

---

## 2. Design Philosophy: Runtime-UI Separation

> **AceClaw's original intent is not "CLI only" — it is "runtime and UI separation".**

The daemon is the **kernel**. The CLI is merely the **first client**. This PRD adds the **second client** (web browser) without disturbing the first.

### The Stable Path

```
                     ┌───────────────────────────────────────────────────┐
                     │              AceClaw Daemon (Kernel)               │
                     │                                                   │
                     │  AgentLoop ─→ Plan Executor ─→ (future) Swarm    │
                     │                      │                            │
                     │              JSON-RPC / Streaming Events          │
                     │              (unchanged — this is the contract)   │
                     │                      │                            │
                     │           ┌──────────┴──────────┐                 │
                     │           │                     │                 │
                     │      Unix Socket           Web Bridge             │
                     │      (existing)            (new — thin layer)     │
                     │           │                     │                 │
                     └───────────│─────────────────────│─────────────────┘
                                 │                     │
                                 ▼                     ▼
                     ┌───────────────────┐  ┌────────────────────────┐
                     │    CLI (Terminal)  │  │   Web UI (Browser)     │
                     │    ✅ unchanged    │  │   ✅ new               │
                     │                   │  │                        │
                     │  Text rendering   │  │  Tree visualization    │
                     │  Permission TTY   │  │  Permission buttons    │
                     │  Keyboard input   │  │  Pause/Resume/Cancel   │
                     └───────────────────┘  └────────────────────────┘
                                 │                     │
                                 └──────────┬──────────┘
                                            │
                                  Future: more clients
                                  - VS Code extension
                                  - Mobile app
                                  - Slack/Teams bot
                                  - Remote daemon access
```

### What This Means

| Principle | Implication |
|-----------|-------------|
| **Daemon = kernel** | All intelligence, execution, memory, tool routing stays in the daemon. Zero logic moves to the frontend. |
| **JSON-RPC = contract** | The existing event protocol (`stream.text`, `stream.tool_use`, `permission.request`, etc.) is the **stable API**. The web bridge does not invent new semantics — it translates existing ones to WebSocket. |
| **CLI does not die** | The terminal remains the primary input channel. The dashboard is an **observer + secondary controller**, not a replacement. If the browser disconnects, execution continues. |
| **Web bridge = thin** | The bridge is a ~200-line adapter: receive JSON-RPC notifications from daemon's `EventMultiplexer`, convert to WebSocket frames, forward to browser. No business logic. |
| **Frontend = display + interaction only** | React renders the tree. Buttons send commands. That's it. No execution logic, no LLM calls, no tool routing. |
| **Multi-client ready** | Any future client (VS Code, mobile, Slack bot) connects via the same protocol — WebSocket for push events, REST for commands. The daemon doesn't care who's listening. |

### What We Get

1. **CLI continues to work** — zero regression, zero migration
2. **Web visualization** — real-time execution tree across all three tiers
3. **Full observability** — ReAct turns, tool use, plan steps, permission requests, swarm workers — all visible
4. **Interactive control** — permissions, pause, resume, cancel — from the browser
5. **Extensibility** — the web bridge pattern is the same pattern for any future UI client

> **One kernel. Many windows. The daemon owns the brain; clients own the eyes.**

---

## 3. Product Vision

A **real-time web dashboard** that renders AceClaw agent execution as a **live, interactive tree** — growing in real-time as the agent works, with drill-down from swarm → worker → plan → step → turn → tool.

### Design Principles

1. **Tree-first** — Execution is always a tree, never flat text
2. **Real-time** — Nodes appear and update as events arrive (< 100ms latency)
3. **Fractal** — The same `<ExecutionNode>` component renders all levels; Tier 3 is just deeper nesting
4. **Pre-visualization** — Plans and DAGs are shown **before** execution starts (AceClaw pre-plans)
5. **Interactive** — Permission requests, pause/resume, cancel — all from the browser
6. **Non-blocking** — The dashboard is a **read/control** overlay; the CLI remains the primary input channel
7. **Zero-config** — `aceclaw dashboard` opens the browser; no setup required

---

## 4. Core Concept: The Execution Tree

### 4.1 Tree Structure (Fractal / Self-Similar)

```
Tier 3: Swarm Node
  ├── Meta-Agent Node
  │   └── Planning Turn
  │       └── 🟢 LLM call [320ms]
  ├── Worker-1 Node (role: Coder, model: Haiku)
  │   └── Plan Node (linear steps)
  │       ├── ✅ Step 1: "Read source files"
  │       │   └── Turn 1
  │       │       ├── 🟢 read_file(auth.java) [45ms]
  │       │       ├── 🟢 read_file(config.java) [38ms]    ← parallel
  │       │       └── 🟢 grep("@Auth") [120ms]             ← parallel
  │       ├── 🔄 Step 2: "Modify handler" [running]
  │       │   └── Turn 1
  │       │       └── 🟡 edit_file(handler.java) [running]
  │       ├── ⬚ Step 3: "Add tests" [pending]
  │       └── ⬚ Step 4: "Run tests" [pending]
  ├── Worker-2 Node (role: Coder, model: Haiku)           ← parallel with W1
  │   └── Plan Node
  │       ├── 🔄 Step 1: "Refactor middleware" [running]
  │       │   └── Turn 1
  │       │       └── 🟡 read_file(middleware.java) [running]
  │       └── ⬚ Step 2: "Update routes" [pending]
  └── Worker-3 Node (role: Tester, model: Sonnet)          ← DAG dep on W1+W2
      └── ⬚ [waiting for Worker-1 and Worker-2]
```

### 4.2 Node Types

Every node in the tree is one of these types, rendered by the **same recursive component**:

| Node Type | Parent | Children | Created By Event |
|-----------|--------|----------|-----------------|
| **Session** | (root) | Turn \| Plan \| Swarm | Session start |
| **Swarm** | Session | Meta-Agent, Worker[] | `stream.swarm_created` |
| **Worker** | Swarm | Plan \| Turn | `stream.worker_started` |
| **Plan** | Session \| Worker | Step[] | `stream.plan_created` |
| **Step** | Plan | Turn[] | `stream.plan_created` (all steps listed) |
| **Turn** | Session \| Step \| Worker | Tool[] | `stream.turn_started` *(new event)* |
| **Tool** | Turn | (leaf) | `stream.tool_use` |
| **Permission** | Turn | (leaf, interactive) | `permission.request` |
| **Text** | Turn | (leaf, collapsible) | `stream.text` |

### 4.3 Node State Machine

Every node follows the same lifecycle:

```
pending ──→ running ──→ completed
                    ──→ failed
                    ──→ cancelled
pending ──→ skipped (replan removed it)
running ──→ paused ──→ running (resume)
running ──→ awaiting_input ──→ running (user responds)
```

### 4.4 Unified Node Data Model

```typescript
interface ExecutionNode {
  id: string;
  type: 'session' | 'swarm' | 'worker' | 'plan' | 'step' | 'turn' | 'tool' | 'permission' | 'text';
  status: 'pending' | 'running' | 'completed' | 'failed' | 'cancelled' | 'skipped' | 'paused' | 'awaiting_input';
  label: string;
  
  // Timing
  startTime?: number;        // epoch ms
  endTime?: number;
  duration?: number;         // ms (computed or from event)
  
  // Hierarchy
  children: ExecutionNode[];
  parallel?: boolean;        // true if children execute concurrently
  
  // Type-specific metadata
  meta?: {
    // Tool nodes
    toolName?: string;
    isError?: boolean;
    errorPreview?: string;
    
    // Worker nodes
    role?: string;           // 'coder' | 'reviewer' | 'tester' | 'researcher'
    model?: string;          // 'haiku' | 'sonnet' | 'opus'
    
    // Step nodes
    stepIndex?: number;
    totalSteps?: number;
    fallbackApproach?: string;
    
    // Plan nodes
    replanCount?: number;
    
    // Permission nodes
    permissionPrompt?: string;
    permissionTool?: string;
    
    // Swarm nodes
    dagDependencies?: Record<string, string[]>;  // taskId → depIds
    
    // Cost tracking
    tokens?: { input: number; output: number };
    cost?: number;
  };
  
  // Collapsible content
  textContent?: string;      // For text nodes — full LLM output (collapsed by default)
}
```

---

## 5. Architecture

### 5.1 System Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                         AceClaw Daemon (Java 21)                     │
│                                                                      │
│  StreamingAgentLoop ───→ StreamingNotificationHandler                │
│  SequentialPlanExecutor ─→ PlanEventListener                         │
│  (future) SwarmExecutor ──→ SwarmEventListener                       │
│                                │                                     │
│                          JSON-RPC events                             │
│                                │                                     │
│                     ┌──────────┴──────────┐                          │
│                     │    EventMultiplexer  │  ← NEW (§4.2)           │
│                     └──────┬────────┬─────┘                          │
│                            │        │                                │
│                     Unix Socket   WebSocket                          │
│                     (→ CLI)       (→ Browser)  ← NEW                 │
│                                      │                               │
│                          ┌───────────┤                               │
│                          │   Javalin │  (embedded, ~100KB)           │
│                          │  :3141    │                               │
│                          │  GET /    │  → serve SPA                  │
│                          │  WS /ws   │  → event stream              │
│                          │  POST /api│  → control (pause/cancel)    │
│                          └───────────┘                               │
└──────────────────────────────────────────────────────────────────────┘
                                   │
                            ws://localhost:3141/ws
                                   │
┌──────────────────────────────────│───────────────────────────────────┐
│                        React SPA (Browser)                           │
│                                                                      │
│  WebSocket ──→ EventReducer (useReducer) ──→ ExecutionTree           │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  Header: session info | tokens | cost | elapsed | controls     │  │
│  ├────────────────────────────────────────────────────────────────┤  │
│  │                                                                │  │
│  │   ExecutionTree (react-window virtualized)                     │  │
│  │                                                                │  │
│  │   ▼ Session: "Refactor auth module + add tests"               │  │
│  │     ▼ Plan (5 steps, 1 replan)                                │  │
│  │       ✅ Step 1: Read source files                            │  │
│  │         ▸ Turn 1 (3 tools, 203ms)        ← collapsed          │  │
│  │       🔄 Step 2: Modify handler                               │  │
│  │         ▼ Turn 1                          ← expanded (active) │  │
│  │           🟡 edit_file(handler.java)  [2.1s running]          │  │
│  │       ⬚ Step 3: Add tests                                     │  │
│  │       ⬚ Step 4: Run tests                                     │  │
│  │       ⬚ Step 5: Verify                                        │  │
│  │                                                                │  │
│  ├────────────────────────────────────────────────────────────────┤  │
│  │  Event Log (tail -f style)                                     │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

### 5.2 EventMultiplexer (New Daemon Component)

The daemon currently sends events to **one** `StreamContext` (the CLI connection). The EventMultiplexer fans out to **N** listeners:

```java
// New class in aceclaw-daemon
public final class EventMultiplexer implements StreamContext {
    
    private final StreamContext primary;                     // CLI (Unix socket)
    private final List<StreamContext> secondaries;           // WebSocket sessions
    
    @Override
    public void sendNotification(String method, Object params) throws IOException {
        // Always send to CLI (primary)
        primary.sendNotification(method, params);
        
        // Best-effort fan-out to dashboard(s)
        for (var ctx : secondaries) {
            try {
                ctx.sendNotification(method, params);
            } catch (IOException e) {
                // Dashboard disconnect is non-fatal; log and remove
                secondaries.remove(ctx);
            }
        }
    }
    
    public void addDashboard(StreamContext wsContext) { secondaries.add(wsContext); }
    public void removeDashboard(StreamContext wsContext) { secondaries.remove(wsContext); }
}
```

**Key design choice**: The CLI remains the **primary** channel. The dashboard is a **secondary observer**. If the dashboard disconnects, execution continues uninterrupted.

### 5.3 WebSocket Endpoint

```java
// Javalin embedded in daemon — started on demand by `aceclaw dashboard`
var app = Javalin.create(config -> {
    config.staticFiles.add("/dashboard", Location.CLASSPATH);  // serve SPA
}).start(3141);

app.ws("/ws", ws -> {
    ws.onConnect(ctx -> {
        var wsContext = new JavalinWebSocketStreamContext(ctx);
        multiplexer.addDashboard(wsContext);
        
        // Send current state snapshot (for late-join / page refresh)
        ctx.send(objectMapper.writeValueAsString(
            Map.of("method", "snapshot", "params", getCurrentExecutionTree())
        ));
    });
    
    ws.onMessage(ctx -> {
        // Handle permission responses, pause/cancel commands from browser
        var msg = objectMapper.readTree(ctx.message());
        switch (msg.get("method").asText()) {
            case "permission.response" -> handlePermissionResponse(msg);
            case "swarm.pause"         -> handleSwarmPause();
            case "swarm.cancel"        -> handleSwarmCancel();
        }
    });
    
    ws.onClose(ctx -> multiplexer.removeDashboard(...));
});
```

### 5.4 Launch Flow

```bash
# User types in terminal:
$ aceclaw dashboard

# This:
# 1. Sends "dashboard.start" notification to daemon
# 2. Daemon starts Javalin on :3141 (if not already running)
# 3. Opens browser to http://localhost:3141
# 4. Browser connects WebSocket, receives state snapshot
# 5. Real-time events start flowing

# Or auto-open on plan/swarm:
$ aceclaw --dashboard "refactor the auth module"
# Automatically opens dashboard when plan/swarm mode triggers
```

---

## 6. Event System (Backend → Frontend)

### 6.1 Existing Events (Already Implemented in Daemon)

These events are already sent by `StreamingNotificationHandler` and `PlanEventListener` in `StreamingAgentHandler.java`:

| Event | Source | Payload | Tree Action |
|-------|--------|---------|-------------|
| `stream.text` | `StreamingNotificationHandler` | `{text}` | Append text to current Turn's text node |
| `stream.thinking` | `StreamingNotificationHandler` | `{text}` | Append to thinking indicator (collapsible) |
| `stream.tool_use` | `StreamingNotificationHandler` | `{toolUseId, name, input}` | Add Tool child to current Turn (status: running) |
| `stream.tool_completed` | `StreamingNotificationHandler` | `{toolUseId, name, durationMs, isError, errorPreview}` | Update Tool node (status: completed/failed) |
| `stream.heartbeat` | `StreamingNotificationHandler` | `{phase}` | Pulse animation on current running node |
| `stream.error` | `StreamingNotificationHandler` | `{message}` | Mark current node as failed |
| `stream.compaction` | `StreamingNotificationHandler` | `{before, after}` | Show compaction indicator on session |
| `stream.subagent.start` | `StreamingNotificationHandler` | `{agentId, prompt}` | Add SubAgent child node (status: running) |
| `stream.subagent.end` | `StreamingNotificationHandler` | `{agentId}` | Mark SubAgent node completed |
| `stream.usage` | `StreamingNotificationHandler` | `{input, output, cacheRead, cacheCreation}` | Update header cost/token counters |
| `stream.plan_created` | `StreamingAgentHandler` | `{planId, goal, steps[]}` | Add Plan node + all Step children (pending) |
| `stream.plan_step_started` | `StreamingAgentHandler` | `{planId, stepName, stepIndex, totalSteps}` | Activate Step node (status: running) |
| `stream.plan_step_completed` | `StreamingAgentHandler` | `{planId, stepName, stepIndex, success}` | Complete Step node (status: completed/failed) |
| `stream.plan_completed` | `StreamingAgentHandler` | `{planId, success, durationMs}` | Complete Plan node |
| `stream.plan_replanned` | `StreamingAgentHandler` | `{planId, attempt, rationale, newSteps[]}` | Replace pending Steps; add replan indicator |
| `stream.plan_escalated` | `StreamingAgentHandler` | `{planId, reason}` | Mark Plan failed; show escalation reason |
| `permission.request` | `PermissionGate` | `{requestId, tool, input, message}` | Add Permission node (status: awaiting_input) |
| `stream.gate` | `StreamingAgentHandler` | `{tool, action}` | Show permission gate decision |
| `stream.cancelled` | `StreamingAgentHandler` | `{sessionId}` | Mark session cancelled |
| `stream.budget_exhausted` | `StreamingAgentHandler` | `{sessionId, reason}` | Mark session budget-exceeded |

### 6.2 New Events Required

| Event | When | Payload | Tree Action |
|-------|------|---------|-------------|
| `stream.turn_started` | AgentLoop begins new iteration | `{turnNumber, parentStepId?}` | Add Turn child to current Step or Session |
| `stream.turn_completed` | AgentLoop iteration done | `{turnNumber, durationMs}` | Complete Turn node |
| `stream.swarm_created` | Meta-Agent generates DAG | `{swarmId, goal, tasks[], deps{}}` | Add Swarm node + Worker placeholders with DAG edges |
| `stream.worker_started` | Worker virtual thread begins | `{swarmId, workerId, role, model, taskDescription}` | Activate Worker node |
| `stream.worker_completed` | Worker finishes | `{swarmId, workerId, success, durationMs}` | Complete Worker node |
| `stream.swarm_replanned` | Meta-Agent modifies DAG | `{swarmId, attempt, rationale, newTasks[], newDeps{}}` | Update DAG structure with animation |
| `stream.swarm_completed` | All workers done | `{swarmId, success, totalDurationMs, totalCost}` | Complete Swarm node |
| `snapshot` | Dashboard connects (late-join) | `{tree: ExecutionNode}` | Replace entire tree state |

### 6.3 Event Implementation Priority

| Priority | Events | Effort | Enables |
|----------|--------|--------|---------|
| **P0** | Use all existing events as-is | 0h (already implemented) | Tier 1 tree |
| **P1** | `stream.turn_started`, `stream.turn_completed` | ~2h (add to `StreamingAgentLoop`) | Turn-level grouping |
| **P2** | `snapshot` | ~3h (serialize current execution state) | Page refresh / late join |
| **P3** | `stream.swarm_*` (6 events) | Part of Swarm implementation | Tier 3 tree |

---

## 7. Frontend Technical Design

### 7.1 State Management: Event Reducer

```typescript
// All state lives in a single useReducer — no external state library needed.
// Each WebSocket message dispatches to the reducer.

type TreeAction =
  | { type: 'SNAPSHOT'; tree: ExecutionNode }
  | { type: 'TURN_STARTED'; turnNumber: number; parentStepId?: string }
  | { type: 'TURN_COMPLETED'; turnNumber: number; durationMs: number }
  | { type: 'TOOL_USE'; toolUseId: string; name: string }
  | { type: 'TOOL_COMPLETED'; toolUseId: string; name: string; durationMs: number; isError: boolean; errorPreview?: string }
  | { type: 'TEXT_DELTA'; text: string }
  | { type: 'PLAN_CREATED'; planId: string; goal: string; steps: StepInfo[] }
  | { type: 'PLAN_STEP_STARTED'; planId: string; stepIndex: number }
  | { type: 'PLAN_STEP_COMPLETED'; planId: string; stepIndex: number; success: boolean }
  | { type: 'PLAN_REPLANNED'; planId: string; attempt: number; rationale: string; newSteps: StepInfo[] }
  | { type: 'PLAN_COMPLETED'; planId: string; success: boolean; durationMs: number }
  | { type: 'PLAN_ESCALATED'; planId: string; reason: string }
  | { type: 'SUBAGENT_START'; agentId: string; prompt: string }
  | { type: 'SUBAGENT_END'; agentId: string }
  | { type: 'PERMISSION_REQUEST'; requestId: string; tool: string; message: string }
  | { type: 'USAGE_UPDATE'; input: number; output: number }
  | { type: 'SWARM_CREATED'; swarmId: string; goal: string; tasks: TaskInfo[]; deps: Record<string, string[]> }
  | { type: 'WORKER_STARTED'; swarmId: string; workerId: string; role: string; model: string }
  | { type: 'WORKER_COMPLETED'; swarmId: string; workerId: string; success: boolean }
  | { type: 'SWARM_COMPLETED'; swarmId: string; success: boolean; totalCost: number };

function executionTreeReducer(state: ExecutionNode, action: TreeAction): ExecutionNode {
  switch (action.type) {
    case 'SNAPSHOT':
      return action.tree;

    case 'TOOL_USE':
      return addChildToActiveLeaf(state, {
        id: action.toolUseId,
        type: 'tool',
        status: 'running',
        label: action.name,
        children: [],
        meta: { toolName: action.name },
        startTime: Date.now(),
      });

    case 'TOOL_COMPLETED':
      return updateNode(state, action.toolUseId, {
        status: action.isError ? 'failed' : 'completed',
        duration: action.durationMs,
        meta: { isError: action.isError, errorPreview: action.errorPreview },
      });

    case 'PLAN_CREATED':
      return addPlanWithPendingSteps(state, action);

    case 'PLAN_STEP_STARTED':
      return activateStep(state, action.planId, action.stepIndex);

    case 'PLAN_REPLANNED':
      return replaceRemainingSteps(state, action);

    case 'PERMISSION_REQUEST':
      return addChildToActiveLeaf(state, {
        id: action.requestId,
        type: 'permission',
        status: 'awaiting_input',
        label: `Permission: ${action.tool}`,
        children: [],
        meta: { permissionPrompt: action.message, permissionTool: action.tool },
      });

    // ... other cases follow the same pattern
  }
}
```

### 7.2 WebSocket Hook

```typescript
function useExecutionStream(wsUrl: string) {
  const [tree, dispatch] = useReducer(executionTreeReducer, createRootNode());
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => setConnected(true);
    ws.onclose = () => {
      setConnected(false);
      // Auto-reconnect after 2s
      setTimeout(() => wsRef.current = new WebSocket(wsUrl), 2000);
    };

    ws.onmessage = (e) => {
      const event = JSON.parse(e.data);
      const action = mapEventToAction(event);  // JSON-RPC → TreeAction
      if (action) dispatch(action);
    };

    return () => ws.close();
  }, [wsUrl]);

  // Expose send for permission responses and control commands
  const send = useCallback((method: string, params: object) => {
    wsRef.current?.send(JSON.stringify({ method, params }));
  }, []);

  return { tree, connected, send };
}
```

### 7.3 Tree Renderer (Virtualized)

```tsx
// Using react-window VariableSizeList for virtualization.
// The tree is flattened into a list with indentation levels.

interface FlatNode {
  node: ExecutionNode;
  depth: number;
  isExpanded: boolean;
  isLast: boolean;          // last sibling (for tree-line drawing)
  parentIsLast: boolean[];  // for tree-line continuation
}

function flattenTree(root: ExecutionNode, expandedIds: Set<string>): FlatNode[] {
  const result: FlatNode[] = [];
  
  function walk(node: ExecutionNode, depth: number, isLast: boolean, parentIsLast: boolean[]) {
    result.push({ node, depth, isExpanded: expandedIds.has(node.id), isLast, parentIsLast });
    
    if (expandedIds.has(node.id)) {
      node.children.forEach((child, i) => {
        walk(child, depth + 1, i === node.children.length - 1, [...parentIsLast, isLast]);
      });
    }
  }
  
  walk(root, 0, true, []);
  return result;
}

// Auto-expand active (running) nodes, collapse completed ones
function useAutoExpand(tree: ExecutionNode): Set<string> {
  return useMemo(() => {
    const expanded = new Set<string>();
    
    function walk(node: ExecutionNode) {
      // Always expand running, awaiting_input nodes and their ancestors
      if (node.status === 'running' || node.status === 'awaiting_input') {
        expanded.add(node.id);
      }
      // Expand completed nodes only if they have < 5 children (readable)
      if (node.status === 'completed' && node.children.length < 5) {
        expanded.add(node.id);
      }
      node.children.forEach(walk);
    }
    
    walk(tree);
    return expanded;
  }, [tree]);
}
```

### 7.4 ExecutionNodeRow Component

```tsx
function ExecutionNodeRow({ flatNode, send }: { flatNode: FlatNode; send: SendFn }) {
  const { node, depth, isExpanded } = flatNode;
  
  return (
    <div className="node-row" style={{ paddingLeft: depth * 20 }}>
      {/* Tree lines */}
      <TreeLines parentIsLast={flatNode.parentIsLast} isLast={flatNode.isLast} />
      
      {/* Expand/collapse toggle */}
      {node.children.length > 0 && (
        <button className="toggle" onClick={() => toggleExpand(node.id)}>
          {isExpanded ? '▼' : '▸'}
        </button>
      )}
      
      {/* Status icon */}
      <StatusIcon status={node.status} type={node.type} />
      
      {/* Label */}
      <span className="label">
        {node.label}
        {node.meta?.role && <Badge>{node.meta.role}</Badge>}
        {node.meta?.model && <Badge variant="outline">{node.meta.model}</Badge>}
      </span>
      
      {/* Duration */}
      {node.status === 'running' && <LiveTimer startTime={node.startTime!} />}
      {node.duration && <span className="duration">{formatDuration(node.duration)}</span>}
      
      {/* Parallel indicator */}
      {node.parallel && <Badge variant="parallel">parallel</Badge>}
      
      {/* Error preview */}
      {node.meta?.isError && node.meta?.errorPreview && (
        <span className="error-preview">{node.meta.errorPreview}</span>
      )}
      
      {/* Permission input */}
      {node.type === 'permission' && node.status === 'awaiting_input' && (
        <PermissionPrompt
          message={node.meta!.permissionPrompt!}
          tool={node.meta!.permissionTool!}
          onRespond={(allowed) => send('permission.response', {
            requestId: node.id,
            allowed,
          })}
        />
      )}
    </div>
  );
}
```

### 7.5 Parallel Tool Detection

The frontend infers parallel execution from **event timing**:

```typescript
// When multiple stream.tool_use events arrive for the same Turn
// before any stream.tool_completed — they are parallel.

case 'TOOL_USE': {
  const currentTurn = findActiveTurn(state);
  if (!currentTurn) return state;
  
  // If the turn already has running tools, this is a parallel sibling
  const hasRunningTools = currentTurn.children.some(
    c => c.type === 'tool' && c.status === 'running'
  );
  
  if (hasRunningTools) {
    currentTurn.parallel = true;  // Mark the turn as parallel
  }
  
  return addChild(currentTurn, newToolNode(action));
}
```

Visual result:
```
▼ Turn 1                           ← parallel = true
  ├── 🟢 read_file(a.java) [45ms]  ┐
  ├── 🟢 read_file(b.java) [38ms]  ├ shown side-by-side or with ║ indicator
  └── 🟢 grep("@Auth") [120ms]     ┘
```

---

## 8. Interaction Model

### 8.1 Permission Handling (Bidirectional)

```
Daemon ──→ permission.request ──→ WebSocket ──→ Browser
                                                  │
                                          User clicks [Allow] / [Deny]
                                                  │
Browser ──→ permission.response ──→ WebSocket ──→ Daemon

Simultaneously:
Daemon ──→ permission.request ──→ Unix Socket ──→ CLI
                                                   │
                                           User types y/n
                                                   │
CLI ──→ permission.response ──→ Unix Socket ──→ Daemon

First response wins. Daemon ignores the second.
```

The tree shows a `⏸ Awaiting input` node with inline buttons:

```
  🔄 Step 2: Modify handler
    ▼ Turn 1
      ⏸ Permission: edit_file(src/auth/handler.java)
         "Allow editing src/auth/handler.java?"
         [✅ Allow]  [❌ Deny]  [🔓 Always Allow]
```

### 8.2 Controls (Header Bar)

| Control | Action | Implementation |
|---------|--------|---------------|
| **⏸ Pause** | Pause execution after current tool completes | `send('execution.pause')` → Daemon sets `CancellationToken.pause()` |
| **▶ Resume** | Resume paused execution | `send('execution.resume')` |
| **⏹ Stop** | Cancel execution gracefully | `send('execution.cancel')` → Daemon triggers `CancellationToken.cancel()` |
| **🔇 Auto-approve** | Approve all permissions for this session | `send('permission.auto_approve', { scope: 'session' })` |

### 8.3 Node Interactions

| Interaction | Behavior |
|-------------|----------|
| **Click expand/collapse** | Toggle children visibility |
| **Click completed tool** | Show full tool input/output in side panel |
| **Click failed tool** | Show error details in side panel |
| **Click text node** | Show full LLM text output (usually collapsed) |
| **Click pending step** | Show step description and dependencies |
| **Hover on worker** | Highlight DAG dependencies (for Tier 3) |
| **Click replan indicator** | Show replan rationale |

### 8.4 Auto-Scroll and Focus

```typescript
// The tree auto-scrolls to keep the active (running) node visible.
// User scrolling pauses auto-scroll; a "Follow execution" button resumes it.

function useAutoScroll(listRef: RefObject<VariableSizeList>, tree: ExecutionNode) {
  const [following, setFollowing] = useState(true);
  
  useEffect(() => {
    if (!following) return;
    
    const activeIndex = findFirstActiveNodeIndex(tree);
    if (activeIndex >= 0) {
      listRef.current?.scrollToItem(activeIndex, 'center');
    }
  }, [tree, following]);
  
  // On user scroll, disable following; show "Follow" button
  const onScroll = useCallback(() => setFollowing(false), []);
  
  return { following, setFollowing, onScroll };
}
```

---

## 9. Visual Design

### 9.1 Status Icons

| Status | Icon | Color | Animation |
|--------|------|-------|-----------|
| `pending` | `○` | Gray (#9CA3AF) | None |
| `running` | `●` | Blue (#3B82F6) | Pulse animation (opacity 0.5→1→0.5) |
| `completed` | `✓` | Green (#10B981) | None |
| `failed` | `✗` | Red (#EF4444) | None |
| `cancelled` | `⊘` | Gray (#6B7280) | None |
| `skipped` | `⊘` | Gray (#6B7280) | Strikethrough on label |
| `paused` | `⏸` | Yellow (#F59E0B) | None |
| `awaiting_input` | `⏸` | Yellow (#F59E0B) | Pulse + input UI shown |

### 9.2 Node Type Indicators

| Node Type | Prefix | Example |
|-----------|--------|---------|
| Session | (none) | `"Refactor auth module + add tests"` |
| Swarm | 🐝 | `🐝 Swarm: "Full system refactor"` |
| Worker | 🤖 | `🤖 Worker-1 (Coder, Haiku)` |
| Plan | 📋 | `📋 Plan (5 steps)` |
| Step | Number | `1. Read source files` |
| Turn | 🔄 | `🔄 Turn 1` |
| Tool | 🔧 | `🔧 read_file(auth.java)` |
| Permission | 🔒 | `🔒 Permission: edit_file(...)` |
| Text | 💬 | `💬 Response (243 chars)` — collapsed |

### 9.3 Color Themes

Support dark (default) and light themes, following system preference:

```css
:root {
  /* Dark theme (default) */
  --bg-primary: #0F172A;
  --bg-secondary: #1E293B;
  --bg-node-active: #1E3A5F;
  --text-primary: #F1F5F9;
  --text-secondary: #94A3B8;
  --border: #334155;
  --accent: #3B82F6;
}

@media (prefers-color-scheme: light) {
  :root {
    --bg-primary: #FFFFFF;
    --bg-secondary: #F8FAFC;
    --bg-node-active: #EFF6FF;
    --text-primary: #0F172A;
    --text-secondary: #64748B;
    --border: #E2E8F0;
    --accent: #2563EB;
  }
}
```

### 9.4 Layout

```
┌──────────────────────────────────────────────────────────────────┐
│  AceClaw Dashboard    ● Connected    [⏸ Pause] [⏹ Stop]        │
│  Session: abc123 | Tokens: 24.3k | Cost: $0.12 | Elapsed: 1m32s │
├───────────────────────────────────────────────┬──────────────────┤
│                                               │                  │
│  Execution Tree (70% width)                   │ Detail Panel     │
│                                               │ (30% width)      │
│  ▼ "Refactor auth module + add tests"         │                  │
│    ▼ 📋 Plan (5 steps, replan: 1)            │ ┌──────────────┐ │
│      ✓ 1. Read source files [2.1s]           │ │ Tool Detail   │ │
│        ▸ 🔄 Turn 1 (3 tools, 2.1s)          │ │               │ │
│      ● 2. Modify handler                      │ │ read_file     │ │
│        ▼ 🔄 Turn 1                           │ │ Input: ...    │ │
│          ● 🔧 edit_file(handler.java) 2.1s   │ │ Output: ...   │ │
│      ○ 3. Add tests                           │ │ Duration: 45ms│ │
│      ○ 4. Run tests                           │ └──────────────┘ │
│      ○ 5. Verify                              │                  │
│                                               │                  │
├───────────────────────────────────────────────┴──────────────────┤
│  Event Log                                          [Auto-scroll]│
│  19:23:15  🔧 edit_file(handler.java) started                    │
│  19:23:14  ✓ read_file(config.java) completed [38ms]             │
│  19:23:14  ✓ read_file(auth.java) completed [45ms]               │
│  19:23:14  ✓ grep("@Auth") completed [120ms]                     │
│  19:23:12  📋 Plan created: 5 steps                              │
└──────────────────────────────────────────────────────────────────┘
```

### 9.5 DAG View (Tier 3 Swarm)

When a Swarm is active, the tree panel gains a **DAG toggle**:

```
[🌳 Tree View]  [🕸 DAG View]    ← toggle between tree and graph

DAG View (D3.js force-directed or dagre layout):

  ┌──────────────┐     ┌──────────────────┐
  │ 🤖 W1:Coder  │     │ 🤖 W2:Coder      │
  │ ● Running    ├──┐  │ ● Running        ├──┐
  │ auth module  │  │  │ payment module   │  │
  └──────────────┘  │  └──────────────────┘  │
                    │                         │
                    └────────┬────────────────┘
                             │ (both must complete)
                             ▼
                    ┌──────────────────┐
                    │ 🤖 W3:Tester     │
                    │ ○ Waiting        │
                    │ integration tests│
                    └──────────────────┘
```

---

## 10. Implementation Phases

### Phase 1: Tier 1 Visualization (MVP) — ~2 weeks

**Goal**: Real-time tree for basic ReAct execution.

| Task | Effort | Description |
|------|--------|-------------|
| Javalin WebSocket endpoint in daemon | 3h | `/ws` endpoint, `EventMultiplexer` |
| `stream.turn_started/completed` events | 2h | Add to `StreamingAgentLoop` |
| `aceclaw dashboard` CLI command | 2h | Start Javalin, open browser |
| React SPA scaffold | 2h | Vite + React + TypeScript |
| `useExecutionStream` hook | 3h | WebSocket + reducer |
| `ExecutionNodeRow` component | 4h | Recursive tree with status icons, timers |
| Virtualized tree (`react-window`) | 3h | Flatten tree + `VariableSizeList` |
| Auto-expand/collapse + auto-scroll | 2h | Follow active node |
| Header bar (session info, tokens, cost) | 2h | `stream.usage` events |
| Dark/light theme | 1h | CSS variables + system preference |
| **Total** | **~24h** | |

**Delivers**: Live tree showing turns and tools for every ReAct execution. Parallel tools shown as siblings.

### Phase 2: Tier 2 Plan Visualization — ~1 week

**Goal**: Plan steps shown as a tree with pre-visualization.

| Task | Effort | Description |
|------|--------|-------------|
| Plan node + Step children rendering | 3h | `plan_created` → show all steps (pending) |
| Step activation and completion | 2h | `plan_step_started/completed` |
| Replan animation | 3h | Old steps strikethrough, new steps slide in |
| Plan escalation indicator | 1h | `plan_escalated` |
| Nested turns inside steps | 2h | Step → Turn → Tool (already built in Phase 1) |
| **Total** | **~11h** | |

**Delivers**: Full plan tree. User sees all steps before execution. Replan events animate.

### Phase 3: Interactivity — ~1 week

**Goal**: Permission handling, pause/resume, detail panel.

| Task | Effort | Description |
|------|--------|-------------|
| Permission request → inline buttons | 3h | `permission.request` → [Allow]/[Deny] |
| Permission response → WebSocket → daemon | 2h | Bidirectional, first-response-wins |
| Pause/Resume/Stop controls | 3h | Header buttons → daemon `CancellationToken` |
| Detail panel (tool input/output) | 4h | Click node → show details on right |
| Event log panel (bottom) | 2h | Tail-style log of all events |
| **Total** | **~14h** | |

**Delivers**: Fully interactive dashboard. Permissions answered from browser. Execution controllable.

### Phase 4: Tier 3 Swarm Visualization — ~2 weeks

**Goal**: Swarm DAG with worker nodes (depends on Swarm backend from PRD.md Phase 5).

| Task | Effort | Description |
|------|--------|-------------|
| Swarm node + Worker children | 4h | `swarm_created` → DAG topology |
| Worker → Plan nesting (reuse Phase 2) | 2h | Each Worker contains a Plan tree |
| DAG view toggle (D3.js dagre) | 8h | Graph layout for Swarm dependencies |
| Worker status badges (role, model) | 2h | Metadata display |
| Swarm cost tracking | 2h | Per-worker + total cost counters |
| Swarm replan animation | 3h | DAG restructure |
| **Total** | **~21h** | |

**Delivers**: Full swarm visualization with DAG view and nested worker trees.

### Phase 5: Polish — ~1 week

| Task | Effort | Description |
|------|--------|-------------|
| Snapshot/late-join (page refresh) | 4h | Daemon serializes current tree state |
| Keyboard shortcuts | 2h | j/k navigate, Enter expand, Esc collapse |
| Search/filter nodes | 3h | Find by tool name, step name, error text |
| Export execution log (JSON) | 1h | Download button |
| Responsive layout (mobile) | 2h | Side panel → bottom panel on narrow screens |
| **Total** | **~12h** | |

### Summary Timeline

| Phase | Duration | Cumulative | Delivers |
|-------|----------|------------|----------|
| Phase 1 (Tier 1 MVP) | 2 weeks | 2 weeks | Live ReAct tree |
| Phase 2 (Tier 2 Plan) | 1 week | 3 weeks | Plan visualization |
| Phase 3 (Interactive) | 1 week | 4 weeks | Permissions + controls |
| Phase 4 (Tier 3 Swarm) | 2 weeks | 6 weeks | Swarm DAG |
| Phase 5 (Polish) | 1 week | 7 weeks | Production-ready |

---

## 11. Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| **Backend WebSocket** | Javalin 6 (embedded) | ~100KB, virtual-thread native, already in PRD.md tech stack |
| **Frontend Framework** | React 19 + TypeScript | Mature ecosystem, `useReducer` perfect for event-driven state |
| **Build Tool** | Vite | Fast dev server, instant HMR, small bundle |
| **Virtualization** | `react-window` | Proven for large lists; tree flattened to list |
| **DAG Rendering** | D3.js + dagre (Phase 4 only) | Standard for directed graph layout |
| **Styling** | Tailwind CSS | Utility-first, dark/light theme, no runtime cost |
| **Icons** | Lucide React | Tree-shakeable, consistent style |
| **Bundle Deployment** | Embedded in daemon JAR as static resources | `aceclaw dashboard` serves from classpath — zero external dependencies |
| **Package Manager** | pnpm | Fast, strict, disk-efficient |
| **Testing** | Vitest + React Testing Library | Unit tests for reducer + component tests |

### Why React, Not "Vanilla HTML/JS + D3.js" (as in PRD.md §4.9.5)?

The main PRD specifies "Vanilla HTML/JS + D3.js" for the Swarm Dashboard. This Frontend PRD **changes that decision** for these reasons:

| Factor | Vanilla JS | React |
|--------|-----------|-------|
| **Tree state management** | Manual DOM diffing — error-prone for deeply nested, rapidly updating trees | `useReducer` + immutable state → predictable, testable |
| **Virtualization** | Must build from scratch | `react-window` off-the-shelf |
| **Component reuse** | Copy-paste | `<ExecutionNodeRow>` recursive composition |
| **Event-driven updates** | Manual `getElementById` + DOM mutation | Reducer dispatch → automatic re-render |
| **Build step** | None (PRD benefit) | Required (Vite) — **but**: output is static HTML/JS bundled into daemon JAR at build time |
| **Bundle size** | ~10KB | ~80KB (React + react-window + Tailwind) — acceptable |

The "no build step" benefit of Vanilla JS is **irrelevant** because the dashboard is bundled into the daemon JAR at compile time, not served from a CDN. The user never runs `npm install` — they run `aceclaw dashboard` and it just works.

---

## 12. Performance Requirements

| Metric | Target | Rationale |
|--------|--------|-----------|
| **WebSocket → tree render** | < 100ms | Events should feel instant |
| **Tree with 1000 nodes** | 60fps scroll | `react-window` virtualizes — only visible rows render |
| **Tree with 5000 nodes** | 60fps scroll | Long swarm runs with many tools |
| **Initial page load** | < 500ms | SPA served from localhost, bundled in JAR |
| **Memory (browser)** | < 100MB for 5000-node tree | Flat node objects, no DOM for off-screen nodes |
| **WebSocket bandwidth** | < 10KB/s typical | JSON events are small (< 500 bytes each) |
| **Reconnect after disconnect** | < 3s (auto-reconnect + snapshot) | Dashboard must survive network blips |
| **Bundle size** | < 200KB gzipped | Embedded in daemon JAR |

---

## 13. Open Questions

| # | Question | Options | Recommendation |
|---|----------|---------|----------------|
| 1 | **Multi-session support?** | (a) Dashboard shows one session (b) Tab per session (c) Session switcher | (a) for v1 — one session. CLI handles session switching. |
| 2 | **Dashboard auto-open?** | (a) Always open on `aceclaw start` (b) Only on `aceclaw dashboard` (c) Auto-open when plan/swarm triggers | (c) — auto-open on plan/swarm, manual for ReAct |
| 3 | **CLI + Dashboard simultaneous permission?** | (a) CLI only (b) Dashboard only (c) First-response-wins | (c) — both can respond, first wins |
| 4 | **Historical execution replay?** | (a) Not in v1 (b) Save event log + replay | (a) for v1 — focus on live. Export JSON for debugging. |
| 5 | **Remote access?** | (a) localhost only (b) Optional TLS + auth for remote | (a) for v1 — `localhost:3141` only. Remote is Phase 4 of main PRD. |
| 6 | **Port configuration?** | (a) Fixed 3141 (b) Configurable in `config.json` | (b) — configurable, default 3141 |
| 7 | **Separate npm project or monorepo?** | (a) `aceclaw-dashboard/` sibling module (b) Embedded in `aceclaw-daemon/src/main/resources` | (a) — separate module, built by Gradle, output copied to daemon resources |

---

## Appendix A: Event-to-Tree Mapping Quick Reference

```
Daemon Event                    → Frontend Tree Action
──────────────────────────────────────────────────────────────
stream.turn_started             → Add Turn node (running)
stream.tool_use                 → Add Tool child to active Turn (running)
stream.tool_completed           → Update Tool node (completed/failed)
stream.text                     → Append text to active Turn's text child
stream.thinking                 → Update thinking indicator (collapsible)
stream.heartbeat                → Pulse animation on active node
stream.error                    → Mark active node failed
stream.usage                    → Update header counters
stream.subagent.start           → Add SubAgent node (running)
stream.subagent.end             → Complete SubAgent node
stream.compaction               → Show compaction badge on session
stream.plan_created             → Add Plan + pending Step children
stream.plan_step_started        → Activate Step node (running)
stream.plan_step_completed      → Complete Step node (completed/failed)
stream.plan_completed           → Complete Plan node
stream.plan_replanned           → Strikethrough old steps, add new ones
stream.plan_escalated           → Mark Plan failed with reason
permission.request              → Add Permission node (awaiting_input)
stream.gate                     → Update Permission node (auto-decision)
stream.cancelled                → Mark session cancelled
stream.budget_exhausted         → Mark session budget-exceeded
stream.swarm_created            → Add Swarm + Worker placeholders + DAG
stream.worker_started           → Activate Worker node (running)
stream.worker_completed         → Complete Worker node
stream.swarm_replanned          → Restructure DAG with animation
stream.swarm_completed          → Complete Swarm node
snapshot                        → Replace entire tree (late-join)
```

## Appendix B: File Structure

```
aceclaw-dashboard/              ← new Gradle module
├── package.json
├── pnpm-lock.yaml
├── vite.config.ts
├── tsconfig.json
├── index.html
├── src/
│   ├── main.tsx                       # Entry point
│   ├── App.tsx                        # Root component
│   ├── hooks/
│   │   ├── useExecutionStream.ts      # WebSocket + reducer
│   │   └── useAutoScroll.ts           # Follow active node
│   ├── state/
│   │   ├── types.ts                   # ExecutionNode, TreeAction, etc.
│   │   ├── reducer.ts                 # executionTreeReducer
│   │   ├── eventMapper.ts            # JSON-RPC event → TreeAction
│   │   └── treeUtils.ts              # findActiveNode, flattenTree, etc.
│   ├── components/
│   │   ├── Header.tsx                 # Session info, cost, controls
│   │   ├── ExecutionTree.tsx          # Virtualized tree (react-window)
│   │   ├── ExecutionNodeRow.tsx       # Single node row (recursive visual)
│   │   ├── StatusIcon.tsx             # Status → icon/color mapping
│   │   ├── LiveTimer.tsx              # Running duration counter
│   │   ├── PermissionPrompt.tsx       # Inline [Allow]/[Deny] buttons
│   │   ├── DetailPanel.tsx            # Tool input/output side panel
│   │   ├── EventLog.tsx               # Bottom event log
│   │   ├── DagView.tsx                # D3.js DAG for Swarm (Phase 4)
│   │   └── TreeLines.tsx              # Unicode tree-drawing lines
│   ├── styles/
│   │   └── globals.css                # Tailwind + theme variables
│   └── __tests__/
│       ├── reducer.test.ts            # Pure function tests for reducer
│       ├── eventMapper.test.ts        # Event → Action mapping tests
│       └── treeUtils.test.ts          # flattenTree, findActive, etc.
├── build.gradle.kts                   # Gradle build: pnpm build → copy to daemon resources
└── README.md
```

---

*This PRD defines the AceClaw Execution Dashboard as a standalone frontend that unifies observability across all three execution tiers. It reuses 100% of existing daemon events (Tier 1 + Tier 2) and defines 7 new events for Tier 3 (Swarm). The fractal tree model means building Tier 1 visualization automatically provides the rendering primitives for Tier 2 and Tier 3.*
