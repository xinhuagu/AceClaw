// AceClaw — Web Event Schema (issue #439, epic #430)
//
// Canonical TypeScript wire format for browser clients consuming AceClaw
// runtime state over the WebSocket bridge (#431).
//
// Architectural rule (decided in #439):
//   Schema layer = flat wire format (this file).
//   Reducer layer (#435) = builds the ExecutionNode tree.
//
// Therefore this file MUST NOT contain:
//   - parentId, nodeType, children, depth
//   - any tree / parallelism / nesting semantics
//   - any presentation concerns (colors, icons, status enums for UI)
//
// All field names mirror the JSON-RPC notifications emitted by the daemon
// (see aceclaw-daemon/src/main/java/dev/aceclaw/daemon/StreamingAgentHandler.java).
// If a daemon emission site changes a field, this file must be updated in lockstep.

// ---------------------------------------------------------------------------
// Envelope
// ---------------------------------------------------------------------------

/**
 * Monotonic event id assigned by the WebSocket bridge (#431) so that browser
 * clients can de-duplicate after `snapshot + live stream` reconnect.
 *
 * Snapshot interop rule:
 *   1. Browser fetches snapshot → receives `lastEventId` watermark.
 *   2. Browser opens WebSocket → applies only events with `eventId > lastEventId`.
 *   3. Within a session, eventId is strictly increasing.
 *
 * `eventId` is added by the bridge and is NOT part of the daemon's existing
 * JSON-RPC notification payload. It lives on the envelope, not in `params`.
 */
export interface DaemonEventEnvelope<E extends DaemonEvent = DaemonEvent> {
  eventId: number;
  sessionId: string;
  receivedAt: string; // ISO-8601, set by bridge
  event: E;
}

// ---------------------------------------------------------------------------
// Per-event params (flat, mirror daemon emission sites verbatim)
// ---------------------------------------------------------------------------

// --- Lifecycle (some new — see "Daemon must emit" below) ---

/** stream.session_started — NEW (issue #439, daemon to add in #431). */
export interface SessionStartedParams {
  sessionId: string;
  model: string;
  timestamp: string; // ISO-8601
}

/** stream.turn_started — NEW (issue #439, daemon to add in #431). */
export interface TurnStartedParams {
  sessionId: string;
  requestId: string;
  turnNumber: number;
  timestamp: string; // ISO-8601
}

/** stream.turn_completed — NEW (issue #439, daemon to add in #431). */
export interface TurnCompletedParams {
  sessionId: string;
  requestId: string;
  turnNumber: number;
  durationMs: number;
  toolCount: number;
  timestamp: string; // ISO-8601
}

// --- Existing daemon stream events ---

/** stream.thinking — extended thinking deltas. */
export interface ThinkingDeltaParams {
  delta: string;
}

/** stream.text — assistant text token deltas. */
export interface TextDeltaParams {
  delta: string;
}

/** stream.tool_use — emitted at tool-use block start (input may still be partial). */
export interface ToolUseParams {
  /** Tool use id (Anthropic block id, e.g. "toolu_..."). Unique per call. */
  id: string;
  /** Tool name (e.g. "read_file", "bash"). */
  name: string;
  /** Optional one-line summary of the input (path, command, etc.). */
  summary?: string;
}

/** stream.tool_completed — terminal event for a tool call. */
export interface ToolCompletedParams {
  id: string;
  name: string;
  durationMs: number;
  isError: boolean;
  /** Truncated error message; only present when isError === true. */
  error?: string;
}

/** stream.heartbeat — keepalive/progress beacon while a phase is in flight. */
export interface HeartbeatParams {
  phase: string;
}

/** stream.error — non-fatal stream-level error from the LLM transport. */
export interface StreamErrorParams {
  error: string;
}

/** stream.cancelled — emitted when a turn was cancelled mid-flight. */
export interface CancelledParams {
  sessionId: string;
}

/** stream.budget_exhausted — watchdog hit hard or soft-stall stop. */
export interface BudgetExhaustedParams {
  sessionId: string;
  reason: string;
  elapsedMs: number;
  extensionCount: number;
  softLimitReached: boolean;
}

/** stream.compaction — context compactor ran. */
export interface CompactionParams {
  originalTokens: number;
  compactedTokens: number;
  phase: string;
}

/** stream.subagent.start — a sub-agent (Task tool) began. */
export interface SubAgentStartParams {
  agentType: string;
  prompt: string;
}

/** stream.subagent.end — sub-agent terminated. */
export interface SubAgentEndParams {
  agentType: string;
}

/** stream.usage — token usage update for the session. */
export interface UsageParams {
  /** Input tokens for the most recent LLM call. */
  inputTokens: number;
  totalInputTokens: number;
  totalOutputTokens: number;
}

