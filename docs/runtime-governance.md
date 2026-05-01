# Runtime-Level Governance

> AceClaw's positioning: capability governance lives in the runtime, not in the protocol.

## The three layers governance can live at

When an agent runs a tool, makes a network call, or touches the filesystem, *something* has to decide whether that's allowed. That decision can be enforced at three very different layers:

| Layer | Where it lives | What it can decide | Failure mode |
|---|---|---|---|
| **Protocol** | MCP server config, tool manifest, allowlist file | "Is this MCP server installed? Is this tool name listed?" | Per-server static allow/deny, no behavioral context. A tool that *was* allowed can do anything inside its execution. |
| **Connector** | Per-tool wrapper, per-adapter middleware | "Is this argument-shape allowed for this specific tool?" | Each adapter re-implements its own checks. Bash gets one policy, MCP another, the browser tool a third. Cross-cutting rules ("never write to `.env`") leak. |
| **Runtime** | Inside the agent loop itself, in the daemon that runs the agent | "Is this capability — regardless of where it came from — allowed for this session, this user, this plan step, right now?" | One policy, every adapter. The agent loop is the policy boundary. |

Most agent platforms today govern at the protocol or connector layer. AceClaw bets that the runtime is the right layer.

## Why the runtime is the right layer

Three concrete reasons:

1. **Capabilities aren't tools.** "Read this file" is a capability. It can come from `read_file`, from a `bash cat`, from an MCP server's `fs.read`, from a browser-tool reading a downloaded file, or from a sub-agent doing the same. A protocol-level policy that allows `read_file` but denies `bash cat` is theatre — same capability, different name.
2. **Decision context lives in the loop.** Whether a `bash rm -rf /tmp/build` is OK depends on which plan step the agent is in, what it just did, what permission mode the session is running, whether the user already approved similar operations this session. None of that context exists at the protocol layer. All of it exists in the agent loop.
3. **Enforcement requires runtime knowledge.** Sandbox boundaries (process isolation, filesystem jail, network egress rules) need to be applied to a *running* execution. Protocol-layer rules can only refuse to start it.

## Where AceClaw is today

The runtime governance scaffolding is real and shipping. The capability-abstraction layer that would unify it is not yet there. Honest current state:

| Capability | Status | Where it lives |
|---|---|---|
| Sealed permission decisions (`Approved` / `Denied` / `NeedsUserApproval`) | ✅ shipped | `aceclaw-security` |
| 4-tier risk model (READ / WRITE / EXECUTE / DANGEROUS) | ✅ shipped | `PermissionLevel` |
| Per-session approval scope ("don't ask again") | ✅ shipped | `PermissionManager` |
| Permission modes (normal / accept-edits / plan / auto-accept) | ✅ shipped | `PermissionMode` |
| Sub-agent privilege isolation (filtered tool registry) | ✅ shipped | `SubAgentPermissionChecker` |
| Live execution tree — every decision traceable end-to-end | ✅ shipped | `aceclaw-dashboard` (#430 epic, #459) |
| Cron runs governed by the same path as user sessions | ✅ shipped | #459 cron-as-session |
| HMAC-signed memory entries (signed audit chain on the memory tier) | ✅ shipped | `MemorySigner` |
| Cross-adapter capability abstraction (one model spans MCP, bash, browser, skill, sub-agent) | 🚧 **not yet** | — |
| Unified policy engine (one decision rule applies regardless of adapter origin) | 🚧 **not yet** | — |
| OS-level enforcement (Seatbelt / bubblewrap / equivalent for bash + subprocess + MCP stdio) | 🚧 **not yet** | — |
| Capability-level audit schema (every capability use is signed, queryable, replayable) | 🚧 **partial** | (only memory tier today) |
| Eval / regression harness for governance behavior under adversarial prompts | 🚧 **not yet** | — |

**Today AceClaw governs at the runtime layer for its built-in tools and primitive operations. It does not yet have a unified capability abstraction across every adapter.** That's the gap.

## What "complete" runtime governance looks like

The target shape is a single normalised capability request that every code path — built-in tool, MCP call, bash subprocess, skill-system invocation, browser tool, sub-agent — must funnel through:

```java
record CapabilityRequest(
    Actor actor,                 // session, sub-agent id, cron job id
    Adapter adapter,             // MCP / CLI / Bash / Skill / Browser / Plugin
    Operation operation,         // READ / WRITE / EXECUTE / NETWORK / MEMORY / API_CALL
    ResourceRef resource,        // file path, URL, MCP method, etc.
    DataFlow dataFlow,           // ingress, egress, both
    PermissionLevel risk,
    Provenance provenance,       // which plan step, which iteration, who chained here
    SessionId sessionId,
    Optional<PlanStepId> planStepId
) {}
```

Every adapter then becomes a thin shim that produces a `CapabilityRequest` and submits it to the runtime. The pipeline:

```
Adapter (MCP / Bash / Skill / Browser / Sub-agent / …)
    │
    ▼
CapabilityNormalizer   — one shape, regardless of origin
    │
    ▼
PolicyEngine           — one rule set, evaluated once
    │
    ▼
ApprovalBroker         — same surface for CLI prompt and dashboard panel
    │
    ▼
SandboxExecutor        — OS-level boundary (Seatbelt/bubblewrap), runs the action
    │
    ▼
AuditLog               — signed, replayable, queryable
    │
    ▼
EventBus → Dashboard   — every capability use is a visible event
    │
    ▼
MemoryGate             — what got written to memory, why, and signed by whom
```

When the runtime can say "no capability use bypasses this pipeline, regardless of which adapter originated it", that's runtime-level governance.

## What this is *not*

- **Not protocol governance.** "We curate which MCP servers users can install" stops at the protocol boundary. Inside an MCP server, anything goes. AceClaw doesn't treat an MCP server's manifest as the policy boundary.
- **Not connector governance.** "Each tool has its own permission check" leaks: cross-cutting policies (no `.env` reads, no network during plan-execution mode, no destructive ops in `plan` mode) end up duplicated across N adapters and inevitably drift.
- **Not just runtime *approval*.** Approving a bash command and then letting the OS process do whatever it wants is governance theatre. Real runtime governance requires *enforcement* — sandbox boundaries that survive after the approval.

## The honest positioning

> AceClaw is an attempt to move agent governance from the protocol layer into the runtime.

The harness already governs the agent loop, the permission decisions, the sub-agent privilege boundary, the memory audit chain, and the live event surface. The next bar — unified capability abstraction across every adapter, plus OS-level sandbox enforcement — is the work ahead. The roadmap in [`visual-harness-roadmap.md`](visual-harness-roadmap.md) describes the visualisation side; the capability-pipeline side is tracked in its own epic.

This positioning is deliberately not "AceClaw solved runtime governance". The point is the *direction*: a daemon-shaped agent runtime where every capability use, regardless of origin, enters one pipeline, gets one decision, runs in one sandbox, and produces one signed audit trail.

Most agent platforms today are still governing at protocol or connector level. Moving that decision into the runtime is the bet.
