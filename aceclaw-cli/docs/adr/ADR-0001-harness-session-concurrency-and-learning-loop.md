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

5. **Mapping to Existing Types**

The dual-channel model maps to existing AceClaw types as follows:

**Lead Session:**
- `SessionManager` (`aceclaw-daemon`) owns the lifecycle of all `AgentSession` instances via a `ConcurrentHashMap<String, AgentSession>`.
- `AgentSession` holds the authoritative `List<ConversationMessage>` (user/assistant/system messages), the `projectPath`, and `active` flag. The lead session is the one created by `SessionManager.createSession(projectPath)` during interactive CLI use.
- `StreamingAgentHandler.handlePrompt()` orchestrates each turn: it resolves the session, builds a permission-aware `ToolRegistry`, runs the `StreamingAgentLoop`, and streams results via `StreamContext`.
- `StreamContext` (interface: `sendNotification()`, `readMessage()`) provides the bidirectional channel between daemon and CLI. The lead session's `StreamContext` is the user-facing notification path.

**Worker Session:**
- `SubAgentRunner.runInBackground(SubAgentConfig, prompt, CancellationToken)` is the existing mechanism for spawning isolated execution. It returns a `BackgroundTask` tracked by `taskId`.
- Each worker runs with a filtered `ToolRegistry` (derived from `parentRegistry`) and an independent `AgentLoopConfig` (bounded by `SubAgentConfig.maxTurns`).
- Worker sessions do not currently create a full `AgentSession` in `SessionManager`. The ADR proposes adding ephemeral `AgentSession` instances with a `parentSessionId` field so that workers appear in session inventories while preserving lead-session authority.
- `SubAgentResult` (record: `taskId`, `text`, `Turn`, `SubAgentTranscript`) is the structured return from worker execution, consumed by the lead session as a merge artifact.

**Notification Bridging:**
- Worker completions are surfaced via `stream.subagent.start` / `stream.subagent.end` notifications (already implemented in `StreamingNotificationHandler.onSubAgentStart/onSubAgentEnd`).
- The lead session's `StreamContext.sendNotification()` bridges worker results to the CLI. Worker sessions themselves do not hold a `StreamContext` -- they report through the `StreamEventHandler` callback chain.

6. **Artifact Merge Contract**

Worker outputs flow into the lead session as structured artifacts rather than raw transcript blending.

**`WorkerArtifact` sealed interface (proposed):**
```java
public sealed interface WorkerArtifact {
    String workerId();
    Instant completedAt();

    record TextSummary(String workerId, Instant completedAt,
                       String summary, TokenUsage usage) implements WorkerArtifact {}
    record FileChange(String workerId, Instant completedAt,
                      Path filePath, String description) implements WorkerArtifact {}
    record ErrorReport(String workerId, Instant completedAt,
                       String errorType, String message,
                       String stackTrace) implements WorkerArtifact {}
}
```

**Merge Protocol:**
1. Worker completes -> `SubAgentResult` returned (contains `text`, `Turn`, `SubAgentTranscript`).
2. Lead session converts `SubAgentResult` into one or more `WorkerArtifact` instances.
3. Each artifact is appended to the lead session's conversation as a `ConversationMessage.System` message (e.g., `"[Worker:explore] Completed: <summary>"`).
4. The lead LLM sees worker results as system context in subsequent turns, not as injected assistant messages.

**File Conflict Resolution (phased):**
- Phase 1: Workers are read-only (`SubAgentConfig.allowedTools` restricted to `read_file`, `glob`, `grep`). No write conflicts possible.
- Phase 2: Workers operate on disjoint file scopes, enforced by `SubAgentConfig.allowedTools` filtering and path-based permission constraints.
- Phase 3: Git-style merge detection -- if multiple workers modify the same file, the lead session receives conflict markers and the LLM resolves them.

7. **Relationship to TaskPlanner ExecutionStrategy**

Worker sessions integrate with the existing `TaskPlanner` -> `SequentialPlanExecutor` pipeline:

- `PlannedStep` currently contains `stepId`, `name`, `description`, `requiredTools`, `fallbackApproach`, and `status`. The ADR proposes adding an `ExecutionStrategy` field:
  ```java
  public enum ExecutionStrategy { INLINE, SUBAGENT }
  ```
  `INLINE` (default) executes the step in the lead session's `StreamingAgentLoop` as today. `SUBAGENT` delegates to `SubAgentRunner`.

