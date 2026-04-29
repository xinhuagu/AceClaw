/**
 * Tier 1 EventReducer (issue #435).
 *
 * Pure function {@code (state, envelope) → newState} that turns the daemon's
 * flat event stream (from the WebSocket bridge, #431) into a hierarchical
 * {@link ExecutionTree}. The reducer is the ONLY place where flat → tree
 * mapping happens — see #439 for why this lives in the frontend rather than
 * the daemon.
 *
 * Architectural rules
 * - Pure: no I/O, no time-dependent helpers (timestamps come from envelopes).
 * - Immutable: every update returns a new state; never mutates inputs.
 * - Forward-compatible: unknown methods are silently ignored so Tier 2/3
 *   additions don't require reducer changes.
 * - Watermark-aware: every dispatch advances {@code state.lastEventId} so
 *   #432's snapshot/reconnect can deduplicate cleanly.
 */

import type {
  DaemonEvent,
  DaemonEventEnvelope,
  PermissionRequestParams,
  PlanCreatedParams,
  PlanStepStartedParams,
  PlanStepCompletedParams,
  PlanStepFallbackParams,
  PlanReplannedParams,
  PlanCompletedParams,
  PlanEscalatedParams,
  SessionStartedParams,
  SubAgentEndParams,
  SubAgentStartParams,
  TextDeltaParams,
  ToolCompletedParams,
  ToolUseParams,
  TurnCompletedParams,
  TurnStartedParams,
  UsageParams,
  CompactionParams,
} from '../types/events';
import type { ExecutionNode, ExecutionTree } from '../types/tree';

/**
 * The reducer takes only well-typed envelopes. Forward-compatibility for
 * Tier 2/3 events is enforced at the hook layer — the hook validates the
 * inbound method against the {@link DaemonEvent} union and drops anything
 * unknown before dispatch. That keeps the switch below exhaustive in the
 * type system without sprinkling casts through every case body.
 */
type Envelope = DaemonEventEnvelope<DaemonEvent>;

// ---------------------------------------------------------------------------
// Tree helpers (pure, recursive, immutable)
// ---------------------------------------------------------------------------

/** Returns the path from root to {@code id}, or null if not found. */
function findPath(
  nodes: ExecutionNode[],
  id: string,
): ExecutionNode[] | null {
  for (const n of nodes) {
    if (n.id === id) return [n];
    const sub = findPath(n.children, id);
    if (sub) return [n, ...sub];
  }
  return null;
}

/** Returns the matching node anywhere in the tree, or null. */
function findNode(
  nodes: ExecutionNode[],
  id: string,
): ExecutionNode | null {
  for (const n of nodes) {
    if (n.id === id) return n;
    const sub = findNode(n.children, id);
    if (sub) return sub;
  }
  return null;
}

/**
 * Returns the nearest ancestor of {@code fromId} (inclusive) for which
 * {@code predicate} holds, or null. Walks the path root→fromId in reverse so
 * "fromId itself" wins over its parent on a tie.
 */
function findNearestAncestor(
  rootNodes: ExecutionNode[],
  fromId: string | null,
  predicate: (n: ExecutionNode) => boolean,
): ExecutionNode | null {
  if (!fromId) return null;
  const path = findPath(rootNodes, fromId);
  if (!path) return null;
  for (let i = path.length - 1; i >= 0; i--) {
    const candidate = path[i];
    if (candidate && predicate(candidate)) return candidate;
  }
  return null;
}

/**
 * Replaces a node anywhere in the forest by id. Returns a new forest with
 * structural sharing for branches that did not change.
 */
function mapNode(
  nodes: ExecutionNode[],
  id: string,
  updater: (n: ExecutionNode) => ExecutionNode,
): ExecutionNode[] {
  let changed = false;
  const next = nodes.map((n) => {
    if (n.id === id) {
      changed = true;
      return updater(n);
    }
    if (n.children.length === 0) return n;
    const newChildren = mapNode(n.children, id, updater);
    if (newChildren !== n.children) {
      changed = true;
      return { ...n, children: newChildren };
    }
    return n;
  });
  return changed ? next : nodes;
}

/** Appends a child under {@code parentId}. Parent must already exist. */
function appendChild(
  nodes: ExecutionNode[],
  parentId: string,
  child: ExecutionNode,
): ExecutionNode[] {
  return mapNode(nodes, parentId, (parent) => ({
    ...parent,
    children: [...parent.children, child],
  }));
}

// ---------------------------------------------------------------------------
// Per-method handlers — each returns a new ExecutionTree
// ---------------------------------------------------------------------------

