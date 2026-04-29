/**
 * useTreeLayout (issue #436).
 *
 * Runs dagre on the {@link ExecutionTree} produced by {@link executionTreeReducer}
 * (#435) and returns a fully-laid-out {@link TreeLayout} ready for SVG
 * rendering. Memoised on the tree reference, so re-runs only happen when
 * the reducer actually returns a new state object.
 *
 * Layout choices (echoed in the issue spec):
 *   - {@code rankdir: 'LR'} — execution flows left-to-right, matching how
 *     events arrive in time.
 *   - {@code nodesep: 50}, {@code ranksep: 120} — sibling spacing chosen so
 *     parallel tools sit cleanly side-by-side without overlap on labels up
 *     to ~24 chars.
 *   - All nodes are sized {@code 180 × 44} so the renderer can hard-code
 *     icon and text positions; we don't auto-size to label content (Tier 2/3
 *     can revisit if labels diverge wildly).
 */

import { useMemo } from 'react';
import * as dagre from 'dagre';
import type {
  ExecutionNode,
  ExecutionTree,
  LayoutEdge,
  LayoutNode,
  TreeLayout,
} from '../types/tree';

/** SVG-pixel dimensions of every node — kept consistent with GrowingNode.tsx. */
export const NODE_WIDTH = 180;
export const NODE_HEIGHT = 44;
const RANK_DIR = 'LR' as const;
const NODE_SEP = 50;
const RANK_SEP = 120;
/** Padding around the bounding box so edges don't touch the SVG border. */
const VIEWBOX_PADDING = 24;

/**
 * Recursively populates a dagre graph with every node + parent→child edge in
 * the execution tree. Walks pre-order so the dagre node insertion order
 * matches event arrival order — useful when dagre's tie-breaking falls back
 * to insertion order.
 */
function populateGraph(
  graph: dagre.graphlib.Graph,
  nodes: ExecutionNode[],
  parentId: string | null,
): void {
  for (const n of nodes) {
    graph.setNode(n.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
    if (parentId !== null) {
      graph.setEdge(parentId, n.id);
    }
    if (n.children.length > 0) {
      populateGraph(graph, n.children, n.id);
    }
  }
}

/** Flattens the tree to a parallel array, preserving pre-order. */
function flattenNodes(nodes: ExecutionNode[], out: ExecutionNode[]): void {
  for (const n of nodes) {
    out.push(n);
    if (n.children.length > 0) {
      flattenNodes(n.children, out);
    }
  }
}

/**
 * Reactive layout pass. Returns an empty layout for an empty tree (avoids
 * dagre's 0×0 viewBox edge case), otherwise the full dagre output decorated
 * with the source ExecutionNode for each LayoutNode.
 */
export function useTreeLayout(tree: ExecutionTree): TreeLayout {
  return useMemo(() => {
    if (tree.rootNodes.length === 0) {
      return {
        nodes: [],
        edges: [],
        viewBox: '0 0 0 0',
        width: 0,
        height: 0,
      };
    }

    const graph = new dagre.graphlib.Graph({ multigraph: false });
    graph.setGraph({
      rankdir: RANK_DIR,
      nodesep: NODE_SEP,
      ranksep: RANK_SEP,
      marginx: VIEWBOX_PADDING,
      marginy: VIEWBOX_PADDING,
    });
    graph.setDefaultEdgeLabel(() => ({}));
    populateGraph(graph, tree.rootNodes, null);
    dagre.layout(graph);

    // Walk tree again pre-order; dagre stores positions on its internal
    // node objects, so we look each up by id and merge the (x, y) on top
    // of the source ExecutionNode.
    const flat: ExecutionNode[] = [];
    flattenNodes(tree.rootNodes, flat);
    const layoutNodes: LayoutNode[] = flat.map((n) => {
      const dn = graph.node(n.id);
      return {
        ...n,
        x: dn.x,
        y: dn.y,
        width: dn.width,
        height: dn.height,
      };
    });

    // dagre's edge endpoints are at node centres — adjust to land on the
    // RIGHT edge of the source and the LEFT edge of the target, so the
    // bezier in GrowingEdge curves cleanly between sides.
    const nodesById = new Map(layoutNodes.map((n) => [n.id, n]));
    const layoutEdges: LayoutEdge[] = graph.edges().map((e) => {
      const from = nodesById.get(e.v);
      const to = nodesById.get(e.w);
      // Both ends MUST exist because populateGraph creates nodes before edges.
      // Defensive fallback to (0,0) so a misconfigured graph doesn't crash
      // the renderer; the visual will be obviously wrong instead.
      const fromX = from ? from.x + from.width / 2 : 0;
      const fromY = from ? from.y : 0;
      const toX = to ? to.x - to.width / 2 : 0;
      const toY = to ? to.y : 0;
      return {
        id: `${e.v}->${e.w}`,
        from: { x: fromX, y: fromY },
        to: { x: toX, y: toY },
        status: to?.status ?? 'pending',
      };
    });

    const g = graph.graph();
    const width = g.width ?? 0;
    const height = g.height ?? 0;
    return {
      nodes: layoutNodes,
      edges: layoutEdges,
      viewBox: `0 0 ${width} ${height}`,
      width,
      height,
    };
  }, [tree]);
}