- `SequentialPlanExecutor.execute()` iterates `plan.steps()` and accumulates conversation history across steps. For `SUBAGENT` steps, the executor would:
  1. Build a `SubAgentConfig` from `step.requiredTools()` and the step description.
  2. Call `SubAgentRunner.runWithTranscript(config, buildStepPrompt(...), handler, cancellationToken)`.
  3. Convert the `SubAgentResult` into a `StepResult` and a `WorkerArtifact.TextSummary` merged into conversation history.

- This maps directly to PRD 4.5.2's `Subagent` execution strategy concept. The `SequentialPlanExecutor` remains the orchestrator; only the dispatch per step changes.

- Future `ParallelPlanExecutor` (ADR-0003) would use `StructuredTaskScope` to run multiple `SUBAGENT` steps concurrently, with the same artifact merge contract.

8. **Learning-Loop Integration (Scoped)**

Full harness outcome records (structured failure/repair tracking with replay validation) are deferred to ADR-0002. For the dual-channel session model, the existing learning infrastructure is sufficient:

- `SubAgentResult` already captures `Turn` (which includes `Turn.totalUsage()` for token accounting) and `SubAgentTranscript` for audit.
- Worker summaries merged into the lead session's `ConversationMessage` history are visible to `SelfImprovementEngine.analyze(Turn, List<ConversationMessage>, Map<String, ToolMetrics>)` -- the engine sees worker outcomes as system messages in the session history it already processes.
- `ErrorDetector`, `PatternDetector`, and `FailureSignalDetector` can extract insights from worker error reports without additional plumbing.
- No new learning release path is introduced; all insights flow through the existing candidate evidence pipeline (shadow -> canary -> active).

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
- No code changes required.

Phase 1 -- Session Hierarchy and Read-Only Workers:
- Add `parentSessionId` field to `AgentSession`; update `SessionManager.createSession()` to accept an optional parent.
- Add `ExecutionStrategy` enum (`INLINE`, `SUBAGENT`) and field to `PlannedStep`.
- Introduce `WorkerArtifact` sealed interface in `aceclaw-core`.
- Introduce `WorkerBudget` record in `aceclaw-core` with enforcement in `SubAgentRunner`.
- Add `SUBAGENT` dispatch branch in `SequentialPlanExecutor.execute()` for read-only workers (`SubAgentConfig.allowedTools` = `[read_file, glob, grep]`).
- Worker-spawn policy flag (default off) in `StreamingAgentHandler`.

Phase 2 -- Write-Capable Workers and Artifact Merge:
- Expand `SubAgentConfig.allowedTools` to include `write_file` / `edit_file` with disjoint file scope enforcement.
- Implement `WorkerArtifact` merge into lead session `ConversationMessage.System` messages.
- Enable `SelfImprovementEngine` to tag insights originating from worker sessions via `parentSessionId`.
- Harness outcome records (ADR-0002) connect to candidate evidence writeback.

Phase 3 -- Parallel Execution and Repair:
- Implement `ParallelPlanExecutor` using `StructuredTaskScope` for concurrent `SUBAGENT` steps (ADR-0003).
- Git-style file conflict detection and resolution for overlapping worker scopes.
- Controlled repair actions (retry/replan/fallback) with quality-gate enforcement.

## Operational Guardrails

### Token Budget Enforcement

Each worker session operates under a `WorkerBudget` (proposed record):

```java
public record WorkerBudget(
        int maxOutputTokens,      // per-worker output token cap (default: 4096)
        int maxTotalTokens,       // per-worker total token cap including input (default: 32768)
        int maxTurns,             // per-worker turn limit (maps to SubAgentConfig.maxTurns)
        Duration maxWallTime,     // per-worker wall-clock timeout (default: 5 min)
        int maxConcurrentWorkers  // max workers active simultaneously per lead session (default: 3)
) {}
```

**Enforcement points:**
- `maxTurns` is already enforced by `SubAgentConfig.maxTurns` -> `AgentLoopConfig.maxIterations`.
- `maxOutputTokens` / `maxTotalTokens` are enforced by the `StreamingAgentLoop` via `AgentLoopConfig` parameters, checked after each LLM response.
- `maxWallTime` is enforced by `SubAgentRunner.runInBackground()` via the `CancellationToken` timeout mechanism.
- `maxConcurrentWorkers` is enforced by the lead session's `StreamingAgentHandler` before dispatching new workers (semaphore-guarded).

### Context Window Cost Analysis

Each worker session consumes a separate context window. Approximate per-worker token costs:

| Component | Tokens (approx) |
|---|---|
| System prompt (filtered) | 800 -- 2,000 |
| Step prompt + plan context | 500 -- 1,500 |
| Tool definitions (filtered subset) | 300 -- 1,000 |
| Conversation turns (multi-turn worker) | 1,000 -- 8,000 per turn |
| **Total per worker (typical)** | **3,000 -- 32,000** |

