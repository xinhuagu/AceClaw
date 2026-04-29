/**
 * Tree types for the Tier 1 execution dashboard (epic #430).
 *
 * These are the OUTPUT of the reducer (#435) and INPUT of the layout
 * engine + tree component (#436). The wire format that comes off the
 * WebSocket bridge lives in {@link ./events.ts} — this file is strictly
 * about the in-memory tree the browser builds.
 */

/** Discriminator for the kind of node a row represents in the tree. */
export type ExecutionNodeType =
  | 'request'
  | 'turn'
  | 'tool'
  | 'text'
  | 'plan'
  | 'step'
  | 'subagent'
  | 'permission';

/** Lifecycle status, presentation-agnostic. */
export type ExecutionStatus =
  | 'pending'
  | 'running'
  | 'completed'
  | 'failed'
  | 'paused';

/** A node in the execution tree. Shape mirrors the reducer's output (#435). */
export interface ExecutionNode {
  id: string;
  type: ExecutionNodeType;
  status: ExecutionStatus;
  label: string;
  startTime?: number;
  duration?: number;
  children: ExecutionNode[];
  /** True when sibling nodes overlap in time (parallel tool calls). */
  parallel?: boolean;
  /** Permission-request nodes set this while waiting on user approval. */
  awaitingInput?: boolean;
  inputPrompt?: string;
  /** Set on failed nodes for rendering an error chip. */
  error?: string;
}

/** A node decorated with absolute coordinates from the layout engine. */
export interface LayoutNode extends ExecutionNode {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** A directional edge between two laid-out nodes. */
export interface LayoutEdge {
  id: string;
  from: { x: number; y: number };
  to: { x: number; y: number };
  status: ExecutionStatus;
}

/** Output of the layout pass — everything the SVG needs in one bundle. */
export interface TreeLayout {
  nodes: LayoutNode[];
  edges: LayoutEdge[];
  /** SVG viewBox string ready for {@code <svg viewBox=...>}. */
  viewBox: string;
  width: number;
  height: number;
}