function addSessionNode(
  state: ExecutionTree,
  params: SessionStartedParams,
): ExecutionTree {
  // Idempotent: if the session node already exists (e.g. from a snapshot
  // replay), don't double-add. Compare on type+sessionId rather than node id
  // so we tolerate snapshot shapes that pre-date this dispatch.
  const existing = state.rootNodes.find(
    (n) => n.type === 'session' && n.id === params.sessionId,
  );
  if (existing) return state;

  const node: ExecutionNode = {
    id: params.sessionId,
    type: 'session',
    status: 'running',
    label: `session (${params.model})`,
    startTime: Date.parse(params.timestamp),
    children: [],
    metadata: { model: params.model },
  };
  return {
    ...state,
    rootNodes: [...state.rootNodes, node],
    activeNodeId: node.id,
  };
}

function addTurnNode(
  state: ExecutionTree,
  params: TurnStartedParams,
): ExecutionTree {
  // Turns nest under the deepest active step (when a plan is mid-execution)
  // or under the session otherwise. session_started may have been missed
  // (e.g. browser connected mid-prompt) — fall back to a synthesised root.
  const parent =
    findNearestAncestor(
      state.rootNodes,
      state.activeNodeId,
      (n) => n.type === 'step' && n.status === 'running',
    ) ??
    findNearestAncestor(
      state.rootNodes,
      state.activeNodeId,
      (n) => n.type === 'session',
    ) ??
    state.rootNodes.find((n) => n.id === params.sessionId) ??
    null;

  const turn: ExecutionNode = {
    id: params.requestId,
    type: 'turn',
    status: 'running',
    label: `turn ${params.turnNumber}`,
    startTime: Date.parse(params.timestamp),
    children: [],
    metadata: { turnNumber: params.turnNumber },
  };

  let rootNodes: ExecutionNode[];
  if (parent) {
    rootNodes = appendChild(state.rootNodes, parent.id, turn);
  } else {
    // No session/step found — drop the turn at the root so it remains visible.
    rootNodes = [...state.rootNodes, turn];
  }

  return {
    ...state,
    rootNodes,
    activeNodeId: turn.id,
    stats: { ...state.stats, totalTurns: state.stats.totalTurns + 1 },
  };
}

function completeTurnNode(
  state: ExecutionTree,
  params: TurnCompletedParams,
): ExecutionTree {
  const rootNodes = mapNode(state.rootNodes, params.requestId, (n) => ({
    ...n,
    status: 'completed' as const,
    endTime: Date.parse(params.timestamp),
    duration: params.durationMs,
  }));
  // After a turn completes, the active node is the parent (step / session).
  const path = findPath(rootNodes, params.requestId) ?? [];
  const parent = path.length >= 2 ? path[path.length - 2] : null;
  return {
    ...state,
    rootNodes,
    activeNodeId: parent ? parent.id : null,
  };
}

function addToolNode(
  state: ExecutionTree,
  params: ToolUseParams,
): ExecutionTree {
  // Tools always nest under the nearest running turn. Without one, the event
  // arrived out of order; surface it at root so the operator sees something.
  const turn = findNearestAncestor(
    state.rootNodes,
    state.activeNodeId,
    (n) => n.type === 'turn' && n.status === 'running',
  );

  const tool: ExecutionNode = {
    id: params.id,
    type: 'tool',
    status: 'running',
    label: params.summary ? `${params.name} — ${params.summary}` : params.name,
    children: [],
    metadata: { tool: params.name, summary: params.summary },
  };

  let rootNodes: ExecutionNode[];
  if (turn) {
    // Parallel-detection: if any sibling tool is already running, mark the
    // turn as having executed parallel work. One-way flag — once true, stays.
    const hasRunningSibling = turn.children.some(
      (c) => c.type === 'tool' && c.status === 'running',
    );
    rootNodes = mapNode(state.rootNodes, turn.id, (t) => ({
      ...t,
      // One-way flag: once a turn has had parallel work, we keep that record
      // even after every tool finishes. Conditional spread because
      // exactOptionalPropertyTypes forbids assigning `undefined` to an
      // optional property.
      ...(hasRunningSibling || t.parallel ? { parallel: true } : {}),
      children: [...t.children, tool],
    }));
  } else {
    rootNodes = [...state.rootNodes, tool];
  }

  return {
    ...state,
    rootNodes,
    activeNodeId: tool.id,
    stats: { ...state.stats, totalTools: state.stats.totalTools + 1 },
  };
}

