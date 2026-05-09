import { describe, expect, it } from 'vitest';
import type {
  DaemonEvent,
  DaemonEventEnvelope,
  EventParams,
} from '../src/types/events';
import { emptyTree, type ExecutionNode, type ExecutionTree } from '../src/types/tree';
import {
  dismissPermissionPanel,
  executionTreeReducer,
  resolvePermissionLocally,
  stepNodeId,
} from '../src/reducers/treeReducer';

/**
 * Acceptance-criteria coverage for issue #435. Each describe block maps to
 * one bullet on the issue: sequential tools, parallel tools, plan lifecycle,
 * replan, permission pause, stats, activeNodeId tracking, forward-compat.
 *
 * The reducer is pure, so tests assert directly on the returned state — no
 * fixtures, no mocks, no spies. {@link envelope} threads a monotonic eventId
 * so the reducer's snapshot-replay dedup gate is exercised throughout.
 */

let nextEventId = 1;
function envelope<M extends DaemonEvent['method']>(
  method: M,
  params: EventParams<M>,
  sessionId = 'sess-1',
): DaemonEventEnvelope {
  return {
    eventId: nextEventId++,
    sessionId,
    receivedAt: new Date(2026, 0, 1).toISOString(),
    event: { method, params } as DaemonEvent,
  };
}

function freshTree(): ExecutionTree {
  nextEventId = 1;
  return emptyTree('sess-1');
}

function runAll(state: ExecutionTree, ...envs: DaemonEventEnvelope[]): ExecutionTree {
  return envs.reduce(executionTreeReducer, state);
}

/**
 * Walk the whole tree and return the first node matching {@code id}.
 * Used by tests that want to be agnostic to whether a synthetic
 * thinking sits between turn and tool (the reducer mints one when no
 * stream.thinking arrived for the iteration — see addToolNode and
 * appendTextToCurrentTurn). Mechanical depth navigation in tests was
 * coupling them to the exact synthesis layout, which made the test
 * suite churn every time the reducer's iteration-boundary detection
 * grew a new clause.
 */
function findById(state: ExecutionTree, id: string): ExecutionNode {
  function search(nodes: ExecutionNode[]): ExecutionNode | null {
    for (const n of nodes) {
      if (n.id === id) return n;
      const found = search(n.children);
      if (found) return found;
    }
    return null;
  }
  const found = search(state.rootNodes);
  if (!found) throw new Error(`node ${id} not found in tree`);
  return found;
}

// ---------------------------------------------------------------------------
// Sequential tools — baseline tree shape
// ---------------------------------------------------------------------------

describe('sequential tools', () => {
  it('builds session → turn → tool, completes in order', () => {
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'claude-opus',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.tool_use', { id: 't1', name: 'read_file', summary: 'a.py' }),
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'read_file',
        durationMs: 12,
        isError: false,
      }),
      envelope('stream.tool_use', { id: 't2', name: 'grep', summary: 'foo' }),
      envelope('stream.tool_completed', {
        id: 't2',
        name: 'grep',
        durationMs: 8,
        isError: false,
      }),
      envelope('stream.turn_completed', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        durationMs: 25,
        toolCount: 2,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );

    const session = state.rootNodes[0]!;
    expect(session.type).toBe('session');
    const turn = session.children[0]!;
    expect(turn.type).toBe('turn');
    expect(turn.status).toBe('completed');
    expect(turn.children).toHaveLength(2);
    expect(turn.children.map((c) => c.status)).toEqual(['completed', 'completed']);
    expect(turn.parallel).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Parallel detection
// ---------------------------------------------------------------------------

describe('parallel tools', () => {
  it('marks turn.parallel=true when a second tool_use arrives before completion', () => {
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.tool_use', { id: 't1', name: 'read_file' }),
      envelope('stream.tool_use', { id: 't2', name: 'grep' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    expect(turn.parallel).toBe(true);
    // Without a stream.thinking event, both parallel tools share a
    // synthetic thinking parent (addToolNode synthesises one for the
    // first tool, the second tool is a parallel sibling under the
    // same anchor). Turn → 1 thinking → 2 tools.
    expect(turn.children).toHaveLength(1);
    expect(turn.children[0]!.type).toBe('thinking');
    expect(turn.children[0]!.children).toHaveLength(2);
  });

  it('parallel flag persists after both tools complete', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.tool_use', { id: 't1', name: 'read_file' }),
      envelope('stream.tool_use', { id: 't2', name: 'grep' }),
    );
    state = runAll(
      state,
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'read_file',
        durationMs: 1,
        isError: false,
      }),
      envelope('stream.tool_completed', {
        id: 't2',
        name: 'grep',
        durationMs: 1,
        isError: false,
      }),
    );
    expect(state.rootNodes[0]!.children[0]!.parallel).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Plan lifecycle
// ---------------------------------------------------------------------------

describe('plan lifecycle', () => {
  function setup(): ExecutionTree {
    return runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
  }

  it('plan_created renders all steps as pending immediately', () => {
    const state = runAll(
      setup(),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 3,
        goal: 'do the thing',
        steps: [
          { index: 1, name: 'analyze', description: 'a' },
          { index: 2, name: 'modify', description: 'b' },
          { index: 3, name: 'verify', description: 'c' },
        ],
      }),
    );
    const plan = state.rootNodes[0]!.children[0]!.children[0]!;
    expect(plan.type).toBe('plan');
    expect(plan.children).toHaveLength(3);
    expect(plan.children.map((c) => c.status)).toEqual([
      'pending',
      'pending',
      'pending',
    ]);
  });

  it('activate → complete walks each step independently', () => {
    let state = runAll(
      setup(),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
        ],
      }),
    );
    state = runAll(
      state,
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 10,
        tokensUsed: 100,
      }),
    );
    const plan = state.rootNodes[0]!.children[0]!.children[0]!;
    expect(plan.children[0]!.status).toBe('completed');
    expect(plan.children[1]!.status).toBe('pending');
    // After step 1 completes, focus should shift to the next pending step.
    expect(state.activeNodeId).toBe(stepNodeId('plan-1', 2));
  });
});

// ---------------------------------------------------------------------------
// Replan — old steps cancelled, plan metadata records the rationale
// ---------------------------------------------------------------------------

describe('replan', () => {
  it('cancels not-yet-completed steps and records replan metadata', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 3,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
          { index: 3, name: 's3', description: '' },
        ],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 3,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 1,
        tokensUsed: 1,
      }),
    );
    state = runAll(
      state,
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 2,
        rationale: 'changed approach',
      }),
    );
    const plan = state.rootNodes[0]!.children[0]!.children[0]!;
    expect(plan.children[0]!.status).toBe('completed'); // s1 untouched
    expect(plan.children[1]!.status).toBe('cancelled'); // s2 was pending
    expect(plan.children[2]!.status).toBe('cancelled'); // s3 was pending
    expect(plan.metadata?.['replanAttempt']).toBe(1);
    expect(plan.metadata?.['replanRationale']).toBe('changed approach');
    // #458 — the replan event itself is now an addressable node.
    expect(plan.children[3]!.type).toBe('replan');
  });
});

// ---------------------------------------------------------------------------
// Plan-step agent-loop scoping — agent-loop events that fire while a plan
// step is running must nest UNDER that step, not on the enclosing turn.
// Without this, every step's thinking/tool/text would walk up past the
// step and chain off the original prompt's turn, producing one long
// undifferentiated agent-loop chain across all steps.
// ---------------------------------------------------------------------------

describe('agent-loop events anchor on the running plan step', () => {
  function freshTurnWithPlan() {
    return runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
        ],
      }),
    );
  }

  it('thinking inside a running step nests under the step, not the turn', () => {
    const state = runAll(
      freshTurnWithPlan(),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      envelope('stream.thinking', { delta: 'considering options' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const plan = turn.children[0]!;
    const step = plan.children.find(
      (c) => c.id === stepNodeId('plan-1', 1),
    )!;
    // Step is the parent of the thinking node, NOT the turn.
    const thinking = step.children.find((c) => c.type === 'thinking');
    expect(thinking).toBeDefined();
    expect(thinking!.text).toBe('considering options');
    // The turn must NOT have a thinking child of its own — only the
    // plan it spawned and nothing else from the agent loop.
    expect(turn.children.filter((c) => c.type === 'thinking')).toHaveLength(0);
  });

  it('tool_use inside a running step nests under the step', () => {
    const state = runAll(
      freshTurnWithPlan(),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      envelope('stream.thinking', { delta: 'plan to read' }),
      envelope('stream.tool_use', { id: 'tu-1', name: 'read_file' }),
    );
    const step = state.rootNodes[0]!.children[0]!.children[0]!.children.find(
      (c) => c.id === stepNodeId('plan-1', 1),
    )!;
    // Tool nests under the step's thinking node (which itself nests
    // under the step) — the whole sub-tree stays inside the step.
    const stepThinking = step.children.find((c) => c.type === 'thinking');
    const toolUse = stepThinking?.children.find((c) => c.type === 'tool');
    expect(toolUse).toBeDefined();
    expect(toolUse!.id).toBe('tu-1');
    // The turn must not have any tool as a DIRECT child — the only
    // child of the turn should be the plan, which contains the step,
    // which contains the agent-loop subtree. Pre-fix, the tool would
    // have appeared as a direct sibling of the plan under the turn.
    const turn = state.rootNodes[0]!.children[0]!;
    expect(turn.children.filter((c) => c.type === 'tool')).toHaveLength(0);
  });

  it('two consecutive steps each get their own agent-loop subtree', () => {
    // The original bug: events from BOTH steps walked up past the step
    // and chained on the user's turn, producing one giant agent-loop
    // chain instead of two separate per-step subtrees.
    const state = runAll(
      freshTurnWithPlan(),
      // Step 1: 1 thinking + 1 tool
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      envelope('stream.thinking', { delta: 's1 thinking' }),
      envelope('stream.tool_use', { id: 'tu-1', name: 'read_file' }),
      envelope('stream.tool_completed', {
        id: 'tu-1',
        name: 'read_file',
        durationMs: 1,
        isError: false,
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 1,
        tokensUsed: 1,
      }),
      // Step 2: 1 thinking + 1 tool
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's2',
        stepIndex: 2,
        totalSteps: 2,
        stepName: 's2',
      }),
      envelope('stream.thinking', { delta: 's2 thinking' }),
      envelope('stream.tool_use', { id: 'tu-2', name: 'edit_file' }),
    );

    const turn = state.rootNodes[0]!.children[0]!;
    const plan = turn.children[0]!;
    const step1 = plan.children.find((c) => c.id === stepNodeId('plan-1', 1))!;
    const step2 = plan.children.find((c) => c.id === stepNodeId('plan-1', 2))!;

    // Each step has its OWN thinking subtree (with its OWN tool nested inside).
    const t1 = step1.children.find((c) => c.type === 'thinking');
    const t2 = step2.children.find((c) => c.type === 'thinking');
    expect(t1?.text).toBe('s1 thinking');
    expect(t2?.text).toBe('s2 thinking');
    expect(t1?.children.find((c) => c.type === 'tool')?.id).toBe('tu-1');
    expect(t2?.children.find((c) => c.type === 'tool')?.id).toBe('tu-2');

    // The turn must NOT have any thinking/tool children of its own
    // outside the plan — those would be the symptom of the original
    // anchoring bug (events chaining off the turn instead of the steps).
    const turnDirectAgentLoop = turn.children.filter(
      (c) => c.type === 'thinking' || c.type === 'tool' || c.type === 'text',
    );
    expect(turnDirectAgentLoop).toHaveLength(0);
  });

  it('falls back to the turn when no step is active (plain ReAct)', () => {
    // Regression guard: the new helper must NOT break the no-plan path.
    // Without this assertion, a future "step-only" interpretation of
    // findCurrentAgentScope would silently break every non-plan
    // session.
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.thinking', { delta: 'no plan, just react' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinking = turn.children.find((c) => c.type === 'thinking');
    expect(thinking).toBeDefined();
    expect(thinking!.text).toBe('no plan, just react');
  });
});

// ---------------------------------------------------------------------------
// Replan as a first-class node (#458)
// ---------------------------------------------------------------------------

