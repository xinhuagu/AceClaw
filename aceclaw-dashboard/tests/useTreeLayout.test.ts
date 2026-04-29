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