**Mitigation strategies:**
- **Prompt caching**: Worker system prompts share a common prefix with the lead session; Anthropic's prompt caching reduces redundant input token costs.
- **Smaller models for workers**: `SubAgentConfig.model` can specify a smaller model (e.g., Haiku) for research/verification tasks while the lead session uses Opus/Sonnet.
- **Budget caps**: `WorkerBudget.maxTotalTokens` provides a hard ceiling. Workers that approach the limit receive a "wrap up" system message before forced termination.
- **Depth-1 discipline**: Workers cannot spawn sub-agents (the `task` tool is always excluded from `SubAgentRunner.createFilteredRegistry()`), preventing exponential context multiplication.

### Other Guardrails

- **Kill switch**: Runtime flag in `StreamingAgentHandler` to disable worker-session spawning globally (`workerSpawnEnabled`, default `false` in Phase 0/1).
- **Permission scope**: Each worker inherits a restricted `PermissionManager` scope. Worker approvals cannot elevate lead-session permissions. Write permissions require explicit `SubAgentConfig.allowedTools` inclusion.
- **Automatic cleanup**: `SubAgentRunner.backgroundTasks` already applies `BG_CLEANUP_AGE` (30 minutes) for stale tasks. Worker `AgentSession` instances are deactivated and removed from `SessionManager` on completion or timeout.
- **Audit trail**: Worker spawn, artifact merge, and rollback events are emitted as `AceClawEvent` instances (`SchedulerEvent` or new `WorkerEvent` subtype) for the event bus. `SubAgentTranscript` (captured in `SubAgentResult`) provides full conversation replay for debugging.

## Acceptance Criteria

1. User-visible `/continue` behavior remains unchanged for baseline tasks.
2. At least one decomposed long task demonstrates parallel speedup with no resume regression.
3. Harness repair outcomes are captured and appear in learning evidence artifacts.
4. Governance gates can block harmful promoted repair patterns.

## Follow-Up ADRs

- **ADR-0002: Harness Outcome Records and Learning Pipeline** -- Defines structured failure/repair tracking records (`failure_type`, `root_cause`, `repair_action`, `repair_result`, `latency_ms`, `token_cost`, `source_session`), replay validation, and integration with the candidate evidence writeback pipeline (shadow -> canary -> active).
- **ADR-0003: Parallel Plan Execution with StructuredTaskScope** -- Defines `ParallelPlanExecutor` using Java 21 `StructuredTaskScope` for concurrent `SUBAGENT` step execution, fork/join semantics, partial-failure handling, and artifact merge ordering for parallel workers.

## References

- `research/openclaw-agent-orchestration.md`
- `research/architecture-agent-teams.md`
- `research/security-review.md`
- `docs/continuous-learning-plan.md`
- `docs/continuous-learning-governance.md`
- `docs/continuous-learning-operations.md`

Source files referenced in this ADR:
- `aceclaw-daemon/.../SessionManager.java` -- session lifecycle and `ConcurrentHashMap<String, AgentSession>` store
- `aceclaw-daemon/.../AgentSession.java` -- session state, `ConversationMessage` sealed hierarchy
- `aceclaw-daemon/.../StreamingAgentHandler.java` -- turn orchestration, permission-aware tool registry, sub-agent event forwarding
- `aceclaw-daemon/.../StreamContext.java` -- bidirectional streaming interface (`sendNotification`, `readMessage`)
- `aceclaw-daemon/.../SelfImprovementEngine.java` -- post-turn learning (`ErrorDetector`, `PatternDetector`, `FailureSignalDetector`)
- `aceclaw-core/.../SubAgentRunner.java` -- sub-agent execution, `parentRegistry` filtering, `runInBackground()`, `BackgroundTask` tracking
- `aceclaw-core/.../SubAgentResult.java` -- worker execution result (record: `taskId`, `text`, `Turn`, `SubAgentTranscript`)
- `aceclaw-core/.../SubAgentConfig.java` -- worker configuration (record: `name`, `model`, `allowedTools`, `disallowedTools`, `maxTurns`)
- `aceclaw-core/.../SequentialPlanExecutor.java` -- sequential step execution with `PlanEventListener` callbacks
- `aceclaw-core/.../PlannedStep.java` -- step model (record: `stepId`, `name`, `description`, `requiredTools`, `fallbackApproach`, `status`)
- `aceclaw-core/.../TaskPlan.java` -- plan model (record: `planId`, `originalGoal`, `steps`, `PlanStatus`)
