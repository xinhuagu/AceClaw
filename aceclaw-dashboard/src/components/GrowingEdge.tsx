/**
 * GrowingEdge — a single bezier curve between two nodes, animated to
 * "draw" itself from source to target on first appearance via Framer
 * Motion's {@code pathLength} primitive.
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

export function GrowingEdge({ edge }: GrowingEdgeProps) {
  const stroke = STATUS_COLOR[edge.status];
  return (
    <motion.path
      d={bezierPath(edge)}
      stroke={stroke}
      strokeWidth={1.75}
      fill="none"
      initial={{ pathLength: 0, opacity: 0 }}
      animate={{ pathLength: 1, opacity: 0.85 }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
    />
  );
}
