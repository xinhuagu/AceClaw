/**
 * GrowingEdge — a single edge between two nodes, animated to "draw"
 * itself from source to target on first appearance via Framer Motion's
 * {@code pathLength} primitive.
 *
 * Two visual variants keyed by {@code edge.kind}:
 *
 * - {@code containment} (default): solid bezier, status-coloured. Reads
 *   as "child belongs to parent" — what the reducer's tree shape
 *   produces.
 * - {@code sequence}: dashed straight line in muted grey. Reads as
 *   "this happened after that, but they're peers" — for turn-after-turn
 *   under a session, or thinking-after-thinking across ReAct iterations.
 *
 * Edge status colour matches the TARGET node's status — readers track
 * the edge into the next state, not out of the previous one.
 */

import { motion } from 'framer-motion';
import type { LayoutEdge } from '../types/tree';
import { STATUS_COLOR } from './StatusIcon';

interface GrowingEdgeProps {
  edge: LayoutEdge;
}

/**
 * Cubic bezier from the right edge of the source to the left edge of the
 * target. Control points sit on the same horizontal as their endpoints so
 * the curve enters/exits each node along the rank's flow direction.
 */
function bezierPath(edge: LayoutEdge): string {
  const { from, to } = edge;
  const midX = (from.x + to.x) / 2;
  return `M ${from.x} ${from.y} C ${midX} ${from.y}, ${midX} ${to.y}, ${to.x} ${to.y}`;
}

/**
 * Straight-line path between two points. Used for sequence edges where
 * a curve would suggest causation (which a sequence edge specifically
 * does NOT — it's just temporal order between siblings).
 */
function straightPath(edge: LayoutEdge): string {
  const { from, to } = edge;
  return `M ${from.x} ${from.y} L ${to.x} ${to.y}`;
}

/** Muted neutral for sequence edges so they recede behind containment edges. */
const SEQUENCE_STROKE = '#52525b';

export function GrowingEdge({ edge }: GrowingEdgeProps) {
  const isSequence = edge.kind === 'sequence';
  const stroke = isSequence ? SEQUENCE_STROKE : STATUS_COLOR[edge.status];
  const d = isSequence ? straightPath(edge) : bezierPath(edge);
  return (
    <motion.path
      d={d}
      stroke={stroke}
      strokeWidth={isSequence ? 1.25 : 1.75}
      strokeDasharray={isSequence ? '4 4' : undefined}
      fill="none"
      initial={{ pathLength: 0, opacity: 0 }}
      animate={{ pathLength: 1, opacity: isSequence ? 0.5 : 0.85 }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
    />
  );
}
