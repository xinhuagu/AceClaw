import { describe, expect, it } from 'vitest';
import * as dagre from 'dagre';
import type { DaemonEvent } from '../src/types/events';
import type { ExecutionNode } from '../src/types/tree';

/**
 * Scaffold sanity checks (issue #434). Verifies the toolchain wiring rather
 * than logic — the reducer (#435) and tree component (#436) bring their own
 * real test suites. Three signals here:
 *  - Vitest runs and asserts pass.
 *  - The schema types from #439 are importable and discriminate by `method`.
 *  - dagre layout produces a deterministic LR ordering for a trivial graph.
 */
describe('scaffold', () => {
  it('Vitest is wired up', () => {
    expect(1 + 1).toBe(2);
  });

  it('DaemonEvent discriminates on method', () => {
    const ev: DaemonEvent = {
      method: 'stream.text',
      params: { delta: 'hello' },
    };
    expect(ev.method).toBe('stream.text');
    if (ev.method === 'stream.text') {
      // exhaustive narrowing exercises the schema types
      expect(ev.params.delta).toBe('hello');
    }
  });

  it('ExecutionNode tree shape is constructible', () => {
    const root: ExecutionNode = {
      id: 'r1',
      type: 'request',
      status: 'running',
      label: 'request',
      children: [
        { id: 't1', type: 'turn', status: 'completed', label: 'turn 1', children: [] },
      ],
    };
    expect(root.children).toHaveLength(1);
    expect(root.children[0]?.type).toBe('turn');
  });

  it('dagre lays out a 2-node LR graph deterministically', () => {
    const g = new dagre.graphlib.Graph();
    g.setGraph({ rankdir: 'LR', nodesep: 50, ranksep: 80 });
    g.setDefaultEdgeLabel(() => ({}));
    g.setNode('a', { width: 100, height: 40 });
    g.setNode('b', { width: 100, height: 40 });
    g.setEdge('a', 'b');
    dagre.layout(g);

    const a = g.node('a');
    const b = g.node('b');
    // Left-to-right means b is strictly right of a on a deterministic layout.
    expect(b.x).toBeGreaterThan(a.x);
    // Both nodes share the same y on a single-rank LR layout.
    expect(a.y).toBe(b.y);
  });
});
