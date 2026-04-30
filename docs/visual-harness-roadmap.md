# Visual Agent Harness — Roadmap

The Tier 1 dashboard ([`docs/visual-harness.md`](visual-harness.md)) makes a long-running agent *visible*. This document sketches the next four directions that make it *legible, debuggable, and learnable* — what we want to build, why it matters, and what each one needs from the daemon to land.

These are **development intents**, not commitments. Each direction is large enough to warrant its own PRD when we pick it up; this file is the index that says where the visual surface is going.

---

## 1. Agent Debugger  *(strongly recommended)*

> Chrome DevTools for agents. Pause, step, inspect, replay, diff.

The runtime tree shows you *what happened*. The debugger lets you *get inside it*.

**Capabilities**

- **Step-by-step execution** — `pause` / `step over (next event)` / `step into (next ReAct iteration)` / `continue`. Pausing freezes the agent at a tool boundary; stepping releases exactly one event.
- **State inspection** — at any paused point, surface the full LLM context window, the tool registry's current view of the workspace, the permission-policy decision trace, and the active task plan. "What does the agent believe right now?"
- **Replay** — re-run a recorded session deterministically, locally, with no LLM calls. Backed by a captured event stream + tool-call results. Same reducer, same tree.
- **Diff between runs** — pin run A, run a new attempt, see node-by-node where they diverged (different tool, different argument, different number of iterations). The signal you need to know whether a prompt change actually moved the needle.

**Why it matters**

Long-running agents fail in subtle ways. Today the only debugging surface is "read the logs and infer." A debugger turns "the agent did something weird at iteration 7" into a reproducible artifact — pause there, inspect the context, edit the prompt, replay to see if it sticks. This is the difference between hoping a fix worked and knowing it did.

**Prerequisites**

