import { describe, expect, it } from 'vitest';
import { renderHook } from '@testing-library/react';
import {
  NODE_HEIGHT,
  NODE_WIDTH,
  useTreeLayout,
} from '../src/hooks/useTreeLayout';
import { emptyTree, type ExecutionNode, type ExecutionTree } from '../src/types/tree';

/**
 * Layout tests for the dagre integration. These pin the contract that the
 * SVG renderer (#436) relies on:
 *   - Empty tree → empty layout, zero viewBox.
 *   - Every ExecutionNode shows up exactly once with positive coords.
 *   - Edge endpoints land on the source's RIGHT edge and the target's LEFT
 *     edge so the bezier curve enters/exits along the rank direction.
 *   - viewBox encompasses every laid-out node.
 *
 * Component-level visuals (animation, zoom/pan) are not unit-testable in a
 * meaningful way — those land in #438's E2E suite.
 */

function leaf(id: string, label = id): ExecutionNode {
  return { id, type: 'tool', status: 'running', label, children: [] };
}

function tree(nodes: ExecutionNode[], sessionId = 'sess-1'): ExecutionTree {
  return { ...emptyTree(sessionId), rootNodes: nodes };
}

describe('useTreeLayout', () => {
  it('returns an empty layout for an empty tree', () => {
    const { result } = renderHook(() => useTreeLayout(emptyTree('sess-1')));
    expect(result.current.nodes).toEqual([]);
    expect(result.current.edges).toEqual([]);
    expect(result.current.width).toBe(0);
    expect(result.current.height).toBe(0);
    expect(result.current.viewBox).toBe('0 0 0 0');
  });

  it('lays out a single node and produces no edges', () => {
    const { result } = renderHook(() =>
      useTreeLayout(tree([leaf('only')])),
    );
    expect(result.current.nodes).toHaveLength(1);
    const n = result.current.nodes[0]!;
    expect(n.id).toBe('only');
    expect(n.width).toBe(NODE_WIDTH);
    expect(n.height).toBe(NODE_HEIGHT);
    expect(n.x).toBeGreaterThan(0);
    expect(n.y).toBeGreaterThan(0);
    expect(result.current.edges).toEqual([]);
  });

  it('builds a parent → child edge with endpoints on facing node edges', () => {
    const parent: ExecutionNode = {
      id: 'p',
      type: 'turn',
      status: 'completed',
      label: 'turn 1',
      children: [leaf('c', 'tool')],
    };
    const { result } = renderHook(() => useTreeLayout(tree([parent])));
    const layout = result.current;
    expect(layout.nodes).toHaveLength(2);
    expect(layout.edges).toHaveLength(1);

    const parentNode = layout.nodes.find((n) => n.id === 'p')!;
    const childNode = layout.nodes.find((n) => n.id === 'c')!;
    const edge = layout.edges[0]!;

    // Source endpoint must sit on the parent's RIGHT edge.
    expect(edge.from.x).toBeCloseTo(parentNode.x + parentNode.width / 2, 1);
    expect(edge.from.y).toBeCloseTo(parentNode.y, 1);
    // Target endpoint must sit on the child's LEFT edge.
    expect(edge.to.x).toBeCloseTo(childNode.x - childNode.width / 2, 1);
    expect(edge.to.y).toBeCloseTo(childNode.y, 1);
    // LR layout: the child sits to the right of the parent.
    expect(childNode.x).toBeGreaterThan(parentNode.x);
    // Edge status mirrors the target node so colour follows execution flow.
    expect(edge.status).toBe('running');
  });

  it('viewBox encompasses every laid-out node', () => {
    const root: ExecutionNode = {
      id: 'root',
      type: 'session',
      status: 'running',
      label: 'session',
      children: [leaf('a'), leaf('b'), leaf('c')],
    };
    const { result } = renderHook(() => useTreeLayout(tree([root])));
    const { nodes, width, height, viewBox } = result.current;
    expect(viewBox).toBe(`0 0 ${width} ${height}`);
    for (const n of nodes) {
      expect(n.x - n.width / 2).toBeGreaterThanOrEqual(0);
      expect(n.x + n.width / 2).toBeLessThanOrEqual(width);
      expect(n.y - n.height / 2).toBeGreaterThanOrEqual(0);
      expect(n.y + n.height / 2).toBeLessThanOrEqual(height);
    }
  });

  it('emits a dashed sequence edge between consecutive sibling turns', () => {
    // Two turns under a session: solid containment edges from session to
    // each turn, plus a dashed sequence edge between turn1 → turn2 to
    // express temporal order. Tools and other types don't get sequence
    // edges (parallelism is ambiguous without metadata).
    const session: ExecutionNode = {
      id: 's',
      type: 'session',
      status: 'running',
      label: 'session',
      children: [
        {
          id: 't1',
          type: 'turn',
          status: 'completed',
          label: 'turn 1',
          children: [],
        },
        {
          id: 't2',
          type: 'turn',
          status: 'running',
          label: 'turn 2',
          children: [],
        },
      ],
    };
    const { result } = renderHook(() => useTreeLayout(tree([session])));
    const containment = result.current.edges.filter((e) => e.kind === 'containment');
    const sequence = result.current.edges.filter((e) => e.kind === 'sequence');
    // Two containment edges (session→t1, session→t2) and one sequence
    // edge (t1→t2).
    expect(containment).toHaveLength(2);
    expect(sequence).toHaveLength(1);
    expect(sequence[0]!.id).toBe('seq:t1->t2');
    // Sequence-edge status follows the target — this is what the renderer
    // uses to colour the line; for a sequence edge GrowingEdge ignores
    // status anyway and uses the muted neutral, but the shape contract
    // mirrors containment so consumers can switch on kind alone.
    expect(sequence[0]!.status).toBe('running');
  });

  it('chains thinking → tools → next thinking via flow edges (ReAct pipeline)', () => {
    // Two ReAct iterations under one turn:
    //   thinking1 ─┬─ toolA
    //              └─ toolB ─ ─ ─ ─ ─ thinking2 ─── toolC
    //                                              └─── response (under th2)
    // The dashed flow edges from each tool of thinking1 land on
    // thinking2, expressing "tool results feed the next LLM call".
    // The response is a child of its iteration's thinking (th2), so
    // the layout reaches it via the regular thinking→text containment
    // — no separate response-flow edge is needed.
    const turn: ExecutionNode = {
      id: 't',
      type: 'turn',
      status: 'running',
      label: 'turn',
      children: [
        {
          id: 'th1',
          type: 'thinking',
          status: 'completed',
          label: 'thinking',
          children: [leaf('toolA'), leaf('toolB')],
        },
        {
          id: 'th2',
          type: 'thinking',
          status: 'running',
          label: 'thinking',
          children: [
            leaf('toolC'),
            {
              id: 'resp',
              type: 'text',
              status: 'running',
              label: 'response',
              children: [],
            },
          ],
        },
      ],
    };
    const { result } = renderHook(() => useTreeLayout(tree([turn])));
    const flow = result.current.edges.filter((e) => e.kind === 'sequence');
    const flowIds = flow.map((e) => e.id).sort();
    // Two flows from thinking1's tools to thinking2 (toolA→th2, toolB→th2).
    expect(flowIds).toContain('toolA->th2');
    expect(flowIds).toContain('toolB->th2');
    // The response no longer gets its own flow edge — it's a child of
    // th2 reached via plain containment.
    expect(flowIds).not.toContain('toolC->resp');

    const containment = result.current.edges.filter((e) => e.kind === 'containment');
    const containmentIds = containment.map((e) => e.id);
    expect(containmentIds).toContain('t->th1');
    expect(containmentIds).not.toContain('t->th2'); // reached via flow
    // text under thinking IS containment now.
    expect(containmentIds).toContain('th2->resp');
    expect(containmentIds).toContain('th1->toolA');
    expect(containmentIds).toContain('th1->toolB');
    expect(containmentIds).toContain('th2->toolC');
  });

  it('does not draw a flow edge from a failed tool — only successful tools carry the chain', () => {
    // Failed tools render as dead-end leaves: visually clearer that
    // they didn't drive the next iteration, even though their error
    // is what the model adapted to. With one tool succeeding and one
    // failing, only the successful one gets a flow edge.
    const turn: ExecutionNode = {
      id: 't',
      type: 'turn',
      status: 'running',
      label: 'turn',
      children: [
        {
          id: 'th1',
          type: 'thinking',
          status: 'completed',
          label: 'thinking',
          children: [
            { id: 'good', type: 'tool', status: 'completed', label: 'bash', children: [] },
            { id: 'bad', type: 'tool', status: 'failed', label: 'bash', children: [] },
          ],
        },
        {
          id: 'th2',
          type: 'thinking',
          status: 'running',
          label: 'thinking',
          children: [],
        },
      ],
    };
    const { result } = renderHook(() => useTreeLayout(tree([turn])));
    const flowIds = result.current.edges
      .filter((e) => e.kind === 'sequence')
      .map((e) => e.id);
    expect(flowIds).toContain('good->th2');
    expect(flowIds).not.toContain('bad->th2');
  });

  it('falls back to thinking-bridge when every tool in an iteration failed', () => {
    // All-failed iteration: model recovered from a total failure.
    // The chain still needs to reach the next thinking, so we bridge
    // via the iteration's text/thinking instead of leaving it
    // disconnected.
    const turn: ExecutionNode = {
      id: 't',
      type: 'turn',
      status: 'running',
      label: 'turn',
      children: [
        {
          id: 'th1',
          type: 'thinking',
          status: 'completed',
          label: 'thinking',
          children: [
            { id: 'bad1', type: 'tool', status: 'failed', label: 'bash', children: [] },
            { id: 'bad2', type: 'tool', status: 'failed', label: 'bash', children: [] },
          ],
        },
        {
          id: 'th2',
          type: 'thinking',
          status: 'running',
          label: 'thinking',
          children: [],
        },
      ],
    };
    const { result } = renderHook(() => useTreeLayout(tree([turn])));
    const flowIds = result.current.edges
      .filter((e) => e.kind === 'sequence')
      .map((e) => e.id);
    expect(flowIds).toContain('th1->th2');
    expect(flowIds).not.toContain('bad1->th2');
    expect(flowIds).not.toContain('bad2->th2');
  });

  it('bridges to the next iteration via the text node when a thinking emitted only text', () => {
    // A pure-text iteration (no tools, just narration) still needs to
    // pass the flow baton to the next iteration's thinking. The text
    // node serves as the bridge in that case.
    const turn: ExecutionNode = {
      id: 't',
      type: 'turn',
      status: 'running',
      label: 'turn',
      children: [
        {
          id: 'th1',
          type: 'thinking',
          status: 'completed',
          label: 'thinking',
          children: [
            {
              id: 'th1-text',
              type: 'text',
              status: 'completed',
              label: 'narration',
              children: [],
            },
          ],
        },
        {
          id: 'th2',
          type: 'thinking',
          status: 'running',
          label: 'thinking',
          children: [leaf('toolA')],
        },
      ],
    };
    const { result } = renderHook(() => useTreeLayout(tree([turn])));
    const flowIds = result.current.edges
      .filter((e) => e.kind === 'sequence')
      .map((e) => e.id);
    // Flow goes from the text bridge (not the bare thinking) so the
    // visual chain stays unbroken left-to-right.
    expect(flowIds).toContain('th1-text->th2');
    expect(flowIds).not.toContain('th1->th2');
  });

  it('does not emit sequence edges between sibling tools (parallel/sequential ambiguous)', () => {
    // Two parallel tools under a turn: only containment edges, no
    // sequence edge between them. Without execution-order metadata we
    // can't say whether they ran in parallel or sequentially, and a
    // wrong sequence edge is worse than no edge.
    const turn: ExecutionNode = {
      id: 't',
      type: 'turn',
      status: 'running',
      label: 'turn',
      children: [
        leaf('tool-a'),
        leaf('tool-b'),
      ],
    };
    const { result } = renderHook(() => useTreeLayout(tree([turn])));
    const sequence = result.current.edges.filter((e) => e.kind === 'sequence');
    expect(sequence).toHaveLength(0);
  });

  it('emits ReAct flow edges between iterations under a STEP, not just a turn', () => {
    // Plan steps each run their own ReAct loop; a step with N
    // iterations needs the same productive-tool → next-thinking flow
    // edges that a turn gets. Without these edges, multiple thinkings
    // under the same step render as visually parallel siblings even
    // though they're sequential in time. (#458 follow-up — caught
    // when the user saw "parallel thinkings under a step".)
    const tool1: ExecutionNode = {
      id: 'tool-1',
      type: 'tool',
      status: 'completed',
      label: 'read_file',
      children: [],
    };
    const thinking1: ExecutionNode = {
      id: 'think-1',
      type: 'thinking',
      status: 'completed',
      label: 'iter 1',
      children: [tool1],
    };
    const thinking2: ExecutionNode = {
      id: 'think-2',
      type: 'thinking',
      status: 'completed',
      label: 'iter 2',
      children: [],
    };
    const step: ExecutionNode = {
      id: 'step-1',
      type: 'step',
      status: 'running',
      label: 'extract',
      children: [thinking1, thinking2],
    };
    const { result } = renderHook(() => useTreeLayout(tree([step])));
    // Flow edges are rendered as kind='sequence' (dashed) on the
    // layout output. At least one should connect the productive tool
    // of iter 1 to the start of iter 2.
    const flowEdges = result.current.edges.filter((e) => e.kind === 'sequence');
    expect(flowEdges.length).toBeGreaterThanOrEqual(1);
  });

  it('does not emit ReAct flow edges between thinkings under a non-loop parent', () => {
    // Regression guard: only turns and steps host ReAct loops. A
    // generic node with multiple thinking children (sub-agent here)
    // shouldn't get flow edges synthesized — those would be wrong
    // semantically since sub-agents aren't running their own ReAct
    // loop in the parent's namespace.
    const thinking1: ExecutionNode = {
      id: 't1',
      type: 'thinking',
      status: 'completed',
      label: 't1',
      children: [],
    };
    const thinking2: ExecutionNode = {
      id: 't2',
      type: 'thinking',
      status: 'completed',
      label: 't2',
      children: [],
    };
    const subagent: ExecutionNode = {
      id: 'sa',
      type: 'subagent',
      status: 'running',
      label: 'sub',
      children: [thinking1, thinking2],
    };
    const { result } = renderHook(() => useTreeLayout(tree([subagent])));
    const flowEdges = result.current.edges.filter((e) => e.kind === 'sequence');
    expect(flowEdges).toHaveLength(0);
  });

  it('attaches the dagre coordinates while preserving every ExecutionNode field', () => {
    const root: ExecutionNode = {
      id: 'r',
      type: 'turn',
      status: 'failed',
      label: 'turn',
      duration: 42,
      error: 'boom',
      metadata: { foo: 'bar' },
      children: [],
    };
    const { result } = renderHook(() => useTreeLayout(tree([root])));
    const node = result.current.nodes[0]!;
    expect(node.label).toBe('turn');
    expect(node.status).toBe('failed');
    expect(node.duration).toBe(42);
    expect(node.error).toBe('boom');
    expect(node.metadata).toEqual({ foo: 'bar' });
  });
});