function completeToolNode(
  state: ExecutionTree,
  params: ToolCompletedParams,
): ExecutionTree {
  const node = findNode(state.rootNodes, params.id);
  if (!node) return state;

  const status: ExecutionNode['status'] = params.isError ? 'failed' : 'completed';
  const rootNodes = mapNode(state.rootNodes, params.id, (n) => {
    const error = params.error ?? n.error;
    return {
      ...n,
      status,
      duration: params.durationMs,
      ...(error !== undefined ? { error } : {}),
    };
  });

  // Move active back up to the parent turn so the next tool_use attaches there.
  const path = findPath(rootNodes, params.id) ?? [];
  const parent = path.length >= 2 ? path[path.length - 2] : null;

  return {
    ...state,
    rootNodes,
    activeNodeId: parent ? parent.id : state.activeNodeId,
    stats: {
      ...state.stats,
      completedTools: state.stats.completedTools + (params.isError ? 0 : 1),
      failedTools: state.stats.failedTools + (params.isError ? 1 : 0),
    },
  };
}

function appendTextToCurrentTurn(
  state: ExecutionTree,
  params: TextDeltaParams,
): ExecutionTree {
  // Text deltas roll up into a single text-typed child of the current turn so
  // the renderer can show them as one streaming bubble. If no turn is active,
  // drop the delta — there's no meaningful place to attach it.
  const turn = findNearestAncestor(
    state.rootNodes,
    state.activeNodeId,
    (n) => n.type === 'turn' && n.status === 'running',
  );
  if (!turn) return state;

  const existing = turn.children.find((c) => c.type === 'text');
  if (existing) {
    return {
      ...state,
      rootNodes: mapNode(state.rootNodes, existing.id, (t) => ({
        ...t,
        text: (t.text ?? '') + params.delta,
      })),
    };
  }
  const textNode: ExecutionNode = {
    id: `${turn.id}:text`,
    type: 'text',
    status: 'running',
    label: 'response',
    children: [],
    text: params.delta,
  };
  return {
    ...state,
    rootNodes: appendChild(state.rootNodes, turn.id, textNode),
  };
}

function addPlanSkeleton(
  state: ExecutionTree,
  params: PlanCreatedParams,
): ExecutionTree {
  // Plan attaches to the nearest running turn (its conceptual owner). Steps
  // are pre-created as pending children so the dashboard can show the full
  // skeleton before any step runs — the headline visualisation feature.
  const owner =
    findNearestAncestor(
      state.rootNodes,
      state.activeNodeId,
      (n) => n.type === 'turn' && n.status === 'running',
    ) ??
    findNearestAncestor(
      state.rootNodes,
      state.activeNodeId,
      (n) => n.type === 'session',
    ) ??
    null;

  const stepChildren: ExecutionNode[] = params.steps.map((s) => ({
    id: `${params.planId}:${s.index}`,
    type: 'step',
    status: 'pending',
    label: s.name,
    children: [],
    metadata: { stepIndex: s.index, description: s.description },
  }));
  const plan: ExecutionNode = {
    id: params.planId,
    type: 'plan',
    status: 'running',
    label: `plan: ${params.goal}`,
    children: stepChildren,
    metadata: {
      planId: params.planId,
      stepCount: params.stepCount,
      resumed: params.resumed ?? false,
      ...(params.resumedFromStep !== undefined
        ? { resumedFromStep: params.resumedFromStep }
        : {}),
    },
  };

  if (owner) {
    return {
      ...state,
      rootNodes: appendChild(state.rootNodes, owner.id, plan),
    };
  }
  return { ...state, rootNodes: [...state.rootNodes, plan] };
}

function activateStep(
  state: ExecutionTree,
  params: PlanStepStartedParams,
): ExecutionTree {
  const stepId = `${params.planId}:${params.stepIndex}`;
  const rootNodes = mapNode(state.rootNodes, stepId, (n) => ({
    ...n,
    status: 'running',
    label: params.stepName,
  }));
  return { ...state, rootNodes, activeNodeId: stepId };
}

function completeStep(
  state: ExecutionTree,
  params: PlanStepCompletedParams,
): ExecutionTree {
  const stepId = `${params.planId}:${params.stepIndex}`;
  const rootNodes = mapNode(state.rootNodes, stepId, (n) => ({
    ...n,
    status: params.success ? 'completed' : 'failed',
    duration: params.durationMs,
  }));
  // Step done → next step (still pending) inherits focus, otherwise the plan.
  const plan = findNode(rootNodes, params.planId);
  const nextPending = plan?.children.find(
    (c) => c.type === 'step' && c.status === 'pending',
  );
  return {
    ...state,
    rootNodes,
    activeNodeId: nextPending ? nextPending.id : params.planId,
  };
}

