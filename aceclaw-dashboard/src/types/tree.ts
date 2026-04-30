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
  | 'text'
  | 'thinking';

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
  /**
   * Monotonic counter used by the reducer when it needs to mint a synthetic
   * node id (e.g. {@code stream.subagent.start} doesn't carry a unique id
   * over the wire — see #439). Kept on state instead of a module-level
   * variable so the reducer stays pure and snapshot-replay produces the
   * same tree.
   */
  nextSyntheticId: number;
  /**
   * The thinking node that subsequent {@code stream.tool_use} events should
   * attach to as a child. Captures the ReAct semantic: thinking is the
   * cause, tool calls are its effects. Parallel tools from a single LLM
   * call share the same anchor (no thinking delta arrives between them);
   * the next iteration's thinking delta creates a fresh node and rotates
   * the anchor. {@code null} when no thinking has been emitted yet (e.g.
   * extended thinking disabled) — tools fall back to attaching to the turn.
   */
  currentThinkingId: string | null;
  /**
   * True when the current {@link #currentThinkingId} has been "sealed" by a
   * subsequent {@code tool_use} or text delta. The next thinking delta
   * creates a new thinking node instead of appending to the sealed one,
   * so multi-iteration ReAct turns produce one thinking node per
   * iteration rather than one fat concatenated node per turn.
   */
  thinkingSealed: boolean;
  /**
   * The text (narration / response) node currently being streamed for the
   * active iteration. Used as the anchor for {@code stream.tool_use}
   * events that follow narration in the same LLM call (so tools nest
   * under the text that announced them). Stays valid across parallel
   * tool_use events so they share the same parent. Reset when a new
   * iteration starts (new thinking delta, or when {@link textIsOpen}
   * is false and the next text delta arrives).
   */
  currentTextId: string | null;
  /**
   * True while the current iteration's text block is open for more
   * deltas. A {@code tool_use} closes the text block (the model has
   * stopped talking and started acting) — we keep
   * {@link currentTextId} for tool anchoring, but the next text delta
   * must mint a NEW node, because it belongs to the NEXT iteration.
   * Without this flag, an iteration that emits text without a
   * preceding thinking delta would silently merge into the previous
   * iteration's text (ids both keyed on {@link currentThinkingId}).
   */
  textIsOpen: boolean;
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
    nextSyntheticId: 1,
    currentThinkingId: null,
    thinkingSealed: false,
    currentTextId: null,
    textIsOpen: false,
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

/**
 * Visual semantic of an edge:
 *
 * - {@code containment}: parent-child relationship ("属于"). Solid stroke,
 *   curved bezier. The reducer's tree shape produces these — every node
 *   below the root has exactly one containment-edge in.
 * - {@code sequence}: temporal order between same-rank siblings whose
 *   ordering is meaningful (turns under a session, thinking nodes under
 *   a turn). Dashed stroke, straight line. Synthesized post-layout from
 *   sibling order — they're not in the dagre graph because forcing dagre
 *   to honour them would push siblings into different ranks and break the
 *   LR "siblings stack vertically" pattern.
 */
export type EdgeKind = 'containment' | 'sequence';

/** A directional edge between two laid-out nodes. */
export interface LayoutEdge {
  id: string;
  kind: EdgeKind;
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
