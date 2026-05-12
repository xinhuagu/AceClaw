import { describe, expect, it } from 'vitest';
import { parseEnvelopeFromObject } from '../src/hooks/useExecutionTree';
import { executionTreeReducer, stepNodeId } from '../src/reducers/treeReducer';
import { emptyTree, type ExecutionNode } from '../src/types/tree';

/**
 * Pins the snapshot replay path (issue #432). The hook's WebSocket plumbing
 * itself is integration-level (jsdom + mock ws) and lives in #438; here we
 * verify the boundary that decides:
 *
 *   - Each envelope inside a {@code snapshot.response} array goes through
 *     the same validator gate as a live frame (no second code path that
 *     could drift from {@link parseEnvelope}).
 *   - Replaying a snapshot through the reducer produces the same tree
 *     state as if those envelopes had arrived live, including
 *     {@code state.lastEventId} matching the newest envelope's id.
 *   - A subsequent live event with {@code eventId <= lastEventId} is
 *     deduped (the reducer's existing guard, exercised here so the
 *     contract between snapshot and live stream is locked).
 */

function buildEnvelope(
  eventId: number,
  sessionId: string,
  method: string,
  params: Record<string, unknown>,
) {
  return {
    eventId,
    sessionId,
    receivedAt: '2026-04-29T00:00:00.000Z',
    event: { method, params },
  };
}