describe('replan as first-class node (#458)', () => {
  // Helper: build a minimal session/turn/plan scaffold so each test starts
  // with a plan that has 3 pending steps. Lets the tests focus on the
  // replan-specific assertions instead of repeating boilerplate.
  function freshPlanWithThreePendingSteps() {
    return runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 3,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
          { index: 3, name: 's3', description: '' },
        ],
      }),
    );
  }
  function locatePlan(state: ReturnType<typeof freshPlanWithThreePendingSteps>) {
    return state.rootNodes[0]!.children[0]!.children[0]!;
  }

  it('inserts exactly one replan marker per replan event', () => {
    const state = runAll(
      freshPlanWithThreePendingSteps(),
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 2,
        rationale: 'one fewer step',
      }),
    );
    const plan = locatePlan(state);
    const markers = plan.children.filter((c) => c.type === 'replan');
    expect(markers).toHaveLength(1);
    expect(markers[0]!.label).toBe('Replan #1');
  });

  it('creates a fresh marker per attempt — N replans yield N markers', () => {
    let state = freshPlanWithThreePendingSteps();
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      state = runAll(
        state,
        envelope('stream.plan_replanned', {
          planId: 'plan-1',
          replanAttempt: attempt,
          newStepCount: 1,
          rationale: `attempt ${attempt}`,
        }),
      );
    }
    const plan = locatePlan(state);
    const markers = plan.children.filter((c) => c.type === 'replan');
    expect(markers.map((m) => m.label)).toEqual([
      'Replan #1',
      'Replan #2',
      'Replan #3',
    ]);
    // Each marker has a unique id so the renderer can address them.
    const ids = new Set(markers.map((m) => m.id));
    expect(ids.size).toBe(3);
  });

  it('marker carries rationale + drop/add counts in metadata', () => {
    const state = runAll(
      freshPlanWithThreePendingSteps(),
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 5,
        rationale: 'need more depth',
      }),
    );
    const plan = locatePlan(state);
    const marker = plan.children.find((c) => c.type === 'replan');
    expect(marker).toBeDefined();
    expect(marker!.metadata?.['rationale']).toBe('need more depth');
    expect(marker!.metadata?.['replanAttempt']).toBe(1);
    expect(marker!.metadata?.['cancelledStepCount']).toBe(3); // all 3 were pending
    expect(marker!.metadata?.['newStepCount']).toBe(5);
  });

  it('counts dropped steps correctly when some were already completed', () => {
    let state = freshPlanWithThreePendingSteps();
    // Complete step 1 first.
    state = runAll(
      state,
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 3,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 1,
        tokensUsed: 1,
      }),
    );
    state = runAll(
      state,
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 2,
        rationale: 'rethink',
      }),
    );
    const marker = locatePlan(state).children.find((c) => c.type === 'replan');
    // Only s2 + s3 were pending → 2 dropped, not 3.
    expect(marker!.metadata?.['cancelledStepCount']).toBe(2);
  });

  it('counts only NEWLY-cancelled steps on a second replan (not previously-cancelled tombstones)', () => {
    // Self-review P1 catch: cancelledStepCount used to include any
    // step where status !== 'completed', which incorrectly re-counted
    // steps already cancelled by an earlier replan. The second
    // marker's "dropped N" metadata would lie — saying 4 when only 1
    // step was newly dropped by THIS replan event.
    let state = freshPlanWithThreePendingSteps();
    // Replan #1 cancels all 3 pending steps.
    state = runAll(
      state,
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 1,
        rationale: 'first pivot',
      }),
      // One new step starts running after the replan.
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 'new-1',
        stepIndex: 99,
        totalSteps: 1,
        stepName: 'new-1',
      }),
      // Replan #2 cancels ONLY that new running step.
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 2,
        newStepCount: 1,
        rationale: 'second pivot',
      }),
    );
    const plan = locatePlan(state);
    const markers = plan.children.filter((c) => c.type === 'replan');
    expect(markers).toHaveLength(2);
    // Replan #1: dropped all 3 originally-pending steps.
    expect(markers[0]!.metadata?.['cancelledStepCount']).toBe(3);
    // Replan #2: only 1 step was newly cancelled (the new running one).
    // The 3 already-cancelled tombstones from Replan #1 must NOT be
    // re-counted, even though they're still in plan.children.
    expect(markers[1]!.metadata?.['cancelledStepCount']).toBe(1);
  });

  it('marker is positioned AFTER cancelled steps so new lazy steps land after it', () => {
    let state = freshPlanWithThreePendingSteps();
    state = runAll(
      state,
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 1,
        rationale: 'pivot',
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 'new-step',
        stepIndex: 99,
        totalSteps: 1,
        stepName: 'new-step',
      }),
    );
    const plan = locatePlan(state);
    // Order should be: cancelled(s1) → cancelled(s2) → cancelled(s3) → replan → new running step
    const types = plan.children.map((c) => c.type);
    expect(types).toEqual(['step', 'step', 'step', 'replan', 'step']);
    const newRunning = plan.children[4]!;
    expect(newRunning.status).toBe('running');
    expect(newRunning.label).toBe('new-step');
  });

  it('marker has stable id across reducer iterations (same key for re-renders)', () => {
    // The id format is `${planId}::replan-${attempt}` so the renderer's
    // React key stays stable across re-renders. This pins the format so
    // a future refactor can't silently change the id and cause unmount/
    // remount thrash.
    const state = runAll(
      freshPlanWithThreePendingSteps(),
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 2,
        newStepCount: 1,
        rationale: 'r',
      }),
    );
    const marker = locatePlan(state).children.find((c) => c.type === 'replan');
    expect(marker!.id).toBe('plan-1::replan-2');
  });

  it('marker survives snapshot replay — same envelopes, same tree shape', () => {
    // The reducer is pure, so re-running the same envelope sequence on a
    // fresh tree must produce a tree with the same replan structure.
    // This is the property that makes snapshot.request safe for cron /
    // late-joining tabs — the marker is reducer state, not render state.
    const env = [
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 2,
        rationale: 'r',
      }),
    ];
    const liveState = runAll(freshTree(), ...env);
    const replayedState = runAll(freshTree(), ...env);
    const liveMarker = locatePlan(liveState).children.find((c) => c.type === 'replan');
    const replayMarker = locatePlan(replayedState).children.find((c) => c.type === 'replan');
    expect(liveMarker!.id).toBe(replayMarker!.id);
    expect(liveMarker!.metadata?.['rationale']).toBe(replayMarker!.metadata?.['rationale']);
  });
});

// ---------------------------------------------------------------------------
// Permission pause
// ---------------------------------------------------------------------------

describe('permission pause', () => {
  it('pauses the active leaf and stores the prompt', () => {
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      envelope('permission.request', {
        tool: 'bash',
        description: 'rm -rf /',
        requestId: 'perm-1',
      }),
    );
    const tool = findById(state, 't1');
    expect(tool.status).toBe('paused');
    expect(tool.awaitingInput).toBe(true);
    expect(tool.inputPrompt).toBe('rm -rf /');
    expect(tool.permissionRequestId).toBe('perm-1');
    // Stamp time the daemon paused — the panel uses this to drive the
    // countdown ring without a separate "I started waiting at X" event.
    expect(typeof tool.metadata?.['permissionRequestedAt']).toBe('number');
  });

  it('uses envelope.receivedAt (not Date.now) for permissionRequestedAt', () => {
    // Snapshot replay re-feeds aged events; using Date.now() in the
    // reducer would re-stamp every replayed permission.request with the
    // current wall clock and the panel's countdown would always show
    // the full 120 s for an already-aged request. Pin envelope-time
    // sourcing so the panel's deadline math survives reconnects.
    const aged = '2026-04-30T10:00:00.000Z';
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: aged,
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: aged,
      }),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      {
        eventId: nextEventId++,
        sessionId: 'sess-1',
        receivedAt: aged,
        event: {
          method: 'permission.request',
          params: { tool: 'bash', description: 'rm', requestId: 'perm-aged' },
        },
      } as DaemonEventEnvelope,
    );
    const tool = findById(state, 't1');
    expect(tool.metadata?.['permissionRequestedAt']).toBe(Date.parse(aged));
  });

  it('marks each parallel tool node awaiting via toolUseId disambiguation', () => {
    // User-reported (#437): three parallel wiki_search calls fired
    // three permission.requests, but the dashboard only marked ONE
    // node as awaiting (the most-recent activeNodeId) — operator
    // could only approve one from the dashboard. Daemon now stamps
    // toolUseId on each permission.request; reducer marks THAT node
    // instead of activeNodeId. All three should get awaitingInput.
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.tool_use', { id: 't1', name: 'wiki_search' }),
      envelope('stream.tool_use', { id: 't2', name: 'wiki_search' }),
      envelope('stream.tool_use', { id: 't3', name: 'wiki_search' }),
      envelope('permission.request', {
        tool: 'wiki_search',
        description: 'wiki_search 1',
        requestId: 'perm-1',
        toolUseId: 't1',
      }),
      envelope('permission.request', {
        tool: 'wiki_search',
        description: 'wiki_search 2',
        requestId: 'perm-2',
        toolUseId: 't2',
      }),
      envelope('permission.request', {
        tool: 'wiki_search',
        description: 'wiki_search 3',
        requestId: 'perm-3',
        toolUseId: 't3',
      }),
    );
    expect(findById(state, 't1').awaitingInput).toBe(true);
    expect(findById(state, 't1').permissionRequestId).toBe('perm-1');
    expect(findById(state, 't2').awaitingInput).toBe(true);
    expect(findById(state, 't2').permissionRequestId).toBe('perm-2');
    expect(findById(state, 't3').awaitingInput).toBe(true);
    expect(findById(state, 't3').permissionRequestId).toBe('perm-3');
  });

  it('moves activeNodeId to the awaiting node so the camera can pan it into view (#437)', () => {
    // User-reported: a permission-awaiting node off the right edge
    // had no visible chip — the operator couldn't see what to click.
    // Cause: pauseForPermission marked the node awaiting but didn't
    // touch activeNodeId, so the auto-scroll effect (which depends
    // on [activeNodeId, layout.nodes]) didn't re-evaluate. The
    // ExecutionTree comfort-zone guard handles the "no-op when
    // already in view" case; the reducer just needs to make the
    // awaiting node the auto-scroll target.
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.tool_use', { id: 't-prior', name: 'read_file' }),
      envelope('stream.tool_completed', {
        id: 't-prior',
        name: 'read_file',
        durationMs: 1,
        isError: false,
      }),
      envelope('stream.tool_use', { id: 't-perm', name: 'bash' }),
      envelope('permission.request', {
        tool: 'bash',
        description: 'rm -rf',
        requestId: 'perm-1',
        toolUseId: 't-perm',
      }),
    );
    expect(state.activeNodeId).toBe('t-perm');
    const tool = findById(state, 't-perm');
    expect(tool.awaitingInput).toBe(true);
  });

  it('disambiguates 8 parallel permissions even with arbitrary wire interleaving', () => {
    // Stress version of the parallel-permission test. Daemon's parallel
    // virtual threads can interleave stream.tool_use and
    // permission.request in any order; the reducer must produce a
    // tree where EVERY tool node ends up awaiting its OWN permission
    // request — no collisions, no missing chips, no buffered leftovers.
    //
    // Wire order constructed below mixes:
    //   - some tools whose tool_use arrives BEFORE their permission
    //   - some whose permission arrives BEFORE their tool_use (buffer path)
    //   - bursts of consecutive same-direction events
    //
    // Asserts each of the 8 nodes ends up with the expected
    // awaitingInput + permissionRequestId pairing — a UI consumer
    // walking the tree gets one chip per tool, addressable.
    const ids = ['t1', 't2', 't3', 't4', 't5', 't6', 't7', 't8'] as const;
    function tu(id: string) {
      return envelope('stream.tool_use', { id, name: 'wiki_search' });
    }
    function pr(toolUseId: string, requestId: string) {
      return envelope('permission.request', {
        tool: 'wiki_search',
        description: `wiki_search ${toolUseId}`,
        requestId,
        toolUseId,
      });
    }
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      // Adversarial interleaving covering every order pattern:
      tu('t1'),  pr('t1', 'p1'),                  // tu then perm (immediate)
      pr('t2', 'p2'),  tu('t2'),                   // perm then tu (buffer)
      tu('t3'),  tu('t4'),  pr('t3', 'p3'),        // burst of tus, then perms in order
      pr('t4', 'p4'),
      pr('t5', 'p5'),  pr('t6', 'p6'),             // burst of perms BEFORE their tus
      tu('t5'),  tu('t6'),                         // tus catch up — buffer drains both
      pr('t7', 'p7'),  tu('t8'),  tu('t7'),        // mixed: p7 buffered, t8 in, then t7
      pr('t8', 'p8'),                              // p8 immediate (t8 already exists)
    );

    for (let i = 0; i < ids.length; i += 1) {
      const id = ids[i]!;
      const expectedRid = `p${i + 1}`;
      const tool = findById(state, id);
      expect(tool.type).toBe('tool');
      expect(tool.awaitingInput).toBe(true);
      expect(tool.permissionRequestId).toBe(expectedRid);
      expect(tool.inputPrompt).toBe(`wiki_search ${id}`);
    }
    // Every buffered request was drained — no orphans hanging around
    // for tool nodes that never arrived.
    expect(state.pendingPermissionsByToolUseId).toEqual({});
  });

  it('buffers a permission.request whose tool node has not arrived yet', () => {
    // Race: parallel virtual threads on the daemon dispatch
    // permission.request and stream.tool_use independently — wire
    // order is not deterministic. If permission.request lands BEFORE
    // its corresponding stream.tool_use, the reducer must NOT fall
    // back to activeNodeId (would mark the wrong node). It buffers
    // the request keyed by toolUseId, and addToolNode applies the
    // marker when the matching node arrives. User symptom of the
    // unbuffered version: 3 permissions fired but only 2 chips
    // appeared in the dashboard (the third's permission silently
    // landed on the wrong node).
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      // permission arrives FIRST, no node t-late yet — must buffer.
      envelope('permission.request', {
        tool: 'wiki_search',
        description: 'wiki_search early',
        requestId: 'perm-late',
        toolUseId: 't-late',
      }),
      // Then the tool_use arrives — addToolNode drains the buffer.
      envelope('stream.tool_use', { id: 't-late', name: 'wiki_search' }),
    );
    const tool = findById(state, 't-late');
    expect(tool.awaitingInput).toBe(true);
    expect(tool.permissionRequestId).toBe('perm-late');
    // Buffer is empty after drain.
    expect(state.pendingPermissionsByToolUseId).toEqual({});
  });
});

