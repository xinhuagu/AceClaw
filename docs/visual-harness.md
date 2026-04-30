# Visual Agent Harness

Most agent CLIs scroll past you. AceClaw streams every event to a browser dashboard that renders the run as a live, navigable tree.

<p align="center">
  <img src="img/aceclaw-dashboard.gif" alt="AceClaw dashboard — real-time execution tree visualization" width="820">
</p>

## What you see

| Surface | Source |
|---------|--------|
| Session, turns, ReAct iterations | `stream.session_started`, `stream.turn_started`, `stream.thinking` |
| Tool calls (parallel siblings collapse cleanly) | `stream.tool_use`, `stream.tool_completed` |
| Streaming narration / final response | `stream.text` deltas, anchored to the iteration's thinking node |
| Plan skeleton with per-step status | `stream.plan_created`, `stream.plan_step_started/completed`, `stream.plan_replanned` |
| Sub-agent fan-outs | `stream.subagent.start/end` |
| Permission requests, approvable from the browser | `permission.request` → inline panel → `permission.response` |
| Token usage, compaction events, stop reasons | `stream.usage`, `stream.compaction`, turn `stopReason` |

## How it's wired

```
Daemon (Java)                                  Dashboard (React)
─────────────                                  ─────────────────
StreamingAgentHandler                          useExecutionTree
   │  emits AceClawEvent (sealed type)            │  WebSocket subscribe
   ▼                                              ▼
AceClawEventBus  ──►  WebSocketBridge  ──►  treeReducer (pure fn)
                       (envelope:                 │
                        eventId,                  ▼
                        sessionId,            ExecutionTree
                        receivedAt)              (immutable, structurally shared)
                                                  │
                                                  ▼
                                              dagre layout → SVG tree + panels
```

## Capabilities

- **Multi-session sidebar** — one daemon, many concurrent sessions; the sidebar lists them and the URL preserves the selection across reload.
- **Snapshot + replay reconnect** — a tab opened mid-execution issues `snapshot.request`, gets the full event history back, and dedupes against the live stream via a monotonic `eventId` watermark. No "I missed the first half of the run."
- **First-response-wins permissions** — CLI and browser race to answer; the daemon resolves whichever lands first via a `ConcurrentHashMap` registry, with a sessionId guard so a tab on session B can't approve session A's tool calls. When the dashboard wins, the daemon also pushes a `permission.cancelled` notification back to the originating CLI so its TUI prompt dismisses automatically with "Resolved via dashboard" instead of waiting on stdin.
- **Inline permission panel** — paused tool nodes anchor a floating Approve/Deny card with `A`/`D` keyboard shortcuts and a 120 s countdown ring; if the CLI answers first, the panel briefly renders "Approved/Denied via CLI" before tearing itself down.
- **Pure reducer, immutable tree** — every event flows through one pure function. The same reducer drives live rendering and snapshot replay, so the two paths can't diverge.

## Run it locally

```bash
# 1. Start the daemon with the WebSocket bridge enabled
#    (~/.aceclaw/config.json):
# {
#   "webSocket": { "enabled": true, "port": 3141, "allowedOrigins": ["http://localhost:5173"] }
# }
aceclaw-restart

# 2. Run the dashboard
cd aceclaw-dashboard
pnpm install   # or npm install
pnpm dev       # http://localhost:5173/

# 3. Pick a session in the sidebar, or open with a known id:
#    http://localhost:5173/?session=<id>&ws=ws://localhost:3141/ws
```

The dashboard lives under [`aceclaw-dashboard/`](../aceclaw-dashboard/). It's a React 19 + Vite + Tailwind 4 app with a dagre-driven SVG tree and framer-motion node animations; reducer and hook layers are unit-tested with Vitest.

## What's next

[`visual-harness-roadmap.md`](visual-harness-roadmap.md) sketches the four directions the harness is heading: an **agent debugger** (pause / step / replay / diff), a **decision graph** (plan + reasoning + counterfactual tools), a **permission dashboard** (auditable decisions across sessions), and a **learning viewer** (the self-learning loop made legible). Each is large enough to spin out its own PRD when we pick it up.