function markStepFallback(
  state: ExecutionTree,
  params: PlanStepFallbackParams,
): ExecutionTree {
  const stepId = `${params.planId}:${params.stepIndex}`;
  return {
    ...state,
    rootNodes: mapNode(state.rootNodes, stepId, (n) => ({
      ...n,
      metadata: {
        ...(n.metadata ?? {}),
        fallback: {
          approach: params.fallbackApproach,
          attempt: params.attempt,
        },
      },
    })),
  };
}

function replacePlanSteps(
  state: ExecutionTree,
  params: PlanReplannedParams,
): ExecutionTree {
  // The daemon's PlanReplanned only carries newStepCount + rationale, not the
  // full new step list (#439). Mark the plan with replan metadata so the
  // renderer can strike through the old step list; the next plan_step_started
  // events will land on synthesised step IDs that don't exist yet — handle
  // that by lazily creating them in activateStep when the lookup misses.
  return {
    ...state,
    rootNodes: mapNode(state.rootNodes, params.planId, (plan) => ({
      ...plan,
      children: plan.children.map((c) =>
        c.type === 'step' && c.status !== 'completed'
          ? { ...c, status: 'cancelled' as const }
          : c,
      ),
      metadata: {
        ...(plan.metadata ?? {}),
        replanAttempt: params.replanAttempt,
        replanRationale: params.rationale,
        newStepCount: params.newStepCount,
      },
    })),
  };
}

function completePlan(
  state: ExecutionTree,
  params: PlanCompletedParams,
): ExecutionTree {
  return {
    ...state,
    rootNodes: mapNode(state.rootNodes, params.planId, (n) => ({
      ...n,
      status: params.success ? 'completed' : 'failed',
      duration: params.totalDurationMs,
      metadata: {
        ...(n.metadata ?? {}),
        stepsCompleted: params.stepsCompleted,
        totalSteps: params.totalSteps,
      },
    })),
  };
}

function failPlan(
  state: ExecutionTree,
  params: PlanEscalatedParams,
): ExecutionTree {
  return {
    ...state,
    rootNodes: mapNode(state.rootNodes, params.planId, (n) => ({
      ...n,
      status: 'failed',
      error: params.reason,
    })),
  };
}

function addSubAgentNode(
  state: ExecutionTree,
  params: SubAgentStartParams,
): ExecutionTree {
  const turn = findNearestAncestor(
    state.rootNodes,
    state.activeNodeId,
    (n) => n.type === 'turn' && n.status === 'running',
  );
  // Sub-agent ids in the daemon are not unique across the same agentType
  // running twice in one turn, so synthesise a unique frontend-side id.
  const id = `subagent:${params.agentType}:${state.stats.totalTools}:${state.rootNodes.length}`;
  const node: ExecutionNode = {
    id,
    type: 'subagent',
    status: 'running',
    label: `subagent: ${params.agentType}`,
    children: [],
    metadata: { agentType: params.agentType, prompt: params.prompt },
  };
  if (turn) {
    return {
      ...state,
      rootNodes: appendChild(state.rootNodes, turn.id, node),
      activeNodeId: id,
    };
  }
  return { ...state, rootNodes: [...state.rootNodes, node], activeNodeId: id };
}

function completeSubAgentNode(
  state: ExecutionTree,
  params: SubAgentEndParams,
): ExecutionTree {
  // Match the most recent running subagent of this type — the daemon does not
  // include a unique id on subagent.end (#439), so we resolve by walking from
  // the active context back up to the most recent matching subagent.
  let target: ExecutionNode | null = null;
  const visit = (nodes: readonly ExecutionNode[]): void => {
    for (const n of nodes) {
      if (
        n.type === 'subagent' &&
        n.status === 'running' &&
        (n.metadata?.['agentType'] === params.agentType)
      ) {
        target = n;
      }
      visit(n.children);
    }
  };
  visit(state.rootNodes);
  if (!target) return state;
  const targetId = (target as ExecutionNode).id;
  return {
    ...state,
    rootNodes: mapNode(state.rootNodes, targetId, (n) => ({
      ...n,
      status: 'completed',
    })),
  };
}