// ---------------------------------------------------------------------------
// Permission resolution — local approve/deny + CLI-resolved + dismiss
// (issue #437 — browser-side panel; complements daemon-side #433)
// ---------------------------------------------------------------------------

describe('permission resolution (issue #437)', () => {
  /**
   * Helper: build the standard "session → turn → tool with pending
   * permission" tree all permission-resolution tests below start from.
   */
  function pausedToolTree(): ExecutionTree {
    return runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.tool_use', { id: 't1', name: 'edit_file' }),
      envelope('permission.request', {
        tool: 'edit_file',
        description: 'edit_file wants to write to /tmp/foo.txt',
        requestId: 'perm-1',
      }),
    );
  }

  it('approve flips paused → running, strips awaitingInput, records resolvedBy', () => {
    const before = pausedToolTree();
    const after = resolvePermissionLocally(before, 'perm-1', true);
    const tool = findById(after, 't1');
    expect(tool.status).toBe('running');
    expect(tool.awaitingInput).toBeUndefined();
    expect(tool.inputPrompt).toBeUndefined();
    expect(tool.permissionRequestId).toBeUndefined();
    expect(tool.metadata?.['resolvedBy']).toBe('browser');
    expect(tool.metadata?.['resolvedApproved']).toBe(true);
  });

  it('deny flips paused → cancelled', () => {
    const after = resolvePermissionLocally(pausedToolTree(), 'perm-1', false);
    const tool = findById(after, 't1');
    expect(tool.status).toBe('cancelled');
    expect(tool.metadata?.['resolvedApproved']).toBe(false);
  });

  it('is a no-op for an unknown requestId (idempotent on double-click)', () => {
    const before = pausedToolTree();
    const ghost = resolvePermissionLocally(before, 'perm-never', true);
    expect(ghost).toBe(before);
    // Second resolve on the same id post-strip is also a no-op — once
    // awaitingInput is gone, the find-by-permissionRequestId visit
    // returns null and the state is returned unchanged.
    const once = resolvePermissionLocally(before, 'perm-1', true);
    const twice = resolvePermissionLocally(once, 'perm-1', false);
    expect(twice).toBe(once);
  });

  it('detects CLI-resolved permission via stream.tool_completed (success)', () => {
    // CLI answered the permission first → daemon ran the tool → tool_completed
    // arrives while the node was awaitingInput. Reducer stamps
    // resolvedBy='cli' for diagnostics AND strips the panel-trigger
    // fields so the node stops advertising "click to approve" in the
    // tree. (Earlier behaviour kept awaitingInput=true to support a
    // brief "Approved via CLI" reveal in the panel; that flow caused
    // the panel to flash on auto-accept policies, so the new
    // click-to-open UX simply self-corrects to the daemon's verdict.)
    const before = pausedToolTree();
    const after = runAll(
      before,
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'edit_file',
        durationMs: 42,
        isError: false,
      }),
    );
    const tool = findById(after, 't1');
    expect(tool.status).toBe('completed');
    expect(tool.metadata?.['resolvedBy']).toBe('cli');
    expect(tool.metadata?.['resolvedApproved']).toBe(true);
    expect(tool.awaitingInput).toBeUndefined();
    expect(tool.permissionRequestId).toBeUndefined();
  });

  it('detects CLI-resolved permission via stream.tool_completed (denied)', () => {
    const before = pausedToolTree();
    const after = runAll(
      before,
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'edit_file',
        durationMs: 42,
        isError: true,
        error: 'Permission denied',
      }),
    );
    const tool = findById(after, 't1');
    expect(tool.status).toBe('failed');
    expect(tool.metadata?.['resolvedBy']).toBe('cli');
    expect(tool.metadata?.['resolvedApproved']).toBe(false);
    expect(tool.awaitingInput).toBeUndefined();
  });

  it('does not corrupt a CLI-resolved node when a tardy browser click arrives', () => {
    // Race scenario: tool_completed lands first (CLI raced and won).
    // completeToolNode strips awaitingInput / permissionRequestId and
    // stamps resolvedBy='cli'. A tardy resolvePermissionLocally call
    // (e.g. from click-to-open state that hasn't unmounted yet) finds
    // no awaiting node by id and is a no-op — the daemon's verdict
    // stays untouched.
    const cliResolved = runAll(
      pausedToolTree(),
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'edit_file',
        durationMs: 42,
        isError: false,
      }),
    );
    const after = resolvePermissionLocally(cliResolved, 'perm-1', false);
    expect(after).toBe(cliResolved); // referentially identical → no-op
    const tool = findById(after, 't1');
    expect(tool.status).toBe('completed');
    expect(tool.metadata?.['resolvedBy']).toBe('cli');
    expect(tool.metadata?.['resolvedApproved']).toBe(true);
  });

  it('does not stamp resolvedBy=cli when local resolve already won the race', () => {
    // Local Approve fires → resolvedBy='browser' stamped, awaitingInput
    // stripped. Daemon emits tool_completed afterwards (the tool ran
    // because we approved). Reducer must not overwrite resolvedBy.
    const after = runAll(
      resolvePermissionLocally(pausedToolTree(), 'perm-1', true),
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'edit_file',
        durationMs: 42,
        isError: false,
      }),
    );
    const tool = findById(after, 't1');
    expect(tool.status).toBe('completed');
    expect(tool.metadata?.['resolvedBy']).toBe('browser');
  });

  it('dismissPermissionPanel strips panel-trigger fields without touching status', () => {
    // Used by the panel's × button when the user closes without
    // responding. Status remains paused until the daemon resolves; we
    // only clear the panel-mounting flags so the overlay unmounts.
    const dismissed = dismissPermissionPanel(pausedToolTree(), 'perm-1');
    const tool = findById(dismissed, 't1');
    expect(tool.awaitingInput).toBeUndefined();
    expect(tool.inputPrompt).toBeUndefined();
    expect(tool.permissionRequestId).toBeUndefined();
    expect(tool.status).toBe('paused');
  });

  it('dismiss is a no-op for unknown requestId', () => {
    const before = pausedToolTree();
    const dismissed = dismissPermissionPanel(before, 'perm-never');
    expect(dismissed).toBe(before);
  });
});

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

describe('stats', () => {
  it('counts tools and turns + mirrors usage tokens', () => {
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.tool_use', { id: 't1', name: 'read_file' }),
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'read_file',
        durationMs: 1,
        isError: false,
      }),
      envelope('stream.tool_use', { id: 't2', name: 'bash' }),
      envelope('stream.tool_completed', {
        id: 't2',
        name: 'bash',
        durationMs: 1,
        isError: true,
        error: 'oops',
      }),
      envelope('stream.usage', {
        inputTokens: 50,
        totalInputTokens: 200,
        totalOutputTokens: 80,
      }),
    );
    expect(state.stats.totalTurns).toBe(1);
    expect(state.stats.totalTools).toBe(2);
    expect(state.stats.completedTools).toBe(1);
    expect(state.stats.failedTools).toBe(1);
    expect(state.stats.inputTokens).toBe(200);
    expect(state.stats.outputTokens).toBe(80);
  });
});

// ---------------------------------------------------------------------------
// activeNodeId tracking — auto-scroll target
// ---------------------------------------------------------------------------

describe('activeNodeId tracking', () => {
  it('walks down on starts and back up on completions', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
    expect(state.activeNodeId).toBe('sess-1');

    state = executionTreeReducer(
      state,
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
    expect(state.activeNodeId).toBe('req-1');

    state = executionTreeReducer(
      state,
      envelope('stream.tool_use', { id: 't1', name: 'read_file' }),
    );
    expect(state.activeNodeId).toBe('t1');

    state = executionTreeReducer(
      state,
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'read_file',
        durationMs: 1,
        isError: false,
      }),
    );
    // After tool completes, focus returns to the tool's parent. The
    // tool was minted under a synthetic thinking (no stream.thinking
    // arrived for this iteration), so the parent is `req-1:thinking`,
    // not the turn directly. The next tool_use under the same turn
    // still attaches to the same synthetic anchor naturally.
    expect(state.activeNodeId).toBe('req-1:thinking');
  });
});

// ---------------------------------------------------------------------------
// Replan → next step: lazy-create when the new step ID was never seen before
// ---------------------------------------------------------------------------

describe('replan → next step', () => {
  it('creates the new step under the plan when activateStep is the first sighting', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
        ],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 1,
        tokensUsed: 1,
      }),
      // Replan cancels s2 and signals a brand new step list (s3) coming next.
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 1,
        rationale: 'detour',
      }),
    );
    // Daemon now sends plan_step_started with a NEW index that the skeleton
    // never saw — without lazy-create this would silently no-op.
    state = runAll(
      state,
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's3',
        stepIndex: 99,
        totalSteps: 1,
        stepName: 's3 (replanned)',
      }),
    );
    const plan = state.rootNodes[0]!.children[0]!.children[0]!;
    // The new step takes the composed id because stepIndex=99 doesn't collide
    // with the cancelled tombstones (which sit at composed indices 1..3).
    const newStep = plan.children.find(
      (c) => c.id === stepNodeId('plan-1', 99) && c.status === 'running',
    );
    expect(newStep).toBeDefined();
    expect(newStep?.label).toBe('s3 (replanned)');
    // createdByReplan flag is only set when we needed to mint a synthetic id
    // (= cancelled tombstone existed at the composed slot). Index 99 had no
    // tombstone, so the flag stays false.
    expect(newStep?.metadata?.['createdByReplan']).toBe(false);
    expect(state.activeNodeId).toBe(newStep?.id);
  });
});

// ---------------------------------------------------------------------------
// Text deltas accumulate into a single text node under the active turn
// ---------------------------------------------------------------------------

