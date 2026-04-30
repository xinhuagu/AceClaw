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

/**
 * Stroke colour for sequence/flow edges. Picked light enough to read on
 * the bg-zinc-950 canvas without competing visually with the saturated
 * status colours on containment edges. zinc-400 (#94a3b8 → slate-400)
 * survives the dark background; the 0.5 opacity earlier rendered as
 * near-invisible.
 */
const SEQUENCE_STROKE = '#94a3b8';

export function GrowingEdge({ edge }: GrowingEdgeProps) {
  const isSequence = edge.kind === 'sequence';
  const stroke = isSequence ? SEQUENCE_STROKE : STATUS_COLOR[edge.status];
  const d = isSequence ? straightPath(edge) : bezierPath(edge);
  // Sequence edges carry a directional arrowhead so reads can tell flow
  // direction at a glance — important once parallel tools merge into the
  // next thinking and the eye needs to track multiple converging dashes.
  // Containment edges don't get one: the parent-on-the-left, child-on-
  // the-right convention plus the bezier shape already imply direction,
  // and adding arrowheads to every containment edge would be visual noise.
  const markerEnd = isSequence ? 'url(#seq-arrow)' : undefined;
  return (
    <motion.path
      d={d}
      stroke={stroke}
      // Sequence edges get the SAME stroke width as containment edges
      // and a longer dash gap so they read as a clear, intentional
      // dashed line on a dark background — not a faint hint.
      strokeWidth={isSequence ? 1.75 : 1.75}
      strokeDasharray={isSequence ? '7 5' : undefined}
      strokeLinecap={isSequence ? 'round' : 'butt'}
      fill="none"
      initial={{ pathLength: 0, opacity: 0 }}
      animate={{ pathLength: 1, opacity: isSequence ? 0.85 : 0.85 }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
      // exactOptionalPropertyTypes: avoid passing undefined to the
      // optional markerEnd attribute.
      {...(markerEnd ? { markerEnd } : {})}
    />
  );
}