function pauseForPermission(
  state: ExecutionTree,
  params: PermissionRequestParams,
): ExecutionTree {
  // The daemon pauses execution waiting for a permission.response. We mark
  // the active leaf as paused + awaitingInput so the renderer can surface
  // the prompt right where the agent stopped.
  if (!state.activeNodeId) return state;
  return {
    ...state,
    rootNodes: mapNode(state.rootNodes, state.activeNodeId, (n) => ({
      ...n,
      status: 'paused',
      awaitingInput: true,
      inputPrompt: params.description,
      permissionRequestId: params.requestId,
      metadata: { ...(n.metadata ?? {}), permissionTool: params.tool },
    })),
  };
}

function updateUsageStats(
  state: ExecutionTree,
  params: UsageParams,
): ExecutionTree {
  return {
    ...state,
    stats: {
      ...state.stats,
      // totalInputTokens / totalOutputTokens are cumulative on the daemon
      // side — mirror the latest snapshot rather than summing locally.
      inputTokens: params.totalInputTokens,
      outputTokens: params.totalOutputTokens,
    },
  };
}

function addCompactionMarker(
  state: ExecutionTree,
  params: CompactionParams,
): ExecutionTree {
  // Surface a compaction event on the active turn's metadata so the renderer
  // can draw a small marker. No new node — compaction is metadata, not a
  // first-class tree node.
  const turn = findNearestAncestor(
    state.rootNodes,
    state.activeNodeId,
    (n) => n.type === 'turn',
  );
  if (!turn) return state;
  return {
    ...state,
    rootNodes: mapNode(state.rootNodes, turn.id, (t) => ({
      ...t,
      metadata: {
        ...(t.metadata ?? {}),
        compactions: [
          ...(((t.metadata?.['compactions'] as unknown[]) ?? [])),
          {
            originalTokens: params.originalTokens,
            compactedTokens: params.compactedTokens,
            phase: params.phase,
          },
        ],
      },
    })),
  };
}

// ---------------------------------------------------------------------------
// Main reducer
// ---------------------------------------------------------------------------

/**
 * Reduces a daemon event envelope into a new ExecutionTree.
 *
 * Idempotent against snapshot replay: events with {@code eventId <= state.lastEventId}
 * are dropped, mirroring the dedup rule from #439's events.ts. Unknown event
 * methods (e.g. future Tier 3 swarm events) are ignored.
 */
export function executionTreeReducer(
  state: ExecutionTree,
  envelope: Envelope,
): ExecutionTree {
  // Snapshot/reconnect dedup: skip events the browser already saw.
  if (envelope.eventId > 0 && envelope.eventId <= state.lastEventId) {
    return state;
  }

  let next: ExecutionTree = state;
  const event = envelope.event;
  switch (event.method) {
    case 'stream.session_started':
      next = addSessionNode(state, event.params);
      break;
    case 'stream.turn_started':
      next = addTurnNode(state, event.params);
      break;
    case 'stream.turn_completed':
      next = completeTurnNode(state, event.params);
      break;
    case 'stream.tool_use':
      next = addToolNode(state, event.params);
      break;
    case 'stream.tool_completed':
      next = completeToolNode(state, event.params);
      break;
    case 'stream.text':
      next = appendTextToCurrentTurn(state, event.params);
      break;
    case 'stream.plan_created':
      next = addPlanSkeleton(state, event.params);
      break;
    case 'stream.plan_step_started':
      next = activateStep(state, event.params);
      break;
    case 'stream.plan_step_completed':
      next = completeStep(state, event.params);
      break;
    case 'stream.plan_step_fallback':
      next = markStepFallback(state, event.params);
      break;
    case 'stream.plan_replanned':
      next = replacePlanSteps(state, event.params);
      break;
    case 'stream.plan_completed':
      next = completePlan(state, event.params);
      break;
    case 'stream.plan_escalated':
      next = failPlan(state, event.params);
      break;
    case 'stream.subagent.start':
      next = addSubAgentNode(state, event.params);
      break;
    case 'stream.subagent.end':
      next = completeSubAgentNode(state, event.params);
      break;
    case 'permission.request':
      next = pauseForPermission(state, event.params);
      break;
    case 'stream.usage':
      next = updateUsageStats(state, event.params);
      break;
    case 'stream.compaction':
      next = addCompactionMarker(state, event.params);
      break;
    default:
      // Unknown / forward-compat method (Tier 2/3 swarm, custom namespaces).
      // Intentionally ignored — schema reserves these for future expansion.
      break;
  }

  // Watermark advances even on no-op events so reconnect dedup stays accurate.
  if (envelope.eventId > next.lastEventId) {
    return { ...next, lastEventId: envelope.eventId };
  }
  return next;
}