describe('text accumulation', () => {
  it('appends deltas onto the same text-typed child of the turn', () => {
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.text', { delta: 'Hello, ' }),
      envelope('stream.text', { delta: 'world' }),
      envelope('stream.text', { delta: '!' }),
    );
    // Text-without-thinking now anchors on a synthetic thinking node
    // (so it stays in the ReAct chain instead of orphaning at the
    // turn level — see addReActFlowEdges in useTreeLayout).
    const turn = state.rootNodes[0]!.children[0]!;
    const thinking = turn.children.find((c) => c.type === 'thinking')!;
    expect(thinking).toBeDefined();
    const textChildren = thinking.children.filter((c) => c.type === 'text');
    expect(textChildren).toHaveLength(1);
    expect(textChildren[0]!.text).toBe('Hello, world!');
  });

  it('drops deltas that arrive without a running turn', () => {
    const state = runAll(
      freshTree(),
      envelope('stream.text', { delta: 'orphan' }),
    );
    expect(state.rootNodes).toHaveLength(0);
  });

  it('rolls thinking deltas into a single thinking child of the running turn', () => {
    // The reducer used to drop stream.thinking entirely (missing case in
    // the switch), so a turn that thought before tool-using showed up as
    // an empty parent. Mirrors the text behaviour: one thinking node per
    // turn, deltas concatenate.
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.thinking', { delta: 'Let me ' }),
      envelope('stream.thinking', { delta: 'consider…' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinkingChildren = turn.children.filter((c) => c.type === 'thinking');
    expect(thinkingChildren).toHaveLength(1);
    expect(thinkingChildren[0]!.text).toBe('Let me consider…');
    expect(thinkingChildren[0]!.label).toBe('thinking');
  });

  it('places text inside its iteration thinking, not as a sibling of the turn', () => {
    // Text in a Claude streaming response is per-LLM-call. Anchoring
    // it on the iteration's thinking lets multi-iteration turns keep
    // each call's narration locally bound — the text node doesn't
    // visually drift across the diagram as later iterations append.
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.thinking', { delta: 'reasoning' }),
      envelope('stream.text', { delta: 'response' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    expect(turn.children.map((c) => c.type)).toEqual(['thinking']);
    const thinking = turn.children[0]!;
    const textChildren = thinking.children.filter((c) => c.type === 'text');
    expect(textChildren).toHaveLength(1);
    expect(textChildren[0]!.text).toBe('response');
  });

  it('mints a separate text node per ReAct iteration (no cross-iteration accumulation)', () => {
    // The bug this guards against: with text keyed on the turn, every
    // iteration's text deltas would pile into one node that survives
    // all the layout reshuffles. Per-iteration text bubbles stay where
    // their iteration is.
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      // Iter 1: thinking → text → tool
      envelope('stream.thinking', { delta: 'iter 1 thought' }),
      envelope('stream.text', { delta: 'iter 1 narration' }),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      // Iter 2: thinking → text (final response)
      envelope('stream.thinking', { delta: 'iter 2 thought' }),
      envelope('stream.text', { delta: 'iter 2 final answer' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinkings = turn.children.filter((c) => c.type === 'thinking');
    expect(thinkings).toHaveLength(2);
    const text1 = thinkings[0]!.children.find((c) => c.type === 'text')!;
    const text2 = thinkings[1]!.children.find((c) => c.type === 'text')!;
    expect(text1.text).toBe('iter 1 narration');
    expect(text2.text).toBe('iter 2 final answer');
    // Texts have distinct ids — no leakage across iterations.
    expect(text1.id).not.toBe(text2.id);
  });

  it('attaches text to a synthetic thinking when no real thinking has been emitted', () => {
    // Extended thinking off, or model emitted text without any prior
    // thinking block. The reducer mints a synthetic thinking so the
    // text stays in the ReAct chain (addReActFlowEdges only iterates
    // thinking children of a turn — text directly under a turn would
    // be orphaned from the layout flow).
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.text', { delta: 'no thinking, just talk' }),
      envelope('stream.text', { delta: ' more talk' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    expect(turn.children.map((c) => c.type)).toEqual(['thinking']);
    const synthThinking = turn.children[0]!;
    expect(synthThinking.status).toBe('completed'); // synthetic, no real reasoning streamed
    const textChildren = synthThinking.children.filter((c) => c.type === 'text');
    expect(textChildren).toHaveLength(1);
    expect(textChildren[0]!.text).toBe('no thinking, just talk more talk');
  });

  it('uses a truncated text preview as the node label', () => {
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.thinking', { delta: 't' }),
      envelope('stream.text', {
        delta: 'this is a much longer response that exceeds the preview cap',
      }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const text = turn.children[0]!.children.find((c) => c.type === 'text')!;
    // Label is truncated and ends with the ellipsis sentinel.
    expect(text.label.length).toBeLessThanOrEqual(22);
    expect(text.label.endsWith('…')).toBe(true);
    // But the full text is preserved on the node itself for the
    // detail view / debugging.
    expect(text.text!.length).toBeGreaterThan(text.label.length);
  });
});

// ---------------------------------------------------------------------------
// ReAct iteration grouping: thinking owns the tools it spawned
// ---------------------------------------------------------------------------

describe('thinking-anchored tool grouping (ReAct iterations)', () => {
  function startedTurn(): ExecutionTree {
    return runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
  }

  it('attaches a tool_use under the most recent thinking node, not the turn', () => {
    // ReAct semantic: thinking is the cause, the tool call is its effect.
    // Without an anchor the tool would attach to the turn, making thinking
    // and the tool look like unrelated siblings instead of cause→effect.
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'I should run bash' }),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    expect(turn.children).toHaveLength(1);
    expect(turn.children[0]!.type).toBe('thinking');
    const thinking = turn.children[0]!;
    expect(thinking.children).toHaveLength(1);
    expect(thinking.children[0]!.id).toBe('t1');
    expect(thinking.children[0]!.type).toBe('tool');
  });

  it('places parallel tool_use calls from one LLM response under the same thinking', () => {
    // No thinking delta arrives between two parallel tool_uses (they come
    // from a single LLM streaming response), so the anchor stays valid for
    // both — they end up as siblings under the same thinking node.
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'parallel work' }),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      envelope('stream.tool_use', { id: 't2', name: 'read' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinking = turn.children[0]!;
    expect(thinking.children).toHaveLength(2);
    expect(thinking.children.map((c) => c.id).sort()).toEqual(['t1', 't2']);
    // Turn still gets the parallel flag for sidebar stats.
    expect(turn.parallel).toBe(true);
  });

  it('creates a new thinking node when a delta arrives after a tool_use (ReAct iteration boundary)', () => {
    // After tool_use the anchor is sealed: the model has finished one LLM
    // call. The next thinking delta is a new iteration and must mint a new
    // thinking node rather than concatenate into the previous one.
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'iter 1 thinking' }),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      envelope('stream.thinking', { delta: 'iter 2 thinking' }),
      envelope('stream.tool_use', { id: 't2', name: 'read' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    // Two thinking siblings under the turn, each with one tool child.
    const thinkingChildren = turn.children.filter((c) => c.type === 'thinking');
    expect(thinkingChildren).toHaveLength(2);
    expect(thinkingChildren[0]!.text).toBe('iter 1 thinking');
    expect(thinkingChildren[0]!.children.map((c) => c.id)).toEqual(['t1']);
    expect(thinkingChildren[1]!.text).toBe('iter 2 thinking');
    expect(thinkingChildren[1]!.children.map((c) => c.id)).toEqual(['t2']);
  });

  it('marks the thinking node completed when a tool_use seals it', () => {
    // Without this transition, the thinking node keeps its 'running'
    // status (and the renderer's pulse animation) indefinitely while
    // tools execute — the user's "thinking 不停 thinking 了就别闪了"
    // complaint.
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'reasoning' }),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinking = turn.children.find((c) => c.type === 'thinking')!;
    expect(thinking.status).toBe('completed');
  });

  it('marks the thinking node completed when a text response seals it', () => {
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'about to respond' }),
      envelope('stream.text', { delta: 'hi' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinking = turn.children.find((c) => c.type === 'thinking')!;
    expect(thinking.status).toBe('completed');
  });

  it('captures stopReason on the turn metadata for the renderer to badge', () => {
    // The dashboard's GrowingNode renders a small amber "⚠ max_tokens"
    // tag on truncated turns. The reducer just stamps the value onto
    // metadata; the renderer decides what to do with it.
    const state = runAll(
      startedTurn(),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      envelope('stream.turn_completed', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
        durationMs: 9999,
        toolCount: 1,
        stopReason: 'MAX_TOKENS',
      }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    expect(turn.status).toBe('completed');
    expect(turn.metadata?.['stopReason']).toBe('MAX_TOKENS');
  });

  it('omits stopReason when the daemon does not emit it (older versions)', () => {
    const state = runAll(
      startedTurn(),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      envelope('stream.turn_completed', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
        durationMs: 100,
        toolCount: 1,
      }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    expect(turn.metadata?.['stopReason']).toBeUndefined();
  });

  it('marks remaining running thinking/text nodes completed when the turn ends', () => {
    // Edge case: a turn ends while a delta-stream child is still
    // 'running' (for example, the model emitted thinking but the turn
    // closed before any tool_use/text ever sealed it). turn_completed
    // sweeps still-running thinking and text into 'completed' so the
    // pulse stops with the turn.
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'no tool, no text' }),
      envelope('stream.turn_completed', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
        durationMs: 50,
        toolCount: 0,
      }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    expect(turn.status).toBe('completed');
    const thinking = turn.children.find((c) => c.type === 'thinking')!;
    expect(thinking.status).toBe('completed');
  });

  it("provisionally completes previous iteration's running tools when next thinking starts", () => {
    // The daemon races: a new iteration's thinking deltas often
    // arrive before the previous iteration's tool_completed event.
    // Without reconciliation the previous tool would keep pulsing
    // while the new thinking pulses, looking visually parallel.
    // Mint-time sweep flips any still-running tool/text in the prior
    // subtree to completed; the real tool_completed event still
    // applies normally when it arrives (with duration / isError).
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'iter 1' }),
      envelope('stream.tool_use', { id: 'tool-A', name: 'bash' }),
      // No tool_completed yet — the daemon hasn't broadcast it. But
      // the next iteration's thinking delta arrives:
      envelope('stream.thinking', { delta: 'iter 2 (next call)' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinking1 = turn.children[0]!;
    const tool = thinking1.children.find((c) => c.id === 'tool-A')!;
    // Tool is now completed (provisionally) even though no
    // tool_completed event has arrived. New thinking is running.
    expect(tool.status).toBe('completed');
    const thinking2 = turn.children[1]!;
    expect(thinking2.status).toBe('running');
  });

  it('lets a real tool_completed event overwrite the provisional status', () => {
    // The provisional sweep marks running tools as 'completed'; a
    // later tool_completed event with isError=true must still flip
    // the tool to 'failed' (with the real error metadata).
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'iter 1' }),
      envelope('stream.tool_use', { id: 'tool-A', name: 'bash' }),
      envelope('stream.thinking', { delta: 'iter 2' }),
      // Real completion arrives late, with a failure result:
      envelope('stream.tool_completed', {
        id: 'tool-A',
        name: 'bash',
        durationMs: 1234,
        isError: true,
        error: 'exit code 1',
      }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinking1 = turn.children[0]!;
    const tool = thinking1.children.find((c) => c.id === 'tool-A')!;
    expect(tool.status).toBe('failed');
    expect(tool.error).toBe('exit code 1');
    expect(tool.duration).toBe(1234);
  });

  it('mints a synthetic thinking + text node when the next iteration emits text without a preceding thinking event', () => {
    // The bug this guards against: an iteration that emits text
    // WITHOUT a preceding thinking event (extended thinking off or
    // budget exhausted) used to silently merge into the previous
    // iteration's text node — the dashboard rendered all iterations'
    // narrations as siblings of one fat thinking, which dagre laid
    // out as a vertical stack that read as "5 parallel responses".
    //
    // Fix: when text starts a new iteration without a fresh thinking
    // delta, mint a SYNTHETIC thinking node (status=completed, since
    // the model already finished thinking by the time we infer the
    // boundary) so the new text/tools attach to a dedicated anchor.
    // The result is a chain shape: th_1 → text_1 → tool → synth_th_2 →
    // text_2 — exactly what the user expects from a multi-iteration
    // ReAct turn.
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'iter 1' }),
      envelope('stream.text', { delta: 'narration before tool' }),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'bash',
        durationMs: 100,
        isError: false,
      }),
      // No stream.thinking before this — model went straight from
      // tool result to final response.
      envelope('stream.text', { delta: 'final answer line 1' }),
      envelope('stream.text', { delta: ' line 2' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinkings = turn.children.filter((c) => c.type === 'thinking');
    expect(thinkings).toHaveLength(2);
    const realThinking = thinkings[0]!;
    const syntheticThinking = thinkings[1]!;
    // Real thinking has its narration text + its tool child (under text)
    expect(realThinking.text).toBe('iter 1');
    const realText = realThinking.children.find((c) => c.type === 'text')!;
    expect(realText.text).toBe('narration before tool');
    // Synthetic thinking has the final response under it
    expect(syntheticThinking.text).toBe('');
    expect(syntheticThinking.status).toBe('completed');
    const synthText = syntheticThinking.children.find((c) => c.type === 'text')!;
    expect(synthText.text).toBe('final answer line 1 line 2');
    expect(realText.id).not.toBe(synthText.id);
  });

  it('attaches tool_use as a child of the iteration narration when text exists', () => {
    // Causal chain: thinking → narration ("I'll call A and B") → tools
    // (the actions that narration described). When the iteration emits
    // text before tools, the tools structurally hang off the text node,
    // not the bare thinking — that puts narration in the right place
    // visually as the immediate cause of the tools.
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'plan: call A and B' }),
      envelope('stream.text', { delta: "I'll call A and B" }),
      envelope('stream.tool_use', { id: 'tA', name: 'bash' }),
      envelope('stream.tool_use', { id: 'tB', name: 'read' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinking = turn.children[0]!;
    const text = thinking.children.find((c) => c.type === 'text')!;
    // Tools hang off text, NOT off thinking.
    const toolsUnderText = text.children.filter((c) => c.type === 'tool');
    expect(toolsUnderText.map((t) => t.id).sort()).toEqual(['tA', 'tB']);
    const toolsUnderThinking = thinking.children.filter((c) => c.type === 'tool');
    expect(toolsUnderThinking).toHaveLength(0);
    // Narration is marked completed once tools start (the model stopped
    // talking and started acting); pulse stops in sync with thinking.
    expect(text.status).toBe('completed');
    expect(thinking.status).toBe('completed');
  });

  it('moves activeNodeId to the new thinking so the camera follows it (#437)', () => {
    // Without this, the camera stayed on the just-completed tool when
    // a new ReAct iteration's thinking arrived — the new thinking
    // landed off the right edge of the viewport (downstream of the
    // tool) and only became visible when a later text delta moved
    // activeNodeId, by which point the canvas appeared to "jump".
    // mintIterationThinking now sets activeNodeId = synth_id so the
    // auto-scroll effect pans to the new node as it arrives. The
    // append-delta path keeps activeNodeId on the streaming thinking
    // so a long thought doesn't drift off-screen.
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'iter 1 thought' }),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'bash',
        durationMs: 12,
        isError: false,
      }),
      envelope('stream.thinking', { delta: 'iter 2 thought' }),
    );
    // After iter-2 thinking arrives, activeNodeId should point at the
    // new thinking (req-1:thinking:1 — the second thinking under the
    // turn) so the auto-scroll effect in ExecutionTree pans there.
    expect(state.activeNodeId).toBe('req-1:thinking:1');
    // Same-iteration delta keeps focus on the same thinking.
    const after = executionTreeReducer(
      state,
      envelope('stream.thinking', { delta: ' more' }),
    );
    expect(after.activeNodeId).toBe('req-1:thinking:1');
  });

  it('synthesises a thinking parent when a tool_use arrives with no preceding thinking', () => {
    // Extended thinking disabled (or model emitted tool_use without
    // any thinking delta): addToolNode mints a synthetic thinking
    // (status=completed) under the turn so the tool has a proper
    // ReAct parent. Without this the tool became a direct child of
    // the turn and the next iteration's text → minted-thinking pair
    // appeared visually parallel to the tool — the user's complaint
    // on the "after permission" diagram (#437).
    const state = runAll(
      startedTurn(),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    // turn → synthetic thinking → tool
    expect(turn.children).toHaveLength(1);
    const thinking = turn.children[0]!;
    expect(thinking.type).toBe('thinking');
    expect(thinking.status).toBe('completed');
    expect(thinking.children).toHaveLength(1);
    expect(thinking.children[0]!.id).toBe('t1');
    expect(thinking.children[0]!.type).toBe('tool');
  });

  it('chains a synthesised thinking into the next iteration when no real thinking arrives', () => {
    // First tool_use minted thinking_1; the final text arrives with
    // no real thinking either, so appendTextToCurrentTurn mints
    // thinking_2. The two synthetic thinkings under the turn give
    // addReActFlowEdges what it needs to draw the horizontal flow
    // chain (thinking_1 → tool → thinking_2 → text).
    const state = runAll(
      startedTurn(),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'bash',
        durationMs: 12,
        isError: false,
      }),
      envelope('stream.text', { delta: 'd.txt 已创建' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const thinkings = turn.children.filter((c) => c.type === 'thinking');
    expect(thinkings).toHaveLength(2);
    // Tool lives under thinking_1; text lives under thinking_2.
    const t1 = findById(state, 't1');
    expect(t1.type).toBe('tool');
    const text = turn.children.find(
      (c) => c.type === 'thinking' && c.children.some((cc) => cc.type === 'text'),
    );
    expect(text).toBeDefined();
  });

  it('resets the anchor across turns so a new turn starts a fresh ReAct loop', () => {
    // Without the reset, turn 2's first tool would graft onto turn 1's
    // last thinking node — which is in a different subtree and not
    // running.
    const state = runAll(
      startedTurn(),
      envelope('stream.thinking', { delta: 'turn 1 thought' }),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
      envelope('stream.turn_completed', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
        durationMs: 100,
        toolCount: 1,
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-2',
        turnNumber: 2,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.tool_use', { id: 't2', name: 'read' }),
    );
    // Turn 2's first tool synthesises ITS OWN thinking — addToolNode
    // does this when no real thinking event arrived for the new
    // iteration. The synthetic thinking is fresh-per-turn (turn 1's
    // anchor was wiped by addTurnNode), so currentThinkingId now
    // points at turn 2's synthetic thinking, not turn 1's real one.
    expect(state.currentThinkingId).toBe('req-2:thinking');
    const turn2 = state.rootNodes[0]!.children[1]!;
    expect(turn2.children).toHaveLength(1);
    expect(turn2.children[0]!.type).toBe('thinking');
    expect(turn2.children[0]!.children[0]!.id).toBe('t2');
  });
});

// ---------------------------------------------------------------------------
// Sub-agent: completion matches the most-recent running entry by agentType
// ---------------------------------------------------------------------------

describe('subagent completion', () => {
  it('marks the running subagent of matching agentType as completed', () => {
    const state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.subagent.start', {
        agentType: 'skill:commit',
        prompt: 'commit changes',
      }),
      envelope('stream.subagent.end', { agentType: 'skill:commit' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const sub = turn.children.find((c) => c.type === 'subagent')!;
    expect(sub.status).toBe('completed');
    expect(sub.metadata?.['agentType']).toBe('skill:commit');
  });

  it('subagent.end without a matching active subagent is a no-op', () => {
    const before = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
    const after = runAll(
      before,
      envelope('stream.subagent.end', { agentType: 'skill:nope' }),
    );
    // Tree, active context, and stats all stay byte-equal — a no-op end signal
    // for a subagent that never started must not corrupt anything. The
    // watermark IS expected to advance: the dispatch happened, just produced
    // no tree changes, and the reconnect-dedup logic depends on every
    // accepted envelope bumping lastEventId.
    expect(after.rootNodes).toEqual(before.rootNodes);
    expect(after.activeNodeId).toBe(before.activeNodeId);
    expect(after.stats).toEqual(before.stats);
    expect(after.nextSyntheticId).toBe(before.nextSyntheticId);
    expect(after.lastEventId).toBeGreaterThan(before.lastEventId);
  });
});

// ---------------------------------------------------------------------------
// Replan with colliding stepIndex — cancelled tombstone must NOT be resurrected
// ---------------------------------------------------------------------------

describe('replan with colliding stepIndex', () => {
  it('mints a synthetic step instead of flipping the cancelled tombstone', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 'old-1', description: '' },
          { index: 2, name: 'old-2', description: '' },
        ],
      }),
      // Replan immediately — both pending steps become cancelled tombstones.
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 1,
        rationale: 'detour',
      }),
    );
    // Daemon now resets stepIndex and emits 1 again for the NEW first step.
    state = runAll(
      state,
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 'new-1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 'new-1',
      }),
    );
    const plan = state.rootNodes[0]!.children[0]!.children[0]!;
    // Four children now (#458): two cancelled tombstones (old-1, old-2) +
    // the synthetic 'replan' marker + one new running step.
    expect(plan.children).toHaveLength(4);
    const tombstone = plan.children.find(
      (c) => c.id === stepNodeId('plan-1', 1) && c.status === 'cancelled',
    );
    const replanMarker = plan.children.find((c) => c.type === 'replan');
    const replacement = plan.children.find(
      (c) => c.metadata?.['createdByReplan'] === true && c.status === 'running',
    );
    expect(tombstone).toBeDefined();
    expect(tombstone?.label).toBe('old-1');
    expect(replanMarker).toBeDefined();
    expect(replacement).toBeDefined();
    expect(replacement?.label).toBe('new-1');
    expect(state.activeNodeId).toBe(replacement?.id);
  });
});