/** stream.gate — anti-pattern pre-execution gate decision (BLOCK/PENALIZE/etc.). */
export interface GateParams {
  sessionId: string;
  tool: string;
  gate: string;
  action: string;
  ruleId?: string;
  reason?: string;
  fallback?: string;
  override: boolean;
  overrideTtlSeconds?: number;
  overrideReason?: string;
}

// --- Plan events ---

/** stream.plan_created — task planner produced a plan. */
export interface PlanCreatedParams {
  planId: string;
  stepCount: number;
  goal: string;
  steps: PlanStepDescriptor[];
}

export interface PlanStepDescriptor {
  /** 1-based. */
  index: number;
  name: string;
  description: string;
}

/** stream.plan_step_started — sequential plan executor began a step. */
export interface PlanStepStartedParams {
  planId: string;
  stepId: string;
  /** 1-based. */
  stepIndex: number;
  totalSteps: number;
  stepName: string;
}

/** stream.plan_step_completed — step terminated (success or failure). */
export interface PlanStepCompletedParams {
  planId: string;
  stepId: string;
  /** 1-based. */
  stepIndex: number;
  stepName: string;
  success: boolean;
  durationMs: number;
  tokensUsed: number;
}

/** stream.plan_step_fallback — NEW (issue #439, daemon to add in #431). */
export interface PlanStepFallbackParams {
  sessionId: string;
  planId: string;
  stepId: string;
  /** 1-based. */
  stepIndex: number;
  fallbackApproach: string;
  attempt: number;
}

/** stream.plan_replanned — planner produced a revised plan mid-execution. */
export interface PlanReplannedParams {
  planId: string;
  replanAttempt: number;
  newStepCount: number;
  rationale: string;
}

/** stream.plan_completed — entire plan finished (success or failure). */
export interface PlanCompletedParams {
  planId: string;
  success: boolean;
  totalDurationMs: number;
  stepsCompleted: number;
  totalSteps: number;
}

/** stream.plan_escalated — planner gave up; control returned to ReAct loop. */
export interface PlanEscalatedParams {
  planId: string;
  reason: string;
}

// --- Permission (bidirectional) ---

/**
 * permission.request — daemon → client.
 *
 * The browser correlates request/response by `requestId`. With the WebSocket
 * bridge enabled, both CLI and Browser may receive the same request; the
 * daemon uses first-response-wins via CompletableFuture (#433).
 */
export interface PermissionRequestParams {
  tool: string;
  description: string;
  requestId: string;
}

/**
 * permission.response — client → daemon.
 *
 * Included here for completeness so reducers and bridge code share a single
 * source of truth. Browsers send this back over WebSocket (#433).
 */
export interface PermissionResponseParams {
  requestId: string;
  approved: boolean;
  /** When true, daemon grants session-level approval for this tool. */
  remember?: boolean;
}

// ---------------------------------------------------------------------------
// Discriminated union — DaemonEvent
// ---------------------------------------------------------------------------

/**
 * Wire-format union mirroring daemon JSON-RPC notifications exactly.
 *
 * Discriminator: `method`. The reducer (#435) switches on `event.method` and
 * builds the ExecutionNode tree from the resulting `params`.
 *
 * This union is exhaustive over the events documented as required for Tier 1
 * (epic #430). Forward-compatible additions go in `DaemonEventExtension` below.
 */
export type DaemonEvent =
  // Lifecycle (NEW — daemon must add in #431)
  | { method: 'stream.session_started'; params: SessionStartedParams }
  | { method: 'stream.turn_started'; params: TurnStartedParams }
  | { method: 'stream.turn_completed'; params: TurnCompletedParams }
  // Streaming primitives
  | { method: 'stream.thinking'; params: ThinkingDeltaParams }
  | { method: 'stream.text'; params: TextDeltaParams }
  | { method: 'stream.tool_use'; params: ToolUseParams }
  | { method: 'stream.tool_completed'; params: ToolCompletedParams }
  | { method: 'stream.heartbeat'; params: HeartbeatParams }
  | { method: 'stream.error'; params: StreamErrorParams }
  | { method: 'stream.cancelled'; params: CancelledParams }
  | { method: 'stream.budget_exhausted'; params: BudgetExhaustedParams }
  | { method: 'stream.compaction'; params: CompactionParams }
  | { method: 'stream.subagent.start'; params: SubAgentStartParams }
  | { method: 'stream.subagent.end'; params: SubAgentEndParams }
  | { method: 'stream.usage'; params: UsageParams }
  | { method: 'stream.gate'; params: GateParams }
  // Plan events
  | { method: 'stream.plan_created'; params: PlanCreatedParams }
  | { method: 'stream.plan_step_started'; params: PlanStepStartedParams }
  | { method: 'stream.plan_step_completed'; params: PlanStepCompletedParams }
  | { method: 'stream.plan_step_fallback'; params: PlanStepFallbackParams }
  | { method: 'stream.plan_replanned'; params: PlanReplannedParams }
  | { method: 'stream.plan_completed'; params: PlanCompletedParams }
  | { method: 'stream.plan_escalated'; params: PlanEscalatedParams }
  // Permission (bidirectional)
  | { method: 'permission.request'; params: PermissionRequestParams }
  | { method: 'permission.response'; params: PermissionResponseParams };

