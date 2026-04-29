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
  | 'session'
  | 'request'
  | 'plan'
  | 'step'
  | 'turn'
  | 'tool'
  | 'subagent'
  | 'text';

/** Lifecycle status, presentation-agnostic. */
export type ExecutionStatus =
  | 'pending'
  | 'running'
  | 'completed'
  | 'failed'
  | 'paused'
  | 'cancelled';

/** A node in the execution tree. Shape mirrors the reducer's output (#435). */
export interface ExecutionNode {
  id: string;
  type: ExecutionNodeType;
  status: ExecutionStatus;
  label: string;
  startTime?: number;
  endTime?: number;
  duration?: number;
  children: ExecutionNode[];
  /** True when sibling nodes overlap in time (parallel tool calls). */
  parallel?: boolean;
  /** True while a permission.request is pending on this subtree. */
  awaitingInput?: boolean;
  /** Permission prompt text the user is being asked. */
  inputPrompt?: string;
  /** Server-assigned correlation ID for permission round-trips. */
  permissionRequestId?: string;
  /** Tool/error/replan metadata; opaque to the renderer. */
  metadata?: Record<string, unknown>;
  /** Reduced text deltas accumulated for a {@code type === 'text'} node. */
  text?: string;
  /** Set on failed nodes for rendering an error chip. */
  error?: string;
}

/**
 * Top-level reducer state (#435 → #436).
 *
 * One ExecutionTree per session: the WebSocket carries multi-session traffic
 * if more than one CLI is attached, but each browser tab views one session at
 * a time. Cross-session demultiplexing is the hook's concern, not the reducer.
 */
export interface ExecutionTree {
  /** Session this tree belongs to (matches the WS envelope's sessionId). */
  sessionId: string;
  /**
   * Top-level nodes. Tier 1 has at most one session node; the array is plural
   * so future tiers (multi-session views) can grow without re-shaping state.
   */
  rootNodes: ExecutionNode[];
  /** Currently-executing leaf node — drives auto-scroll in the renderer. */
  activeNodeId: string | null;
  /**
   * Highest envelope eventId observed so far. Snapshot reconnect (#432)
   * sends this back as a watermark to skip events the browser already has.
   */
  lastEventId: number;
  /** Aggregate counters surfaced in the UI sidebar. */
  stats: TreeStats;
}

export interface TreeStats {
  totalTools: number;
  completedTools: number;
  failedTools: number;
  totalTurns: number;
  inputTokens: number;
  outputTokens: number;
}

/** Fresh empty state for a session — convenience constructor. */
export function emptyTree(sessionId: string): ExecutionTree {
  return {
    sessionId,
    rootNodes: [],
    activeNodeId: null,
    lastEventId: 0,
    stats: {
      totalTools: 0,
      completedTools: 0,
      failedTools: 0,
      totalTurns: 0,
      inputTokens: 0,
      outputTokens: 0,
    },
  };
}

// ---------------------------------------------------------------------------
// Layout types — output of #436's dagre layout pass
// ---------------------------------------------------------------------------

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