// ---------------------------------------------------------------------------
// Replan after a successful step — the completed step's history must survive
// ---------------------------------------------------------------------------

describe('replan preserves non-pending tombstones (completed / failed)', () => {
  it('replanning after a successful step keeps the completed record intact', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 'analyze', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 'analyze',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 'analyze',
        success: true,
        durationMs: 12,
        tokensUsed: 100,
      }),
      // Replan AFTER step 1 completed. replacePlanSteps only flips
      // not-yet-completed steps to cancelled, so the completed step stays
      // completed. activateStep for the new stepIndex=1 must NOT overwrite
      // that completed history.
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 1,
        rationale: 'still need more',
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1-new',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 'deeper analysis',
      }),
    );
    const plan = state.rootNodes[0]!.children[0]!.children[0]!;
    const completedOriginal = plan.children.find(
      (c) => c.id === stepNodeId('plan-1', 1),
    );
    expect(completedOriginal?.status).toBe('completed');
    expect(completedOriginal?.label).toBe('analyze');
    const replacement = plan.children.find(
      (c) => c.metadata?.['createdByReplan'] === true && c.status === 'running',
    );
    expect(replacement?.label).toBe('deeper analysis');
  });
});

// ---------------------------------------------------------------------------
// completeStep finds the running step under the plan even when active focus
// has moved to a child tool/text. Without this guard, replanned-step
// completions would silently no-op and leave the step stuck running.
// ---------------------------------------------------------------------------

describe('completeStep resolves replanned synthetic steps after focus moves', () => {
  it('completes the synthetic-id replan step when activeNodeId points at a tool', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1-old', description: '' }],
      }),
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 1,
        rationale: 'changed approach',
      }),
      // After replan, daemon resets stepIndex. activateStep mints a synthetic
      // id since the composed id is now a cancelled tombstone.
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1-replan',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1-replan',
      }),
      // Real flow: a tool runs inside the step. activeNodeId moves off the
      // step to the tool.
      envelope('stream.tool_use', { id: 't1', name: 'read_file' }),
    );
    expect(state.activeNodeId).toBe('t1');
    // Now the step completes. The composed id ("plan-1:step:1") matches the
    // cancelled tombstone, NOT the running synthetic step. activeFallback
    // would have failed (active is a tool). The fix walks the plan children
    // for the running step.
    state = runAll(
      state,
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1-replan',
        stepIndex: 1,
        stepName: 's1-replan',
        success: true,
        durationMs: 50,
        tokensUsed: 200,
      }),
    );
    const plan = state.rootNodes[0]!.children[0]!.children[0]!;
    const tombstone = plan.children.find(
      (c) => c.id === stepNodeId('plan-1', 1),
    );
    const replacement = plan.children.find(
      (c) => c.metadata?.['createdByReplan'] === true,
    );
    expect(tombstone?.status).toBe('cancelled');
    expect(replacement?.status).toBe('completed');
  });
});

// ---------------------------------------------------------------------------
// Step subtree convergence (#485): when stream.tool_completed is lost in
// transit, the tool node would otherwise stay 'running' under a step that
// has long since finished. completeStep walks the step subtree and flips
// any still-running descendants to a terminal state.
// ---------------------------------------------------------------------------