/** Extracts the params type for a given event method (compile-time helper). */
export type EventParams<M extends DaemonEvent['method']> = Extract<
  DaemonEvent,
  { method: M }
>['params'];

// ---------------------------------------------------------------------------
// Forward-compatibility hooks (Tier 2 / Tier 3 — out of scope for #439)
// ---------------------------------------------------------------------------

/**
 * Reserved namespaces. The reducer (#435) MUST tolerate unknown methods
 * (drop or stash under "unknown") so that new event kinds can ship without
 * forcing schema bumps in lockstep.
 *
 * Reserved prefixes:
 *   - `stream.swarm_*`     — Tier 3 swarm DAG events
 *   - `stream.worker_*`    — Tier 3 worker session events
 *   - `stream.skill_*`     — skill/agent invocation telemetry
 *   - `stream.memory_*`    — memory subsystem events
 *   - custom client namespaces: `x-<vendor>.<event>` (will not collide with daemon)
 */
export type ReservedMethodPrefix =
  | 'stream.swarm_'
  | 'stream.worker_'
  | 'stream.skill_'
  | 'stream.memory_';

/**
 * Loose escape hatch for events not yet in the canonical union. Reducers
 * should branch on `method` first and only fall through to this shape.
 */
export interface UnknownDaemonEvent {
  method: string;
  params: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Daemon emission map — single source of truth for #431 implementers
// ---------------------------------------------------------------------------

/**
 * Maps each event method to its current daemon emission site. Updated whenever
 * the daemon is changed; CI can grep this list against StreamingAgentHandler
 * source to catch drift.
 *
 * `status: 'existing'` — already emitted today.
 * `status: 'new'`      — must be added by #431 per issue #439.
 */
export const DAEMON_EMISSION_MAP = {
  // NEW — to be added by #431
  'stream.session_started': { status: 'new', emitter: 'StreamingAgentHandler#startSession' },
  'stream.turn_started': { status: 'new', emitter: 'StreamingAgentHandler#beginTurn' },
  'stream.turn_completed': { status: 'new', emitter: 'StreamingAgentHandler#endTurn' },
  'stream.plan_step_fallback': { status: 'new', emitter: 'SequentialPlanExecutor#onStepFallback' },

  // Existing — already emitted by daemon
  'stream.thinking': { status: 'existing', emitter: 'StreamingNotificationHandler#onThinkingDelta' },
  'stream.text': { status: 'existing', emitter: 'StreamingNotificationHandler#onTextDelta' },
  'stream.tool_use': { status: 'existing', emitter: 'StreamingNotificationHandler#onContentBlockStart' },
  'stream.tool_completed': { status: 'existing', emitter: 'StreamingNotificationHandler#onToolCompleted' },
  'stream.heartbeat': { status: 'existing', emitter: 'StreamingNotificationHandler#onHeartbeat' },
  'stream.error': { status: 'existing', emitter: 'StreamingNotificationHandler#onError' },
  'stream.cancelled': { status: 'existing', emitter: 'StreamingAgentHandler#sendCancelledNotificationIfNeeded' },
  'stream.budget_exhausted': { status: 'existing', emitter: 'StreamingAgentHandler#sendBudgetExhaustedNotificationIfNeeded' },
  'stream.compaction': { status: 'existing', emitter: 'StreamingNotificationHandler#onCompaction' },
  'stream.subagent.start': { status: 'existing', emitter: 'StreamingNotificationHandler#onSubAgentStart' },
  'stream.subagent.end': { status: 'existing', emitter: 'StreamingNotificationHandler#onSubAgentEnd' },
  'stream.usage': { status: 'existing', emitter: 'StreamingNotificationHandler#onUsageUpdate' },
  'stream.gate': { status: 'existing', emitter: 'StreamingAgentHandler#emitGateNotification' },
  'stream.plan_created': { status: 'existing', emitter: 'StreamingAgentHandler (plan executor wiring)' },
  'stream.plan_step_started': { status: 'existing', emitter: 'PlanEventListener#onStepStarted' },
  'stream.plan_step_completed': { status: 'existing', emitter: 'PlanEventListener#onStepCompleted' },
  'stream.plan_replanned': { status: 'existing', emitter: 'PlanEventListener#onPlanReplanned' },
  'stream.plan_completed': { status: 'existing', emitter: 'PlanEventListener#onPlanCompleted' },
  'stream.plan_escalated': { status: 'existing', emitter: 'PlanEventListener#onPlanEscalated' },
  'permission.request': { status: 'existing', emitter: 'StreamingAgentHandler permission gate' },
  'permission.response': { status: 'existing', emitter: '(client → daemon)' },
} as const satisfies Record<DaemonEvent['method'], { status: 'existing' | 'new'; emitter: string }>;
