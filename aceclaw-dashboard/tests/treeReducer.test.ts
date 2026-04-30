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

  it('falls back to attaching text to the turn when no thinking has been emitted', () => {
    // Extended thinking off, or model emitted text without a prior
    // thinking block. Text still gets at most one node per turn,
    // keyed on a turn-level fallback id.
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
    const textChildren = turn.children.filter((c) => c.type === 'text');
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

  it('falls back to attaching tools to the turn when no thinking has been emitted', () => {
    // Extended thinking disabled: stream.thinking never fires, so tools
    // attach to the turn directly (preserves the pre-anchor shape).
    const state = runAll(
      startedTurn(),
      envelope('stream.tool_use', { id: 't1', name: 'bash' }),
    );
    const turn = state.rootNodes[0]!.children[0]!;
    expect(turn.children).toHaveLength(1);
    expect(turn.children[0]!.id).toBe('t1');
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
    // currentThinkingId stays null because turn 2 had no thinking event
    // before its tool_use — and addTurnNode wiped turn 1's anchor on entry.
    // The tool fell back to the turn-as-anchor branch.
    expect(state.currentThinkingId).toBeNull();
    const turn2 = state.rootNodes[0]!.children[1]!;
    expect(turn2.children).toHaveLength(1);
    expect(turn2.children[0]!.id).toBe('t2');
    expect(turn2.children[0]!.type).toBe('tool');
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