describe('completeStep converges still-running descendants (#485)', () => {
  function planStartedWithToolRunning() {
    return runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
      envelope('stream.tool_use', { id: 't1', name: 'read_file' }),
    );
  }

  it('flips a running tool to completed and bumps completedTools when the step succeeds', () => {
    const before = planStartedWithToolRunning();
    expect(findById(before, 't1').status).toBe('running');
    const beforeCompletedTools = before.stats.completedTools;

    // Note: stream.tool_completed deliberately omitted to simulate event loss.
    const after = runAll(
      before,
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 100,
        tokensUsed: 50,
      }),
    );

    const step = findById(after, stepNodeId('plan-1', 1));
    expect(step.status).toBe('completed');
    expect(findById(after, 't1').status).toBe('completed');
    expect(after.stats.completedTools).toBe(beforeCompletedTools + 1);
    expect(after.stats.failedTools).toBe(before.stats.failedTools);
  });

  it('flips a running tool to cancelled and bumps failedTools when the step fails', () => {
    const before = planStartedWithToolRunning();
    const beforeFailedTools = before.stats.failedTools;

    const after = runAll(
      before,
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: false,
        durationMs: 100,
        tokensUsed: 50,
      }),
    );

    expect(findById(after, stepNodeId('plan-1', 1)).status).toBe('failed');
    expect(findById(after, 't1').status).toBe('cancelled');
    expect(after.stats.failedTools).toBe(beforeFailedTools + 1);
    expect(after.stats.completedTools).toBe(before.stats.completedTools);
  });

  it('does not double-count a tool that already completed normally', () => {
    const before = runAll(
      planStartedWithToolRunning(),
      envelope('stream.tool_completed', { id: 't1', name: 'read_file', isError: false, durationMs: 10 }),
    );
    expect(findById(before, 't1').status).toBe('completed');
    const beforeCompletedTools = before.stats.completedTools;

    const after = runAll(
      before,
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 100,
        tokensUsed: 50,
      }),
    );

    expect(findById(after, 't1').status).toBe('completed');
    // Tool was already counted by completeToolNode; convergence must not
    // bump completedTools again.
    expect(after.stats.completedTools).toBe(beforeCompletedTools);
  });

  it('converges multiple parallel running tools under the same step', () => {
    // Two parallel tool_use events from the same LLM response — neither
    // tool_completed arrives, so both stay running. plan_step_completed
    // must flip both and bump completedTools by 2.
    const before = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
      envelope('stream.tool_use', { id: 't1', name: 'read_file' }),
      envelope('stream.tool_use', { id: 't2', name: 'read_file' }),
    );
    expect(findById(before, 't1').status).toBe('running');
    expect(findById(before, 't2').status).toBe('running');
    const beforeCompletedTools = before.stats.completedTools;

    const after = runAll(
      before,
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 100,
        tokensUsed: 50,
      }),
    );

    expect(findById(after, 't1').status).toBe('completed');
    expect(findById(after, 't2').status).toBe('completed');
    expect(after.stats.completedTools).toBe(beforeCompletedTools + 2);
  });
});

// ---------------------------------------------------------------------------
// Plan subtree convergence (#485 PR 2/3): when a step-level completion event
// is lost entirely, completeStep never runs but the plan's terminal event
// still arrives. completePlan / failPlan walk the plan subtree to converge
// any still-running step (and its still-running tool children) so the plan
// node and its descendants render a coherent terminal state.
// ---------------------------------------------------------------------------

describe('completePlan / failPlan converge still-running descendants (#485)', () => {
  function planWithRunningStepAndTool() {
    return runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
      envelope('stream.tool_use', { id: 't1', name: 'read_file' }),
    );
  }

  it('completePlan(success=true) converges a still-running step + tool to completed', () => {
    const before = planWithRunningStepAndTool();
    const beforeCompletedTools = before.stats.completedTools;
    expect(findById(before, stepNodeId('plan-1', 1)).status).toBe('running');
    expect(findById(before, 't1').status).toBe('running');

    // Step-level completion deliberately omitted — only the plan-level event
    // arrives, simulating a lost stream.plan_step_completed.
    const after = runAll(
      before,
      envelope('stream.plan_completed', {
        planId: 'plan-1',
        success: true,
        totalDurationMs: 200,
        stepsCompleted: 1,
        totalSteps: 1,
      }),
    );

    expect(findById(after, 'plan-1').status).toBe('completed');
    expect(findById(after, stepNodeId('plan-1', 1)).status).toBe('completed');
    expect(findById(after, 't1').status).toBe('completed');
    expect(after.stats.completedTools).toBe(beforeCompletedTools + 1);
  });

  it('completePlan(success=false) flips descendants to cancelled and bumps failedTools', () => {
    const before = planWithRunningStepAndTool();
    const beforeFailedTools = before.stats.failedTools;

    const after = runAll(
      before,
      envelope('stream.plan_completed', {
        planId: 'plan-1',
        success: false,
        totalDurationMs: 200,
        stepsCompleted: 0,
        totalSteps: 1,
      }),
    );

    expect(findById(after, 'plan-1').status).toBe('failed');
    expect(findById(after, stepNodeId('plan-1', 1)).status).toBe('cancelled');
    expect(findById(after, 't1').status).toBe('cancelled');
    expect(after.stats.failedTools).toBe(beforeFailedTools + 1);
  });

  it('failPlan (escalation) converges descendants to cancelled and bumps failedTools', () => {
    const before = planWithRunningStepAndTool();
    const beforeFailedTools = before.stats.failedTools;

    const after = runAll(
      before,
      envelope('stream.plan_escalated', {
        planId: 'plan-1',
        reason: 'replanner gave up',
      }),
    );

    const plan = findById(after, 'plan-1');
    expect(plan.status).toBe('failed');
    expect(plan.error).toBe('replanner gave up');
    expect(findById(after, stepNodeId('plan-1', 1)).status).toBe('cancelled');
    expect(findById(after, 't1').status).toBe('cancelled');
    expect(after.stats.failedTools).toBe(beforeFailedTools + 1);
  });

  it('completePlan does not double-count tools whose step already completed normally', () => {
    const before = runAll(
      planWithRunningStepAndTool(),
      envelope('stream.tool_completed', {
        id: 't1',
        name: 'read_file',
        isError: false,
        durationMs: 10,
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 100,
        tokensUsed: 50,
      }),
    );
    expect(findById(before, stepNodeId('plan-1', 1)).status).toBe('completed');
    expect(findById(before, 't1').status).toBe('completed');
    const beforeCompletedTools = before.stats.completedTools;

    const after = runAll(
      before,
      envelope('stream.plan_completed', {
        planId: 'plan-1',
        success: true,
        totalDurationMs: 200,
        stepsCompleted: 1,
        totalSteps: 1,
      }),
    );

    // Step + tool already terminal before plan_completed, so the convergence
    // walk finds nothing to flip and stats stay put.
    expect(after.stats.completedTools).toBe(beforeCompletedTools);
  });
});

// ---------------------------------------------------------------------------
// Step events arriving before plan_created — don't dangle activeNodeId
// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// parentStepId on stream.tool_use (#485 PR 3/3): the daemon tags each
// tool_use with the composed step id ({planId}:step:{1-based-index}) so
// the reducer can attribute the tool to the right step even when events
// arrive out of order (e.g. plan_step_completed overtaking a late
// tool_use).
// ---------------------------------------------------------------------------

describe('stepNodeId wire format guard (#485)', () => {
  // The daemon hardcodes the same `${planId}:step:${1-based-index}` form
  // when emitting parentStepId on stream.tool_use (see
  // StreamingAgentHandler.java — the executePlannedPrompt and
  // executeResumedPlan listeners both compose it inline). If this format
  // ever changes here without a matching daemon change, every tool_use
  // would silently stop attributing to its step. Lock the contract so a
  // format change immediately fails CI on the dashboard side.
  it('produces the exact form the daemon emits as parentStepId', () => {
    expect(stepNodeId('plan-1', 1)).toBe('plan-1:step:1');
    expect(stepNodeId('uuid-foo-bar', 42)).toBe('uuid-foo-bar:step:42');
  });
});

