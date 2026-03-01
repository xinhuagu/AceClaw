# ADR-0001: Agent Harness Session Concurrency and Learning-Loop Integration

- Status: Proposed
- Date: 2026-03-01
- Decision Owners: AceClaw CLI/Core
- Related: PRD 4.5/4.5.1/4.5.2, Continuous-Learning Plan/Governance/Operations

## Context

Current CLI execution uses per-task dedicated connections and virtual threads, but tasks are submitted under a single interactive `sessionId`. In practice, if daemon scheduling is session-serialized, users observe task blocking despite multi-connection plumbing.

At the same time, PRD expectations require:

- deterministic resume routing with strong isolation
- long-task continuity in the main session
- future harness capability for self-repair and self-learning

The design question is how to add real parallel progress for long tasks without breaking PRD session semantics.

## Problem Statement

We need a harness model that:

1. keeps the main session as the authoritative conversation and resume anchor
2. allows safe parallel execution for decomposable work
3. feeds online repair outcomes into the existing continuous-learning governance and quality gates
4. avoids conflicts with PRD isolation constraints

## Decision

Adopt a **Dual-Channel Session Model**:

1. **Lead Session (authoritative)**
- The existing interactive session remains the only source of truth for:
  - user-visible reasoning/output
  - `/continue` binding and resume routing
  - final acceptance and task closure

2. **Worker Sessions (isolated, optional)**
- Harness may create additional sessions only for explicitly decomposed sub-tasks (research/verification/isolated execution).
- Worker sessions do not mutate lead-session transcript directly.
- Worker outputs are returned as structured artifacts/evidence and merged by lead session.

3. **Session Spawning Policy (default conservative)**
- Do not spawn worker sessions for simple or tightly stateful flows.
- Spawn only when all conditions hold:
  - sub-task is decomposed and labeled independent
  - expected output can be represented as artifact/result summary
  - permission scope can be constrained per worker

4. **Resume Invariant**
- `/continue` keeps binding to lead session checkpoints by default.
- Worker resumes are internal harness operations and must not override user-facing routing priority.

5. **Learning-Loop Integration**
- Introduce harness outcome records for each worker/repair attempt:
  - `failure_type`, `root_cause`, `repair_action`, `repair_result`, `latency_ms`, `token_cost`, `source_session`
- Write these as candidate evidence into existing continuous-learning pipeline (shadow->canary->active) instead of introducing a new release path.

## Why This Does Not Conflict with PRD

This ADR preserves PRD contracts:

- session continuity for user interaction remains intact (lead session)
- resume routing isolation remains deterministic and user-facing
- multi-agent/session parallelism is used as an execution optimization, not as a replacement for core session semantics

## OpenClaw / Claude Code Experience We Explicitly Reuse

From orchestration research, we adopt:

1. **Single authoritative loop + delegated isolation**
- Keep one master/lead context; delegated units run in isolated contexts.

2. **Depth-1 delegation discipline**
- Prevent uncontrolled nesting of delegated tasks by policy.

3. **Artifact-first merge**
- Delegated execution returns result artifacts, not raw transcript blending.

4. **Background task observability**
- Surface waiting/blocked/running state explicitly in UI/status, avoid silent stalls.

5. **Deterministic claim/update semantics for shared work**
- For future team/task-list flows, enforce atomic claim/update and conflict checks.

## OpenClaw Lessons We Explicitly Avoid

1. **Permission bleed across sessions/agents**
- Approval scope must be session-bound; worker approvals cannot elevate lead session.

2. **Unbounded delegated privilege lifetime**
- Any pre-approval requires scope + TTL + revocation.

3. **Resume artifact tampering risk**
- Worker transcript/artifact loading should include integrity checks before reuse.

4. **Unvetted cross-session learning writes**
- Harness-generated learnings must go through existing validation/replay/rollback governance.

## Consequences

Positive:

- user-facing behavior remains compatible with current PRD semantics
- true parallel progress becomes possible for decomposable long tasks
- self-repair evidence can be measured and governed through existing learning gates

Tradeoffs:

- additional complexity in session orchestration and artifact merge contracts
- stricter permission and audit handling required for worker sessions

## Non-Goals

- replacing current `/continue` routing policy
- exposing raw worker-session conversation as first-class user history
- introducing independent learning release logic outside continuous-learning governance

## Rollout Plan

Phase 0 (current):
- Keep single-session submission; improve labeling to avoid over-promising concurrency.

Phase 1:
- Add harness metadata model (`lead_session_id`, `worker_session_id`, `parent_task_id`, `artifact_ids`).
- Add worker-spawn policy flag (default off).

Phase 2:
- Enable worker sessions for read-mostly/research tasks.
- Persist harness outcome records and connect to candidate evidence writeback.

Phase 3:
- Expand to controlled repair actions (retry/replan/fallback) with quality-gate enforcement.

## Operational Guardrails

- Kill switch: disable worker-session spawning at runtime.
- Per-worker token and time budget.
- Per-worker permission scope and automatic cleanup.
- Full audit trail for worker spawn/merge/rollback actions.

## Acceptance Criteria

1. User-visible `/continue` behavior remains unchanged for baseline tasks.
2. At least one decomposed long task demonstrates parallel speedup with no resume regression.
3. Harness repair outcomes are captured and appear in learning evidence artifacts.
4. Governance gates can block harmful promoted repair patterns.

## References

- `research/openclaw-agent-orchestration.md`
- `research/architecture-agent-teams.md`
- `research/security-review.md`
- `docs/continuous-learning-plan.md`
- `docs/continuous-learning-governance.md`
- `docs/continuous-learning-operations.md`