- Daemon-side `agent.pause` / `agent.resume` / `agent.step` JSON-RPC methods, gated by a debug flag (so it can't accidentally fire in production sessions).
- Event stream is already complete and replayable — the reducer was [explicitly designed pure](visual-harness.md#how-its-wired) for exactly this. Replay needs a recorded `tool_completed` payload archive (already on disk for the current session; needs a retention policy for older ones).
- Diff needs a stable node-identity scheme that survives reordering — synthetic-id minting (`nextSyntheticId`) already gives us this.

---

## 2. Decision Graph  *(critical)*

> The plan, the reasoning, and the *paths not taken*.

The execution tree shows the *path the agent took*. The decision graph shows the *space it was choosing from* — which is where most agent failures live.

**Capabilities**

- **Plan tree** — the Plan/Replan structure rendered as a graph instead of a flat list: which step depends on which, where a replan branched, what got dropped. ([`docs/plan-replan.md`](plan-replan.md) describes the underlying state machine; the graph makes it navigable.)
- **Reasoning path** — for each tool call, the linked thinking node + the snippets of context that justified it. "Why did the agent reach for `bash` here?" answerable in two clicks.
- **Counterfactuals: why tool A not tool B** — when the LLM stop reason is `tool_use`, the response actually carries an ordered list of candidate tool calls (and discarded ones, when extended thinking is on). Surface them. Hover a tool node → see the alternatives the model considered, ranked by the model's own scoring where available.

**Why it matters**

Agents that look correct in the happy path can be deeply wrong about *why*. A plan that picked the right tool for the wrong reason is a latent bug — it'll work on this prompt and fail on the next. Making reasoning legible turns "trust the agent" into "audit the agent."

**Prerequisites**

- Daemon needs to retain the discarded tool-use candidates from the LLM stream (currently dropped after the chosen one is dispatched). Cheap to keep — they're already in the `MessageDelta` payload.
- A linkage from `stream.tool_use` back to the `stream.thinking` block that produced it. The reducer already maintains `currentThinkingId` for tool anchoring; that pointer is the seed for "show me the reasoning."
- Plan replan history is already typed (`stream.plan_replanned`) but not graphed — the layout pass needs a "decision-graph mode" alongside the existing ReAct-tree mode.

---

## 3. Permission Dashboard

> Auditable permission decisions across sessions and policies.

Inline approve/deny is great in the moment. The aggregate view tells you whether your policy is sane.

**Capabilities**

- **Tool-call ledger** — every tool invocation across recent sessions, with: tool name, args (truncated), permission decision (auto-approved / user-approved / denied / policy-denied), latency, outcome (success / error / timeout).
- **Denial reasons** — when the permission system rejects a call, surface *which rule* rejected it (default policy, session-scoped revoke, hook veto) and the policy fragment. "Why was this denied?" answerable without reading server logs.
- **Policy effectiveness** — % of calls auto-approved, % escalated to user, % denied. Per tool, per session, over time. The signal that tells you when a policy is too strict (operator fatigue from constant prompts) or too loose (everything sails through).
- **Per-session scope view** — which tools have an active session-scoped grant ("Don't ask again"), when it was granted, who can revoke it.

**Why it matters**

Permissions are the daemon's safety surface. Today they're decided per-call and forgotten. Aggregating them is how you catch a tool that's quietly being denied 40% of the time, or a session-scope grant that's been live for three days across unrelated tasks. Same data, just made queryable.

**Prerequisites**

- A `PermissionDecisionLog` (already partially captured in `aceclaw-security`) that's persisted, indexed by session + timestamp + tool, and queryable from the daemon.
- A new daemon endpoint `permissions.history` (paginated) — the dashboard already speaks the WebSocket bridge for live data, but historical aggregate queries should go over a dedicated request/response surface so they don't compete with live event throughput.

---

## 4. Learning Viewer

> Make the self-learning loop legible.

The [self-learning system](self-learning.md) generates typed insights and reuses patterns silently. That silence is the problem — operators can't tell whether learning is helping, harming, or sleeping.

**Capabilities**

- **Insight stream** — every `insight.generated` event surfaced as a card with: which detector fired, what behavior triggered it, the typed insight body, the confidence score, and a "what would change" preview if the insight were applied.
- **Pattern reuse heatmap** — which patterns get matched and reused, how often, in which session contexts. Surfaces both the wins ("this debugging pattern saves 4 turns on average") and the duds ("this insight has matched 200 times and never improved outcome").
- **Insight provenance** — for any insight currently in the prompt, trace it back to the session(s) that generated it. Closes the loop: an insight that's misfiring should be traceable to the original incident.
- **Manual curation** — promote / demote / delete an insight from the UI. Today this only happens via the consolidation pass; surfacing manual controls makes the system inspectable in production without an SSH session.

**Why it matters**

Self-learning is the long-term lever that separates AceClaw from one-shot agents. But a learning system you can't inspect is a black box that quietly drifts. Putting the loop on screen converts "the agent is getting smarter (we hope)" into "here's what it learned this week, here's what it reused, here's what's worth keeping." That's the difference between a feature and a system you can operate.

**Prerequisites**

- The insight pipeline already emits typed events (`AceClawEvent.PatternEvent` / detector outputs); they're not currently bridged to the WebSocket multiplexer. Adding them to the bridge is a one-line registration plus dashboard reducer support.
- Provenance needs an insight → originating-session-ids index in memory storage. Schema lives in [`docs/memory-system-design.md`](memory-system-design.md); not yet exposed.

---

## Sequencing notes

These four don't have to land in order, but there are dependencies worth flagging:

- **Debugger before everything else.** Pause/step/replay infrastructure is the lever every other surface benefits from — the decision graph is far more useful when you can pause inside it; the permission dashboard becomes a debugger view of "stop on next denial"; the learning viewer can replay an insight's effect by stepping the same prompt with and without it applied.
- **Decision graph and permission dashboard share an audit-log substrate.** Both want a queryable history of "what happened, when, why." Building one persistence layer for both is cheaper than two.
- **Learning viewer is last** because it depends on the others — provenance is most useful when you can replay the originating session, and pattern-effectiveness is most measurable when you can diff runs with and without an insight.

A fair MVP slice in roughly two-quarter chunks:

1. **Q+1**: debugger MVP (pause / step / event replay), decision graph (plan tree + thinking → tool linkage)
2. **Q+2**: permission dashboard (history + denial reasons), learning viewer (insight stream + manual curation)
3. **Q+3+**: counterfactual tool ranking, insight provenance, run-diff, replay-with-modified-insight

---

*Each section above is a candidate for a standalone PRD when we pick it up. The principle stays the same as Tier 1: the same daemon events drive everything, the reducer stays pure, and nothing in the harness invents state the runtime doesn't already own.*