describe('addToolNode honours parentStepId from daemon (#485)', () => {
  it('attaches a tool to the explicit parent step even after step has flipped to completed', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 100,
        tokensUsed: 50,
      }),
    );
    const stepId = stepNodeId('plan-1', 1);
    expect(findById(state, stepId).status).toBe('completed');
    const childCountBefore = findById(state, stepId).children.length;

    state = runAll(
      state,
      envelope('stream.tool_use', {
        id: 't1',
        name: 'read_file',
        parentStepId: stepId,
      }),
    );

    const stepAfter = findById(state, stepId);
    expect(stepAfter.children.length).toBeGreaterThan(childCountBefore);
    const tool = stepAfter.children.find((c) => c.id === 't1');
    expect(tool).toBeDefined();
    expect(tool?.type).toBe('tool');
  });

  it('falls back to the heuristic when parentStepId is missing (regression guard)', () => {
    const before = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
    );
    const after = runAll(
      before,
      envelope('stream.tool_use', { id: 't1', name: 'read_file' }),
    );
    const step = findById(after, stepNodeId('plan-1', 1));
    // Tool can sit directly under the step or under a synthesized
    // thinking node beneath it — assert the subtree contains it.
    function hasDescendant(node: ExecutionNode, id: string): boolean {
      if (node.children.some((c) => c.id === id)) return true;
      return node.children.some((c) => hasDescendant(c, id));
    }
    expect(hasDescendant(step, 't1')).toBe(true);
  });

  it('does not mint an orphan synthetic thinking under explicit override (CodeRabbit #488)', () => {
    // Regression guard: when explicit parentStepId routes the tool
    // directly to the step, the iteration-boundary synthesis above
    // must NOT mint a thinking node — the tool would bypass it,
    // leaving an empty completed thinking hanging off the step.
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 100,
        tokensUsed: 50,
      }),
    );
    const stepId = stepNodeId('plan-1', 1);
    state = runAll(
      state,
      envelope('stream.tool_use', {
        id: 't1',
        name: 'read_file',
        parentStepId: stepId,
      }),
    );

    const stepAfter = findById(state, stepId);
    const orphanThinking = stepAfter.children.find(
      (c) => c.type === 'thinking' && c.children.length === 0,
    );
    expect(orphanThinking).toBeUndefined();
    // Tool itself should be a direct child of the step under override.
    expect(stepAfter.children.find((c) => c.id === 't1')).toBeDefined();
  });

  it('skips override when parentStepId resolves to a replan tombstone (Codex #488)', () => {
    // After replan, the failed/cancelled skeleton at the composed id
    // (e.g. plan-1:step:1) sticks around as a tombstone, and the new
    // running step gets a synthetic `:r{n}` id under the same plan.
    // The daemon still tags subsequent tool_use with the composed
    // form. If the reducer naively trusted that, all post-replan
    // tools would attach to the cancelled step. The fix: skip
    // override when the resolved step is 'cancelled', falling back
    // to the heuristic which finds the live synthetic step.
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 1,
        rationale: 'changed approach',
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1-replan',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1-replan',
      }),
    );
    const tombstoneId = stepNodeId('plan-1', 1);
    expect(findById(state, tombstoneId).status).toBe('cancelled');
    const plan = state.rootNodes[0]!.children[0]!.children[0]!;
    const liveStep = plan.children.find(
      (c) => c.metadata?.['createdByReplan'] === true,
    );
    expect(liveStep?.status).toBe('running');

    state = runAll(
      state,
      envelope('stream.tool_use', {
        id: 't1',
        name: 'read_file',
        // Daemon's hardcoded composed form — collides with the tombstone.
        parentStepId: tombstoneId,
      }),
    );

    // Tombstone gets no children; live synthetic step gets the tool.
    const tombstoneAfter = findById(state, tombstoneId);
    expect(tombstoneAfter.children.find((c) => c.id === 't1')).toBeUndefined();
    function hasDescendant(node: ExecutionNode, id: string): boolean {
      if (node.children.some((c) => c.id === id)) return true;
      return node.children.some((c) => hasDescendant(c, id));
    }
    const liveStepAfter = findById(state, liveStep!.id);
    expect(hasDescendant(liveStepAfter, 't1')).toBe(true);
  });

  it('preserves live-scope anchors when an explicit-parent tool arrives late (Codex #488)', () => {
    // Step 1 runs and emits a thinking + tool that completes. Step 2
    // starts and emits its own thinking — currentThinkingId now points
    // to step 2's live thinking. A delayed tool_use for step 1 arrives
    // (parentStepId resolves to the completed step 1). Without the
    // override-aware guards, this late event would flip step 2's
    // running thinking to completed, seal thinkingSealed, and move
    // activeNodeId — breaking the rendering of step 2's subsequent
    // events. The fix: under explicitOverride, leave global anchors
    // untouched.
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
        ],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 50,
        tokensUsed: 25,
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's2',
        stepIndex: 2,
        totalSteps: 2,
        stepName: 's2',
      }),
      // Step 2's live iteration bookkeeping: thinking + first tool.
      envelope('stream.thinking', { delta: 'planning step 2…' }),
    );
    const liveThinkingIdBefore = state.currentThinkingId;
    const liveActiveBefore = state.activeNodeId;
    const liveThinkingSealedBefore = state.thinkingSealed;
    expect(liveThinkingIdBefore).not.toBeNull();

    // Delayed tool_use for step 1 (completed). parentStepId routes it
    // back to step 1. This must not disturb step 2's live anchors.
    state = runAll(
      state,
      envelope('stream.tool_use', {
        id: 't-late',
        name: 'read_file',
        parentStepId: stepNodeId('plan-1', 1),
      }),
    );

    expect(state.currentThinkingId).toBe(liveThinkingIdBefore);
    expect(state.activeNodeId).toBe(liveActiveBefore);
    expect(state.thinkingSealed).toBe(liveThinkingSealedBefore);
    // Step 2's live thinking must still be running.
    const liveThinking = findById(state, liveThinkingIdBefore!);
    expect(liveThinking.status).toBe('running');
  });

  it('forces override when explicit + heuristic agree but candidate anchor is stale (Codex #488)', () => {
    // Step 1 ends with currentTextId set to its final-text node. Step 2
    // starts (activateStep moves activeNodeId but does NOT clear the
    // text/thinking anchors). Step 2's first tool_use arrives with
    // parentStepId pointing at step 2.
    //
    // Without the candidate-inside-explicit guard, explicitParentStep
    // and heuristicScope both resolve to step 2 → explicitOverride
    // stays false → anchorId falls through to the leftover step-1
    // currentTextId, and the step-2 tool attaches under step 1's text.
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
        ],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      // Step 1 emits text → currentTextId is set to a node under step 1.
      envelope('stream.text', { delta: 'finished step 1' }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 50,
        tokensUsed: 25,
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's2',
        stepIndex: 2,
        totalSteps: 2,
        stepName: 's2',
      }),
    );
    // Confirm the leftover anchor really points into step 1's subtree.
    function hasDescendant(node: ExecutionNode, id: string): boolean {
      if (node.children.some((c) => c.id === id)) return true;
      return node.children.some((c) => hasDescendant(c, id));
    }
    const step1Id = stepNodeId('plan-1', 1);
    const step2Id = stepNodeId('plan-1', 2);
    const step1 = findById(state, step1Id);
    expect(state.currentTextId).not.toBeNull();
    expect(hasDescendant(step1, state.currentTextId!)).toBe(true);

    // Step 2's first tool_use, daemon-tagged with parentStepId=step 2.
    state = runAll(
      state,
      envelope('stream.tool_use', {
        id: 't1',
        name: 'read_file',
        parentStepId: step2Id,
      }),
    );

    // Tool must end up in step 2's subtree, NOT under step 1's leftover text.
    expect(hasDescendant(findById(state, step2Id), 't1')).toBe(true);
    expect(hasDescendant(findById(state, step1Id), 't1')).toBe(false);
  });

  it('falls back to the heuristic when parentStepId references a non-existent node', () => {
    const before = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
    );
    const after = runAll(
      before,
      envelope('stream.tool_use', {
        id: 't1',
        name: 'read_file',
        parentStepId: 'plan-NEVER-EXISTED:step:99',
      }),
    );
    const step = findById(after, stepNodeId('plan-1', 1));
    // Tool can sit directly under the step or under a synthesized
    // thinking node beneath it — assert the subtree contains it.
    function hasDescendant(node: ExecutionNode, id: string): boolean {
      if (node.children.some((c) => c.id === id)) return true;
      return node.children.some((c) => hasDescendant(c, id));
    }
    expect(hasDescendant(step, 't1')).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// parentStepId on stream.thinking / stream.text (#485 follow-up): the
// daemon tags thinking + text deltas with the same composed step id it
// already attaches to tool_use, so iteration anchors stay routed to the
// right step even when activeNodeId or currentTextId / currentThinkingId
// belong to a previous step's iteration.
// ---------------------------------------------------------------------------

describe('appendThinkingToCurrentTurn honours parentStepId (#485 follow-up)', () => {
  function planWithStep1Thinking() {
    // Step 1 emits thinking, then completes. currentThinkingId is left
    // pointing at step 1's thinking node (sealed once the iteration
    // ends, but still inside step 1's subtree).
    return runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
        ],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      envelope('stream.thinking', { delta: 'step 1 reasoning' }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 50,
        tokensUsed: 25,
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's2',
        stepIndex: 2,
        totalSteps: 2,
        stepName: 's2',
      }),
    );
  }

  it('routes a thinking delta to the explicit step even when current anchor is from a prior step', () => {
    let state = planWithStep1Thinking();
    const step1Id = stepNodeId('plan-1', 1);
    const step2Id = stepNodeId('plan-1', 2);
    // Sanity: leftover thinking anchor lives under step 1.
    function hasDescendant(node: ExecutionNode, id: string): boolean {
      if (node.children.some((c) => c.id === id)) return true;
      return node.children.some((c) => hasDescendant(c, id));
    }
    expect(state.currentThinkingId).not.toBeNull();
    expect(hasDescendant(findById(state, step1Id), state.currentThinkingId!)).toBe(true);

    state = runAll(
      state,
      envelope('stream.thinking', {
        delta: 'step 2 reasoning',
        parentStepId: step2Id,
      }),
    );

    // Step 2's new thinking must land under step 2, not step 1.
    const step2 = findById(state, step2Id);
    const step2Thinking = step2.children.find((c) => c.type === 'thinking');
    expect(step2Thinking).toBeDefined();
    expect(step2Thinking?.text ?? '').toContain('step 2 reasoning');
    // And step 1's content must not absorb the new delta.
    const step1 = findById(state, step1Id);
    const step1Thinking = step1.children.find((c) => c.type === 'thinking');
    expect(step1Thinking?.text ?? '').not.toContain('step 2 reasoning');
  });

  it('continues the same-iteration delta when explicit and live anchors agree', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
      envelope('stream.thinking', {
        delta: 'first chunk ',
        parentStepId: stepNodeId('plan-1', 1),
      }),
      envelope('stream.thinking', {
        delta: 'second chunk',
        parentStepId: stepNodeId('plan-1', 1),
      }),
    );
    const step = findById(state, stepNodeId('plan-1', 1));
    const thinkingNodes = step.children.filter((c) => c.type === 'thinking');
    // Both deltas land in the SAME thinking node (same iteration), not
    // two separate nodes from spurious override-driven minting.
    expect(thinkingNodes).toHaveLength(1);
    expect(thinkingNodes[0]?.text ?? '').toBe('first chunk second chunk');
  });
});

describe('appendTextToCurrentTurn honours parentStepId (#485 follow-up)', () => {
  it('routes a text delta to the explicit step when activeNodeId / anchors are stale', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
        ],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      // Step 1 emits text → currentTextId points into step 1's subtree.
      envelope('stream.text', { delta: 'step 1 result' }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 50,
        tokensUsed: 25,
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's2',
        stepIndex: 2,
        totalSteps: 2,
        stepName: 's2',
      }),
    );

    const step1Id = stepNodeId('plan-1', 1);
    const step2Id = stepNodeId('plan-1', 2);

    state = runAll(
      state,
      envelope('stream.text', {
        delta: 'step 2 narration',
        parentStepId: step2Id,
      }),
    );

    function hasDescendantWithText(node: ExecutionNode, snippet: string): boolean {
      if (node.children.some((c) => (c.text ?? '').includes(snippet))) return true;
      return node.children.some((c) => hasDescendantWithText(c, snippet));
    }
    // Step 2's narration lands under step 2.
    expect(hasDescendantWithText(findById(state, step2Id), 'step 2 narration')).toBe(true);
    // Step 1's leftover text does NOT absorb step 2's delta.
    expect(hasDescendantWithText(findById(state, step1Id), 'step 2 narration')).toBe(false);
  });

  it('falls back to the heuristic when parentStepId is missing (regression guard)', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
      envelope('stream.text', { delta: 'plain narration' }),
    );

    const step = findById(state, stepNodeId('plan-1', 1));
    function hasDescendantWithText(node: ExecutionNode, snippet: string): boolean {
      if (node.children.some((c) => (c.text ?? '').includes(snippet))) return true;
      return node.children.some((c) => hasDescendantWithText(c, snippet));
    }
    expect(hasDescendantWithText(step, 'plain narration')).toBe(true);
  });
});

