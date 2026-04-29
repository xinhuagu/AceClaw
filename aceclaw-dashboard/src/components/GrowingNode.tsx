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

/**
 * Per-type background overrides. Each non-default type gets its own
 * palette so the eye can tell — at a glance — what kind of work each
 * box represents:
 *
 *   - {@code thinking}: indigo/violet — model reasoning
 *   - {@code text} (response): rose/pink — final answer to the user
 *
 * Tool nodes inherit the default blue/green status palette (running
 * blue → completed green) since they're the "default" work type and
 * make up the bulk of the tree. Other types fall through to
 * {@link STATUS_BG}.
 */
const TYPE_BG: Partial<Record<string, Record<string, string>>> = {
  thinking: {
    pending: '#312e81', // indigo-900
    running: '#5b21b6', // violet-800 — bright while actively thinking
    completed: '#1e1b4b', // indigo-950 — muted once the turn moves on
    failed: '#7f1d1d',
    paused: '#713f12',
    cancelled: '#1f2937',
  },
  text: {
    pending: '#831843', // pink-900
    running: '#be185d', // pink-700 — bright while streaming response
    completed: '#831843', // pink-900 — muted once the turn ends
    failed: '#7f1d1d',
    paused: '#713f12',
    cancelled: '#1f2937',
  },
};

/**
 * Per-type stroke overrides. Mirrors {@link TYPE_BG} so the border
 * outline is consistent with the fill. Falls through to the default
 * status-colour border for un-overridden types.
 */
const TYPE_BORDER: Partial<Record<string, Record<string, string>>> = {
  thinking: {
    pending: '#6366f1', // indigo-500
    running: '#a78bfa', // violet-400
    completed: '#818cf8', // indigo-400 (muted)
    failed: '#ef4444',
    paused: '#f59e0b',
    cancelled: '#71717a',
  },
  text: {
    pending: '#f472b6', // pink-400
    running: '#ec4899', // pink-500
    completed: '#f9a8d4', // pink-300 (muted)
    failed: '#ef4444',
    paused: '#f59e0b',
    cancelled: '#71717a',
  },
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
  const typeBg = TYPE_BG[node.type]?.[node.status];
  const typeBorder = TYPE_BORDER[node.type]?.[node.status];
  const bg = typeBg ?? STATUS_BG[node.status] ?? STATUS_BG['pending']!;
  const border = typeBorder ?? STATUS_COLOR[node.status];
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
