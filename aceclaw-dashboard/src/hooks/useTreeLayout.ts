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
 *
 * <p>Skips containment edges that the flow-edge pass (see
 * {@link addReActFlowEdges}) will replace. Specifically: the second-and-later
 * {@code thinking} children of a turn don't get a direct turn → thinking
 * containment edge — they're reached via the flow chain
 * (thinking[i] tools → thinking[i+1]). Same for the {@code text} response
 * when any thinking exists. Without this skip, dagre would draw a long
 * containment line from turn to thinking[i+1] that crosses the chain
 * visually for no semantic gain.
 */
function populateGraph(
  graph: dagre.graphlib.Graph,
  nodes: ExecutionNode[],
  parent: ExecutionNode | null,
): void {
  for (const n of nodes) {
    graph.setNode(n.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
    if (parent !== null && !shouldSkipContainment(parent, n)) {
      graph.setEdge(parent.id, n.id);
    }
    if (n.children.length > 0) {
      populateGraph(graph, n.children, n);
    }
  }
}

/**
 * True iff the {@code child}'s containment edge from {@code parent} is
 * redundant with a flow edge that {@link addReActFlowEdges} will add.
 *
 * - A non-first {@code thinking} child of a turn: the chain enters via
 *   the previous thinking's tool outputs.
 *
 * Text nodes are now anchored to their iteration's thinking (so they're
 * never direct children of the turn), and the redundant-containment
 * skip for them is no longer relevant.
 */
function shouldSkipContainment(
  parent: ExecutionNode,
  child: ExecutionNode,
): boolean {
  if (parent.type !== 'turn') return false;
  if (child.type === 'thinking') {
    const firstThinking = parent.children.find((c) => c.type === 'thinking');
    return firstThinking !== child;
  }
  return false;
}

/**
 * Adds dashed flow edges to the graph so the dagre layout chains
 * thinking/tool/thinking/... left-to-right within a turn.
 *
 * For each turn that has multiple thinking iterations:
 *   - Each tool of {@code thinking[i]} gets a flow edge to
 *     {@code thinking[i+1]}. Multiple parallel tools all merge into
 *     the next thinking, mirroring "tool results feed the next LLM call".
 *   - When {@code thinking[i]} has no tool children (a thinking
 *     iteration that emitted only text), the iteration's text node
 *     bridges to {@code thinking[i+1]} instead — so the chain stays
 *     visually unbroken across iterations whose model response was
 *     pure narration. Falls back to a direct
 *     {@code thinking[i] → thinking[i+1]} flow when there's no text
 *     either.
 *
 * Each iteration's text node lives as a child of its thinking
 * (sibling of that iteration's tools), so no separate response-flow
 * branch is needed — the final iteration's text is just the last
 * leaf in its iteration's subtree.
 *
 * Flow edges carry a {@code { flow: true }} label so the readback pass
 * can mark them {@code kind: 'sequence'} and the renderer draws them
 * dashed.
 */
function addReActFlowEdges(
  graph: dagre.graphlib.Graph,
  nodes: ExecutionNode[],
): void {
  for (const parent of nodes) {
    if (parent.type === 'turn') {
      const thinkings = parent.children.filter((c) => c.type === 'thinking');

      for (let i = 0; i < thinkings.length - 1; i += 1) {
        const curr = thinkings[i]!;
        const next = thinkings[i + 1]!;
        const tools = curr.children.filter((c) => c.type === 'tool');
        if (tools.length > 0) {
          for (const t of tools) {
            graph.setEdge(t.id, next.id, { flow: true });
          }
          continue;
        }
        const text = curr.children.find((c) => c.type === 'text');
        if (text) {
          graph.setEdge(text.id, next.id, { flow: true });
        } else {
          graph.setEdge(curr.id, next.id, { flow: true });
        }
      }
    }
    if (parent.children.length > 0) {
      addReActFlowEdges(graph, parent.children);
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
 * Types that get a vertical dashed sibling-sequence edge between
 * consecutive same-type peers in {@link addSequenceEdges}. Currently
 * only {@code turn} — turns under a session stack vertically at the
 * same rank in LR layout, so a short vertical dashed connector is the
 * clearest expression of "next turn".
 *
 * Thinking nodes used to be on this list, but their ReAct-iteration
 * ordering is now expressed via horizontal flow edges through the tool
 * chain (see {@link addReActFlowEdges}). Tools are excluded by design —
 * within a thinking parent they're parallel; outside one we can't tell
 * parallel from sequential.
 */
const SEQUENCE_TYPES: ReadonlyArray<ExecutionNode['type']> = ['turn'];

/**
 * Walks the tree and pushes a {@code sequence}-kind edge between every
 * pair of consecutive same-type siblings whose type appears in
 * {@link SEQUENCE_TYPES}. Coordinates are derived from the dagre layout
 * positions: in LR layout siblings stack vertically at the same x, so
 * the edge runs from the bottom of {@code prev} to the top of
 * {@code curr}.
 */
function addSequenceEdges(
  siblings: ExecutionNode[],
  layoutById: Map<string, LayoutNode>,
  out: LayoutEdge[],
): void {
  for (let i = 1; i < siblings.length; i += 1) {
    const prev = siblings[i - 1]!;
    const curr = siblings[i]!;
    if (
      prev.type === curr.type &&
      SEQUENCE_TYPES.includes(prev.type as ExecutionNode['type'])
    ) {
      const p = layoutById.get(prev.id);
      const c = layoutById.get(curr.id);
      if (!p || !c) continue;
      out.push({
        id: `seq:${prev.id}->${curr.id}`,
        kind: 'sequence',
        from: { x: p.x, y: p.y + p.height / 2 },
        to: { x: c.x, y: c.y - c.height / 2 },
        status: c.status,
      });
    }
  }
  for (const sibling of siblings) {
    if (sibling.children.length > 0) {
      addSequenceEdges(sibling.children, layoutById, out);
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
    addReActFlowEdges(graph, tree.rootNodes);
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
      // Flow edges carry { flow: true } in their dagre label (set by
      // addReActFlowEdges); containment edges have the default empty
      // label. Detect by reading the label back.
      const label = graph.edge(e.v, e.w) as { flow?: boolean } | undefined;
      const kind: LayoutEdge['kind'] = label?.flow ? 'sequence' : 'containment';
      return {
        id: `${e.v}->${e.w}`,
        kind,
        from: { x: fromX, y: fromY },
        to: { x: toX, y: toY },
        status: to?.status ?? 'pending',
      };
    });

    // Sibling-sequence edges (vertical dashed) between consecutive turns
    // under a session/step — turns stack vertically at the same rank, so
    // the connector is short and clean as a post-layout overlay.
    // Thinking-to-thinking sequencing within a turn is handled in dagre
    // via flow edges (see addReActFlowEdges) — those go horizontally
    // through the tool chain and need dagre's rank-aware layout.
    addSequenceEdges(tree.rootNodes, nodesById, layoutEdges);

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