describe('scopeChanged late-delivery isolation (#485 follow-up)', () => {
  // Build state where step 1 is COMPLETED, step 2 is RUNNING with a
  // running tool under it. A late thinking/text for step 1 must NOT
  // touch step 2's running tool, and multi-chunk late deltas for the
  // same older step must accumulate into one node, not fragment.
  function liveStep2WithRunningTool() {
    return runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
        ],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 50,
        tokensUsed: 25,
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's2',
        stepIndex: 2,
        totalSteps: 2,
        stepName: 's2',
      }),
      envelope('stream.thinking', {
        delta: 'step 2 reasoning',
        parentStepId: stepNodeId('plan-1', 2),
      }),
      envelope('stream.tool_use', {
        id: 't-live',
        name: 'read_file',
        parentStepId: stepNodeId('plan-1', 2),
      }),
    );
  }

  it('late thinking for older step does NOT seal live step running tool (Codex P2 self-found)', () => {
    let state = liveStep2WithRunningTool();
    expect(findById(state, 't-live').status).toBe('running');
    const liveAnchorsBefore = {
      currentThinkingId: state.currentThinkingId,
      currentTextId: state.currentTextId,
      activeNodeId: state.activeNodeId,
      thinkingSealed: state.thinkingSealed,
      textIsOpen: state.textIsOpen,
    };

    state = runAll(
      state,
      envelope('stream.thinking', {
        delta: 'late chunk for step 1',
        parentStepId: stepNodeId('plan-1', 1),
      }),
    );

    // Live tool unchanged.
    expect(findById(state, 't-live').status).toBe('running');
    // Live anchors unchanged.
    expect(state.currentThinkingId).toBe(liveAnchorsBefore.currentThinkingId);
    expect(state.currentTextId).toBe(liveAnchorsBefore.currentTextId);
    expect(state.activeNodeId).toBe(liveAnchorsBefore.activeNodeId);
    expect(state.thinkingSealed).toBe(liveAnchorsBefore.thinkingSealed);
    expect(state.textIsOpen).toBe(liveAnchorsBefore.textIsOpen);
    // Late content lands under step 1.
    const step1 = findById(state, stepNodeId('plan-1', 1));
    const late = step1.children.find((c) => c.type === 'thinking' && (c.text ?? '').includes('late chunk'));
    expect(late).toBeDefined();
  });

  it('pending explicit step (race ahead of plan_step_started) is treated as live, not late (Codex #490)', () => {
    // plan_created mints a 'pending' skeleton for step 2. In the race
    // window before plan_step_started for step 2 is reduced, the
    // daemon's first stream.thinking for step 2 arrives carrying
    // parentStepId=step-2. Without the pending-is-live carve-out, this
    // would be classified as scopeChanged → late path → first chunk
    // lands in {step-2}:late:thinking. Once plan_step_started arrives
    // and subsequent chunks no longer fire scopeChanged, those go to
    // mintIterationThinking — splitting one streamed response across
    // two nodes.
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
        ],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 50,
        tokensUsed: 25,
      }),
      // Race window: thinking for step 2 arrives BEFORE its plan_step_started.
      envelope('stream.thinking', {
        delta: 'race chunk one ',
        parentStepId: stepNodeId('plan-1', 2),
      }),
      // Then plan_step_started arrives.
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's2',
        stepIndex: 2,
        totalSteps: 2,
        stepName: 's2',
      }),
      envelope('stream.thinking', {
        delta: 'race chunk two',
        parentStepId: stepNodeId('plan-1', 2),
      }),
    );

    const step2 = findById(state, stepNodeId('plan-1', 2));
    // No `:late:thinking` orphan should exist — both chunks live in
    // the normal iteration thinking node.
    const lateNode = step2.children.find((c) => c.id.endsWith(':late:thinking'));
    expect(lateNode).toBeUndefined();
    // Both chunks must accumulate into the same iteration thinking,
    // not be split across two nodes.
    const thinkingNodes = step2.children.filter((c) => c.type === 'thinking');
    expect(thinkingNodes).toHaveLength(1);
    expect(thinkingNodes[0]?.text ?? '').toBe('race chunk one race chunk two');
  });

  it('late nodes inherit terminal status from their owning step (CodeRabbit M1 #490)', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 2,
        goal: 'g',
        steps: [
          { index: 1, name: 's1', description: '' },
          { index: 2, name: 's2', description: '' },
        ],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 2,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 50,
        tokensUsed: 25,
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's2',
        stepIndex: 2,
        totalSteps: 2,
        stepName: 's2',
      }),
      // Late delivery to step 1 (completed). Late node must be 'completed',
      // not 'running' — otherwise it pulses indefinitely under a finished step.
      envelope('stream.thinking', {
        delta: 'late chunk for step 1',
        parentStepId: stepNodeId('plan-1', 1),
      }),
    );

    const step1 = findById(state, stepNodeId('plan-1', 1));
    const lateThinking = step1.children.find((c) => c.id.endsWith(':late:thinking'));
    expect(lateThinking).toBeDefined();
    expect(lateThinking?.status).toBe('completed');
  });

  it('non-cancelled replan tombstone falls back to live synthetic step (Codex #490)', () => {
    // Step 1 starts, COMPLETES, then a replan reuses stepIndex=1 →
    // dashboard's activateStep preserves the completed tombstone at
    // the composed id and creates the live replacement at a synthetic
    // `:r{n}` id. The daemon's currentStepId tracker still emits the
    // composed form. Without the sibling-search fix, every post-replan
    // delta would route to the COMPLETED tombstone instead of the
    // running synthetic step.
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
      // Step 1 completes (not cancelled — naturally finished).
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 50,
        tokensUsed: 25,
      }),
      // Replan reuses stepIndex=1 → activateStep mints synthetic.
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 1,
        rationale: 'try a different angle',
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1-replan',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1-replan',
      }),
    );

    const tombstoneId = stepNodeId('plan-1', 1);
    const tombstone = findById(state, tombstoneId);
    // The tombstone here is the COMPLETED original — replacePlanSteps
    // only cancels non-completed/non-cancelled steps, so a normally-
    // finished step keeps its 'completed' status. (For a 'failed' /
    // 'paused' tombstone the same sibling-search rule applies — see
    // resolveExplicitStep.)
    // replacePlanSteps explicitly excludes 'completed' steps from the
    // cancellation pass (countCancellableSteps), so the original step's
    // history is preserved as 'completed'. Asserting strictly catches
    // regressions where replan would downgrade a finished step's record.
    expect(tombstone.status).toBe('completed');
    const plan = state.rootNodes[0]!.children[0]!.children[0]!;
    const liveSynthetic = plan.children.find(
      (c) => c.metadata?.['createdByReplan'] === true,
    );
    expect(liveSynthetic?.status).toBe('running');

    // Daemon emits parentStepId=composed (= tombstone id).
    state = runAll(
      state,
      envelope('stream.thinking', {
        delta: 'replan reasoning',
        parentStepId: tombstoneId,
      }),
    );

    // Tombstone receives nothing; live synthetic step does.
    function hasDescendantWithText(node: ExecutionNode, snippet: string): boolean {
      if (node.children.some((c) => (c.text ?? '').includes(snippet))) return true;
      return node.children.some((c) => hasDescendantWithText(c, snippet));
    }
    expect(hasDescendantWithText(findById(state, tombstoneId), 'replan reasoning')).toBe(false);
    expect(hasDescendantWithText(findById(state, liveSynthetic!.id), 'replan reasoning')).toBe(true);
  });

  it('redirects to latest synthetic replacement even after it has also completed (Codex #490)', () => {
    // Step 1 completes → replan creates `plan-1:step:1:r1` → that
    // synthetic also completes → late delta for the composed id
    // arrives. Detection must NOT gate on the synthetic still being
    // running; it should redirect to the synthetic regardless of
    // status, so the late content accumulates under the most recent
    // replacement (not the original historical tombstone).
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.plan_created', {
        planId: 'plan-1',
        stepCount: 1,
        goal: 'g',
        steps: [{ index: 1, name: 's1', description: '' }],
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 50,
        tokensUsed: 25,
      }),
      envelope('stream.plan_replanned', {
        planId: 'plan-1',
        replanAttempt: 1,
        newStepCount: 1,
        rationale: 'try again',
      }),
      envelope('stream.plan_step_started', {
        planId: 'plan-1',
        stepId: 's1-replan',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1-replan',
      }),
      // Synthetic replacement also completes.
      envelope('stream.plan_step_completed', {
        planId: 'plan-1',
        stepId: 's1-replan',
        stepIndex: 1,
        stepName: 's1-replan',
        success: true,
        durationMs: 50,
        tokensUsed: 25,
      }),
    );
    const tombstoneId = stepNodeId('plan-1', 1);
    const plan = state.rootNodes[0]!.children[0]!.children[0]!;
    const synthetic = plan.children.find(
      (c) => c.metadata?.['createdByReplan'] === true,
    );
    expect(synthetic?.status).toBe('completed');

    state = runAll(
      state,
      envelope('stream.thinking', {
        delta: 'late after both done',
        parentStepId: tombstoneId,
      }),
    );

    function hasDescendantWithText(node: ExecutionNode, snippet: string): boolean {
      if (node.children.some((c) => (c.text ?? '').includes(snippet))) return true;
      return node.children.some((c) => hasDescendantWithText(c, snippet));
    }
    // Original tombstone gets nothing; synthetic gets the late content.
    expect(hasDescendantWithText(findById(state, tombstoneId), 'late after both done')).toBe(false);
    expect(hasDescendantWithText(findById(state, synthetic!.id), 'late after both done')).toBe(true);
  });

  it('multi-chunk late text accumulates into one node rather than fragmenting (Codex P2 #490)', () => {
    let state = liveStep2WithRunningTool();
    // Three late text deltas for step 1 (a completed step).
    state = runAll(
      state,
      envelope('stream.text', {
        delta: 'first ',
        parentStepId: stepNodeId('plan-1', 1),
      }),
      envelope('stream.text', {
        delta: 'second ',
        parentStepId: stepNodeId('plan-1', 1),
      }),
      envelope('stream.text', {
        delta: 'third',
        parentStepId: stepNodeId('plan-1', 1),
      }),
    );
    const step1 = findById(state, stepNodeId('plan-1', 1));
    const lateTexts = step1.children.filter((c) => c.type === 'text');
    // One node accumulates all three chunks instead of three siblings.
    expect(lateTexts).toHaveLength(1);
    expect(lateTexts[0]?.text ?? '').toBe('first second third');
    // Live tool still untouched.
    expect(findById(state, 't-live').status).toBe('running');
  });
});

// ---------------------------------------------------------------------------
// Step events arriving before plan_created — don't dangle activeNodeId
// ---------------------------------------------------------------------------

describe('plan/step events without plan skeleton', () => {
  it('plan_step_started without a plan is a state no-op', () => {
    const before = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
    const after = runAll(
      before,
      envelope('stream.plan_step_started', {
        planId: 'never-existed',
        stepId: 's1',
        stepIndex: 1,
        totalSteps: 1,
        stepName: 's1',
      }),
    );
    expect(after.rootNodes).toEqual(before.rootNodes);
    expect(after.activeNodeId).toBe(before.activeNodeId);
    expect(after.lastEventId).toBeGreaterThan(before.lastEventId);
  });

  it('plan_step_completed without a matching step is a no-op', () => {
    const before = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
    const after = runAll(
      before,
      envelope('stream.plan_step_completed', {
        planId: 'never',
        stepId: 's1',
        stepIndex: 1,
        stepName: 's1',
        success: true,
        durationMs: 1,
        tokensUsed: 1,
      }),
    );
    expect(after.activeNodeId).toBe(before.activeNodeId);
  });
});

// ---------------------------------------------------------------------------
// Subagent.end LIFO: highest synthetic-id counter wins
// ---------------------------------------------------------------------------

describe('subagent.end LIFO resolution', () => {
  it('completes the most-recently-started subagent of matching agentType', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.subagent.start', {
        agentType: 'skill:foo',
        prompt: 'first',
      }),
      envelope('stream.subagent.start', {
        agentType: 'skill:foo',
        prompt: 'second',
      }),
    );
    state = runAll(
      state,
      envelope('stream.subagent.end', { agentType: 'skill:foo' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    const subs = turn.children.filter((c) => c.type === 'subagent');
    expect(subs).toHaveLength(2);
    // First-started ('first') stays running; second-started ('second')
    // completes — LIFO via highest synthetic counter, deterministic regardless
    // of DFS order.
    expect(subs[0]!.status).toBe('running');
    expect(subs[0]!.metadata?.['prompt']).toBe('first');
    expect(subs[1]!.status).toBe('completed');
    expect(subs[1]!.metadata?.['prompt']).toBe('second');
  });
});

// ---------------------------------------------------------------------------
// stream.session_ended (issue #445) — close the session node, clear focus
// ---------------------------------------------------------------------------

describe('stream.session_ended', () => {
  it('closes the matching session node and records the reason', () => {
    let state = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
      envelope('stream.turn_started', {
        sessionId: 'sess-1',
        requestId: 'req-1',
        turnNumber: 1,
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
    expect(state.activeNodeId).toBe('req-1');
    state = runAll(
      state,
      envelope('stream.session_ended', {
        sessionId: 'sess-1',
        timestamp: new Date(2026, 0, 1, 1).toISOString(),
        reason: 'destroyed',
      }),
    );
    const session = state.rootNodes[0]!;
    expect(session.type).toBe('session');
    expect(session.status).toBe('completed');
    expect(session.metadata?.['endReason']).toBe('destroyed');
    expect(session.endTime).toBeGreaterThan(0);
    // Active focus is cleared — no further events apply to this session.
    expect(state.activeNodeId).toBeNull();
  });

  it('is a no-op when the ended session does not match this tree', () => {
    const before = runAll(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
    const after = runAll(
      before,
      envelope('stream.session_ended', {
        sessionId: 'sess-other',
        timestamp: new Date(2026, 0, 1).toISOString(),
        reason: 'destroyed',
      }),
    );
    expect(after.rootNodes).toEqual(before.rootNodes);
    expect(after.activeNodeId).toBe(before.activeNodeId);
  });
});

// ---------------------------------------------------------------------------
// Forward-compat: unknown methods are ignored, watermark still advances
// ---------------------------------------------------------------------------

describe('forward-compat', () => {
  it('ignores unknown methods but still advances lastEventId', () => {
    const tree = freshTree();
    const fake = {
      eventId: 99,
      sessionId: 'sess-1',
      receivedAt: new Date(2026, 0, 1).toISOString(),
      event: { method: 'stream.swarm_started', params: {} },
    } as unknown as DaemonEventEnvelope;
    const state = executionTreeReducer(tree, fake);
    expect(state.rootNodes).toHaveLength(0);
    expect(state.lastEventId).toBe(99);
  });

  it('drops events whose eventId is below the current watermark', () => {
    const state1 = executionTreeReducer(
      freshTree(),
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'm',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
    const watermark = state1.lastEventId;
    // Same eventId replayed (snapshot duplication scenario) — must no-op.
    nextEventId = watermark;
    const state2 = executionTreeReducer(
      state1,
      envelope('stream.session_started', {
        sessionId: 'sess-1',
        model: 'changed-after-replay',
        timestamp: new Date(2026, 0, 1).toISOString(),
      }),
    );
    expect(state2).toBe(state1); // referential equality — true no-op
  });
});
