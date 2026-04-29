/**
 * GrowingNode — one tree node rendered as an SVG group with three layers:
 *
 *   1. A status-coloured rounded rectangle background (with a pulsing glow
 *      while the node is {@code running}).
 *   2. A {@link StatusIcon} on the left.
 *   3. A label + optional duration badge on the right.
 *
 * Spring-in animation pushes the node from {@code -40px} on the x-axis up
 * to its final dagre-computed position so newly-added nodes feel like they
 * "snap in" along the flow direction.
 */

import { motion } from 'framer-motion';
import type { LayoutNode } from '../types/tree';
import { STATUS_COLOR, StatusIcon } from './StatusIcon';

const STATUS_BG: Record<string, string> = {
  pending: '#1e293b',
  running: '#1e3a5f',
  completed: '#14532d',
  failed: '#7f1d1d',
  paused: '#713f12',
  cancelled: '#1f2937',
};

interface GrowingNodeProps {
  node: LayoutNode;
}

/**
 * Truncates labels that would overrun the fixed 180-px node width. The cap
 * (24 chars) was chosen so JetBrains-Mono 13px text fits with margins.
 */
function truncate(s: string, max = 24): string {
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}

export function GrowingNode({ node }: GrowingNodeProps) {
  const bg = STATUS_BG[node.status] ?? STATUS_BG['pending']!;
  const border = STATUS_COLOR[node.status];
  // Top-left corner of the node in dagre coords (it gives us centres).
  const left = node.x - node.width / 2;
  const top = node.y - node.height / 2;
  const isRunning = node.status === 'running';

  // Pulse the running node's glow so the eye picks up the active branch
  // at a glance. The animation runs forever; framer-motion handles unmount
  // cleanup when the node transitions out of running. Conditional spread
  // satisfies exactOptionalPropertyTypes (can't pass undefined to animate/
  // transition).
  const pulseProps = isRunning
    ? {
        animate: {
          filter: [
            `drop-shadow(0 0 2px ${border}55)`,
            `drop-shadow(0 0 8px ${border}cc)`,
            `drop-shadow(0 0 2px ${border}55)`,
          ],
        },
        transition: {
          repeat: Infinity,
          duration: 1.5,
          ease: 'easeInOut' as const,
        },
      }
    : {};

  return (
    <motion.g
      initial={{ opacity: 0, scale: 0.6, x: -40 }}
      animate={{ opacity: 1, scale: 1, x: 0 }}
      exit={{ opacity: 0, scale: 0.6, transition: { duration: 0.15 } }}
      transition={{ type: 'spring', stiffness: 350, damping: 28 }}
      style={{ transformOrigin: `${node.x}px ${node.y}px` }}
    >
      <motion.rect
        x={left}
        y={top}
        width={node.width}
        height={node.height}
        rx={8}
        fill={bg}
        stroke={border}
        strokeWidth={1.5}
        {...pulseProps}
      />
      <StatusIcon status={node.status} cx={left + 16} cy={node.y} />
      <text
        x={left + 30}
        y={node.y + 4}
        fontFamily="ui-monospace, 'JetBrains Mono', monospace"
        fontSize={12}
        fill="#e5e7eb"
      >
        {truncate(node.label)}
      </text>
      {typeof node.duration === 'number' && node.duration > 0 ? (
        <text
          x={left + node.width - 8}
          y={top + 14}
          textAnchor="end"
          fontFamily="ui-monospace, 'JetBrains Mono', monospace"
          fontSize={10}
          fill="#94a3b8"
        >
          {node.duration < 1000
            ? `${Math.round(node.duration)}ms`
            : `${(node.duration / 1000).toFixed(1)}s`}
        </text>
      ) : null}
    </motion.g>
  );
}
