/**
 * Status glyph rendered inside each {@link GrowingNode}. Pure SVG so it
 * scales with the node's transform and doesn't add a font dependency.
 *
 * The glyphs intentionally avoid Unicode dingbats (◉, ✓, …) — those vary
 * wildly in baseline and width across system fonts. SVG primitives keep
 * the visual stable on every machine.
 */

import type { ExecutionStatus } from '../types/tree';

const COLORS: Record<ExecutionStatus, string> = {
  pending: '#64748b',
  running: '#3b82f6',
  completed: '#22c55e',
  failed: '#ef4444',
  paused: '#eab308',
  cancelled: '#475569',
};

interface StatusIconProps {
  status: ExecutionStatus;
  /** Centre of the icon in the parent {@code <g>}'s coordinate space. */
  cx: number;
  cy: number;
  /**
   * Optional override for the icon stroke/fill — lets the caller
   * tie the glyph to the node's type-specific accent (so a completed
   * thinking shows a violet check, a completed response an amber one)
   * instead of every check being the same status-default green.
   */
  color?: string;
}

export function StatusIcon({ status, cx, cy, color }: StatusIconProps) {
  const resolved = color ?? COLORS[status];
  switch (status) {
    case 'pending':
      // Empty circle — "not started yet".
      return (
        <circle cx={cx} cy={cy} r={5} fill="none" stroke={resolved} strokeWidth={1.5} />
      );
    case 'running':
      // Filled circle with a thin halo — pulses via parent GrowingNode.
      return <circle cx={cx} cy={cy} r={4.5} fill={resolved} />;
    case 'completed':
      // Checkmark inside the node's accent colour (passed in via `color`).
      return (
        <g stroke={resolved} strokeWidth={1.8} fill="none" strokeLinecap="round">
          <path d={`M ${cx - 4} ${cy} L ${cx - 1} ${cy + 3} L ${cx + 4} ${cy - 3}`} />
        </g>
      );
    case 'failed':
      // Cross.
      return (
        <g stroke={resolved} strokeWidth={1.8} strokeLinecap="round">
          <line x1={cx - 4} y1={cy - 4} x2={cx + 4} y2={cy + 4} />
          <line x1={cx - 4} y1={cy + 4} x2={cx + 4} y2={cy - 4} />
        </g>
      );
    case 'paused':
      // Two parallel bars (pause symbol).
      return (
        <g fill={resolved}>
          <rect x={cx - 4} y={cy - 4} width={2} height={8} />
          <rect x={cx + 2} y={cy - 4} width={2} height={8} />
        </g>
      );
    case 'cancelled':
      // Circle with a slash through it — clearly "wasn't allowed to finish".
      return (
        <g stroke={resolved} strokeWidth={1.5} fill="none">
          <circle cx={cx} cy={cy} r={5} />
          <line x1={cx - 4} y1={cy + 4} x2={cx + 4} y2={cy - 4} />
        </g>
      );
  }
}

export const STATUS_COLOR = COLORS;