describe('snapshot replay (#432)', () => {
  it('replays a session_started + tool_use snapshot into a tree the dispatcher can extend', () => {
    const envelopes = [
      buildEnvelope(1, 'sess-1', 'stream.session_started', {
        sessionId: 'sess-1',
        model: 'claude-opus-4-7',
      }),
      buildEnvelope(2, 'sess-1', 'stream.turn_started', {
        requestId: 'r1',
        turnNumber: 1,
      }),
      buildEnvelope(3, 'sess-1', 'stream.tool_use', {
        id: 't1',
        name: 'bash',
      }),
    ];

    let tree = emptyTree('sess-1');
    for (const env of envelopes) {
      const parsed = parseEnvelopeFromObject(env);
      expect(parsed).not.toBeNull();
      if (parsed?.kind === 'known') {
        tree = executionTreeReducer(tree, parsed.envelope);
      }
    }

    expect(tree.lastEventId).toBe(3);
    // Every replayed envelope produced its tree contribution: a session
    // root, a turn child, and an in-flight tool node.
    expect(tree.rootNodes.find((n) => n.type === 'session')).toBeDefined();
    expect(tree.stats.totalTurns).toBe(1);
    expect(tree.stats.totalTools).toBe(1);
  });

  it('dedups a live event whose eventId is at or below the snapshot watermark', () => {
    // Snapshot delivered: events 1..3, lastEventId=3. Then a live frame
    // with eventId=3 arrives — daemon hasn't dropped it yet because the
    // bridge fans out to every connected client without a per-client
    // ack. Reducer must skip it instead of double-applying.
    const snapshot = [
      buildEnvelope(1, 'sess-1', 'stream.session_started', {
        sessionId: 'sess-1',
        model: 'claude-opus-4-7',
      }),
      buildEnvelope(2, 'sess-1', 'stream.turn_started', {
        requestId: 'r1',
        turnNumber: 1,
      }),
      buildEnvelope(3, 'sess-1', 'stream.tool_use', {
        id: 't1',
        name: 'bash',
      }),
    ];
    let tree = emptyTree('sess-1');
    for (const env of snapshot) {
      const parsed = parseEnvelopeFromObject(env);
      if (parsed?.kind === 'known') {
        tree = executionTreeReducer(tree, parsed.envelope);
      }
    }

    const liveDup = parseEnvelopeFromObject(
      buildEnvelope(3, 'sess-1', 'stream.tool_use', {
        id: 't1',
        name: 'bash',
      }),
    );
    expect(liveDup?.kind).toBe('known');
    if (liveDup?.kind === 'known') {
      const next = executionTreeReducer(tree, liveDup.envelope);
      // Tree state unchanged because the dedup gate fires.
      expect(next).toBe(tree);
      expect(next.stats.totalTools).toBe(1);
    }
  });

  it('applies a live event whose eventId is strictly above the snapshot watermark', () => {
    // Same snapshot as above. A new live event (eventId=4) gets through.
    const snapshot = [
      buildEnvelope(1, 'sess-1', 'stream.session_started', {
        sessionId: 'sess-1',
        model: 'claude-opus-4-7',
      }),
      buildEnvelope(2, 'sess-1', 'stream.turn_started', {
        requestId: 'r1',
        turnNumber: 1,
      }),
    ];
    let tree = emptyTree('sess-1');
    for (const env of snapshot) {
      const parsed = parseEnvelopeFromObject(env);
      if (parsed?.kind === 'known') {
        tree = executionTreeReducer(tree, parsed.envelope);
      }
    }
    expect(tree.lastEventId).toBe(2);

    const liveNew = parseEnvelopeFromObject(
      buildEnvelope(4, 'sess-1', 'stream.tool_use', {
        id: 't1',
        name: 'bash',
      }),
    );
    if (liveNew?.kind === 'known') {
      const next = executionTreeReducer(tree, liveNew.envelope);
      expect(next.lastEventId).toBe(4);
      expect(next.stats.totalTools).toBe(1);
    }
  });

  it('rejects malformed envelopes inside a snapshot the same way live frames are rejected', () => {
    // A daemon-side bug that put a non-string eventId on the wire would
    // otherwise leak past parseEnvelopeFromObject and crash the reducer.
    expect(
      parseEnvelopeFromObject({
        eventId: 'one',
        sessionId: 's',
        receivedAt: 't',
        event: { method: 'stream.text', params: {} },
      }),
    ).toBeNull();
    expect(
      parseEnvelopeFromObject({
        eventId: 1,
        sessionId: 's',
        receivedAt: 't',
        event: { method: 'stream.tool_use', params: { id: 't1' /* missing name */ } },
      }),
    ).toBeNull();
  });

  it('replays a multi-step plan with parentStepId-tagged events and attributes each tool under its own step', () => {
    // Regression pin for the user-visible symptom where late plan steps
    // appeared to have only a thinking child (the 2026-05-11 GSCORD session).
    // On snapshot reload, a multi-step plan where the daemon tags
    // stream.thinking / stream.tool_use with the owning parentStepId must
    // reconstruct one thinking + tool subtree per step, not collapse
    // everything onto the first step.
    //
    // Exercises the full snapshot path: parseEnvelopeFromObject (envelope
    // gate) -> executionTreeReducer (resolveExplicitStep + addToolNode
    // override). A regression here would re-introduce the symptom because
    // every late-arriving tool_use carrying parentStepId of an
    // already-completed step would silently fall back to the heuristic and
    // chain off step 1's anchor.
    const planId = 'plan-gscord';
    const stepIdOf = (idx: number) => stepNodeId(planId, idx);
    const stepNames = [
      'Locate & Retrieve',
      'Analyse GSCORD',
      'Create draw.io Diagram',
      'Compose Documentation',
    ];

    const envelopes: Array<ReturnType<typeof buildEnvelope>> = [
      buildEnvelope(1, 'sess-1', 'stream.session_started', {
        sessionId: 'sess-1',
        model: 'claude-opus-4-7',
      }),
      buildEnvelope(2, 'sess-1', 'stream.turn_started', {
        requestId: 'r1',
        turnNumber: 1,
      }),
      buildEnvelope(3, 'sess-1', 'stream.plan_created', {
        planId,
        goal: 'analyse GSCORD',
        steps: stepNames.map((name, i) => ({ index: i + 1, name })),
      }),
    ];

    // For each step: started -> thinking (with parentStepId) -> tool_use
    // (with parentStepId) -> tool_completed -> step_completed.
    let nextId = 4;
    stepNames.forEach((name, i) => {
      const idx = i + 1;
      const stepId = stepIdOf(idx);
      envelopes.push(
        buildEnvelope(nextId++, 'sess-1', 'stream.plan_step_started', {
          planId,
          stepId,
          stepIndex: idx,
          totalSteps: stepNames.length,
          stepName: name,
        }),
        buildEnvelope(nextId++, 'sess-1', 'stream.thinking', {
          delta: `thinking for ${name}`,
          parentStepId: stepId,
        }),
        buildEnvelope(nextId++, 'sess-1', 'stream.tool_use', {
          id: `tu-${idx}`,
          name: idx === 3 ? 'bash' : 'read_file',
          parentStepId: stepId,
        }),
        buildEnvelope(nextId++, 'sess-1', 'stream.tool_completed', {
          id: `tu-${idx}`,
          name: idx === 3 ? 'bash' : 'read_file',
          durationMs: 100,
          isError: false,
        }),
        buildEnvelope(nextId++, 'sess-1', 'stream.plan_step_completed', {
          planId,
          stepId,
          stepIndex: idx,
          stepName: name,
          success: true,
          durationMs: 100,
          tokensUsed: 100,
        }),
      );
    });

    let tree = emptyTree('sess-1');
    for (const env of envelopes) {
      const parsed = parseEnvelopeFromObject(env);
      expect(parsed).not.toBeNull();
      if (parsed?.kind === 'known') {
        tree = executionTreeReducer(tree, parsed.envelope);
      }
    }

    // Walk down to the plan node.
    const session = tree.rootNodes.find((n) => n.type === 'session');
    expect(session).toBeDefined();
    const turn = session!.children[0]!;
    const plan = turn.children.find((c) => c.type === 'plan')!;
    expect(plan).toBeDefined();

    // Each step has exactly one thinking child with exactly one tool nested
    // inside. The original symptom would manifest here as later steps having
    // a thinking child with no tool descendant (the tool got lost or
    // attached to step 1 instead). Cardinality is asserted strictly so a
    // future regression that duplicated nodes per step would also fail.
    for (let idx = 1; idx <= stepNames.length; idx++) {
      const step = plan.children.find((c) => c.id === stepIdOf(idx));
      expect(step, `step ${idx} should exist under plan`).toBeDefined();
      const thinkingChildren = step!.children.filter(
        (c) => c.type === 'thinking',
      );
      expect(
        thinkingChildren,
        `step ${idx} should have exactly one thinking child`,
      ).toHaveLength(1);
      const thinking = thinkingChildren[0]!;
      const toolChildren = thinking.children.filter((c) => c.type === 'tool');
      expect(
        toolChildren,
        `step ${idx} thinking should have exactly one tool_use nested inside`,
      ).toHaveLength(1);
      expect(toolChildren[0]!.id).toBe(`tu-${idx}`);
    }

    // Cross-check: step 1's subtree must NOT have absorbed steps 2-4's
    // tool calls. This is the explicit regression guard for the original
    // "anchor walks up to step 1" bug.
    const step1 = plan.children.find((c) => c.id === stepIdOf(1))!;
    const allToolsUnderStep1: string[] = [];
    function collect(node: ExecutionNode): void {
      if (node.type === 'tool') allToolsUnderStep1.push(node.id);
      for (const c of node.children) collect(c);
    }
    collect(step1);
    expect(allToolsUnderStep1).toEqual(['tu-1']);
  });

  it('returns kind=unknown for forward-compat methods so callers can advance the watermark', () => {
    // Snapshot may include events whose method we don't know yet (Tier
    // 2/3 events the dashboard wasn't built to render). The reducer
    // skips them, but the hook caller still bumps lastEventId so the
    // next reconnect doesn't re-replay them forever.
    const parsed = parseEnvelopeFromObject(
      buildEnvelope(7, 'sess-1', 'stream.future_event', { foo: 'bar' }),
    );
    expect(parsed?.kind).toBe('unknown');
    expect(parsed?.eventId).toBe(7);
  });
});
