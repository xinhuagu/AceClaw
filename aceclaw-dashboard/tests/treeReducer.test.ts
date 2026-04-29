import { describe, expect, it } from 'vitest';
import type {
  DaemonEvent,
  DaemonEventEnvelope,
  EventParams,
} from '../src/types/events';
import { emptyTree, type ExecutionTree } from '../src/types/tree';
import { executionTreeReducer, stepNodeId } from '../src/reducers/treeReducer';

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
    expect(turn.children).toHaveLength(2);
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
    const tool = state.rootNodes[0]!.children[0]!.children[0]!;
    expect(tool.status).toBe('paused');
    expect(tool.awaitingInput).toBe(true);
    expect(tool.inputPrompt).toBe('rm -rf /');
    expect(tool.permissionRequestId).toBe('perm-1');
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
    // After tool completes, focus returns to the parent turn so the next
    // tool_use under the same turn becomes active naturally.
    expect(state.activeNodeId).toBe('req-1');
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
    const turn = state.rootNodes[0]!.children[0]!;
    const textChildren = turn.children.filter((c) => c.type === 'text');
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
    // Three children: two cancelled tombstones (old-1, old-2) + one new running.
    expect(plan.children).toHaveLength(3);
    const tombstone = plan.children.find(
      (c) => c.id === stepNodeId('plan-1', 1) && c.status === 'cancelled',
    );
    const replacement = plan.children.find(
      (c) => c.metadata?.['createdByReplan'] === true && c.status === 'running',
    );
    expect(tombstone).toBeDefined();
    expect(tombstone?.label).toBe('old-1');
    expect(replacement).toBeDefined();
    expect(replacement?.label).toBe('new-1');
    expect(state.activeNodeId).toBe(replacement?.id);
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
