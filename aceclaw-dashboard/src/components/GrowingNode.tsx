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
 *
 * <h3>Palette: tinted-glass on dark</h3>
 *
 * <p>Each node renders as a translucent tinted card — accent colour at low
 * opacity for the fill, full-opacity accent for the border. Running nodes
 * have a higher fill alpha so they look "lit" against their muted
 * completed siblings, without the heavy dark-jewel saturation an opaque
 * fill would carry. The look matches modern dark-mode dashboards
 * (Linear/Vercel/Notion) — light, airy, consistent.
 *
 * <p>Three node types pick distinct hue families so the eye can sort them
 * at a glance:
 *
 *   - {@code tool}     — sky/emerald (the workhorse default)
 *   - {@code thinking} — violet      (model reasoning)
 *   - {@code text}     — amber       (final answer)
 *
 * Within a type, brightness/opacity contrasts running vs completed.
 */

import { motion } from 'framer-motion';
import type { LayoutNode } from '../types/tree';
import { STATUS_COLOR, StatusIcon } from './StatusIcon';

/**
 * Accent colour per (type, status). Used as both fill (with low opacity)
 * and stroke (full opacity). Default branch is the tool palette —
 * everything that isn't thinking/text inherits it.
 */
const TYPE_ACCENT: Record<string, Record<string, string>> = {
  default: {
    pending: '#94a3b8', // slate-400
    running: '#60a5fa', // blue-400 — clear sky blue while active
    completed: '#4ade80', // green-400 — fresh green when done
    failed: '#f87171', // red-400
    paused: '#fbbf24', // amber-400
    cancelled: '#71717a', // zinc-500
  },
  thinking: {
    pending: '#a5b4fc', // indigo-300
    running: '#a78bfa', // violet-400 — bright lavender while reasoning
    completed: '#c4b5fd', // violet-300 — paler, recedes once sealed
    failed: '#f87171',
    paused: '#fbbf24',
    cancelled: '#71717a',
  },
  text: {
    pending: '#fcd34d', // amber-300
    running: '#fbbf24', // amber-400 — warm gold while streaming
    completed: '#fde68a', // amber-200 — paler gold once muted
    failed: '#f87171',
    paused: '#fbbf24',
    cancelled: '#71717a',
  },
};

/**
 * Fill alpha per status. Running nodes pop; completed recede. The
 * difference here, plus the colour shift between accent palettes, is
 * what carries the running ↔ done signal — opacity contrast on a
 * shared dark background reads as "active vs at rest" without the
 * heaviness of fully opaque tile fills.
 */
const FILL_ALPHA: Record<string, number> = {
  pending: 0.08,
  running: 0.22,
  completed: 0.12,
  failed: 0.2,
  paused: 0.18,
  cancelled: 0.08,
};

interface GrowingNodeProps {
  node: LayoutNode;
  /**
   * Click handler for nodes that are awaiting permission (#437). When
   * the user clicks an awaiting node the parent opens its
   * {@link PermissionPanel}; non-awaiting clicks are no-ops. Optional
   * so test scaffolding can render without wiring panel state.
   */
  onAwaitingClick?: (requestId: string) => void;
  /**
   * True when this node's permission panel is currently open. Used to
   * show a "panel open" visual hint (different border / cursor) so the
   * user can tell which node owns the floating panel they're looking at.
   */
  isOpenPanel?: boolean;
}

/**
 * Truncates labels that would overrun the fixed 180-px node width.
 *
 * <p>Cap calc: usable label area = node.width - 30 (left padding for
 * status icon + spacing) - 8 (right padding) = 142 px. JetBrains Mono
 * at 12 px averages ~7.2 px per character (some glyphs wider, e.g. 'm',
 * 'w'), so 142 / 7.2 ≈ 19. Cap at 18 to leave a hair of slack and so
 * the trailing `…` still fits without pushing the last visible glyph
 * past the right edge — labels like {@code mcp__agent-wiki__wiki_search}
 * were overflowing the previous 24-char cap.
 */
function truncate(s: string, max = 18): string {
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}

export function GrowingNode({ node, onAwaitingClick, isOpenPanel }: GrowingNodeProps) {
  const palette = TYPE_ACCENT[node.type] ?? TYPE_ACCENT['default']!;
  // The accent picks per-type-and-status; if a type is missing a status
  // fallback (shouldn't happen with the palette above), drop back to the
  // default tool palette — same reason a missing-key in TYPE_ACCENT does.
  const accent =
    palette[node.status] ??
    TYPE_ACCENT['default']![node.status] ??
    STATUS_COLOR[node.status];
  const fillAlpha = FILL_ALPHA[node.status] ?? FILL_ALPHA['pending']!;
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
            `drop-shadow(0 0 2px ${accent}55)`,
            `drop-shadow(0 0 8px ${accent}cc)`,
            `drop-shadow(0 0 2px ${accent}55)`,
          ],
        },
        transition: {
          repeat: Infinity,
          duration: 1.5,
          ease: 'easeInOut' as const,
        },
      }
    : {};

  // Awaiting nodes get an interactive cursor and a click handler that
  // opens the permission panel. Stop pointer/click propagation so the
  // parent's pan handler doesn't grab pointer capture and swallow the
  // click — same fix applied inside PermissionPanel for its buttons.
  const isAwaiting =
    node.awaitingInput === true && node.permissionRequestId !== undefined;
  const interactive = isAwaiting && onAwaitingClick !== undefined;

  return (
    <motion.g
      initial={{ opacity: 0, scale: 0.6, x: -40 }}
      animate={{ opacity: 1, scale: 1, x: 0 }}
      exit={{ opacity: 0, scale: 0.6, transition: { duration: 0.15 } }}
      transition={{ type: 'spring', stiffness: 350, damping: 28 }}
      style={{
        transformOrigin: `${node.x}px ${node.y}px`,
        cursor: interactive ? 'pointer' : 'default',
      }}
      // Keyboard + ARIA: an awaiting node is a button (it opens the
      // permission panel); make it focusable, give it a role/label, and
      // wire Enter/Space the same way as click. aria-pressed flips when
      // the panel is open so screen readers reflect the toggle state.
      // Non-interactive nodes (any tree node not awaiting permission)
      // stay unfocusable so the tab order matches the actionable set.
      tabIndex={interactive ? 0 : undefined}
      role={interactive ? 'button' : undefined}
      aria-label={
        interactive ? `Open permission panel for ${node.label}` : undefined
      }
      aria-pressed={interactive ? isOpenPanel === true : undefined}
      onPointerDown={interactive ? (e) => e.stopPropagation() : undefined}
      onClick={
        interactive
          ? (e) => {
              e.stopPropagation();
              onAwaitingClick!(node.permissionRequestId!);
            }
          : undefined
      }
      onKeyDown={
        interactive
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                e.stopPropagation();
                onAwaitingClick!(node.permissionRequestId!);
              }
            }
          : undefined
      }
    >
      <motion.rect
        x={left}
        y={top}
        width={node.width}
        height={node.height}
        rx={8}
        fill={accent}
        fillOpacity={fillAlpha}
        stroke={accent}
        strokeWidth={isOpenPanel ? 2.5 : 1.5}
        {...pulseProps}
      />
      {/*
        "Click to review" hint for awaiting nodes (#437). A small amber
        chip on the top-right of the node so the operator can spot
        permission gates at a glance and knows the node is interactive.
        Hidden once the panel is open or the request resolves.
      */}
      {isAwaiting && !isOpenPanel ? (
        <g pointerEvents="none">
          <rect
            x={left + node.width - 64}
            y={top - 10}
            width={62}
            height={16}
            rx={8}
            fill="#fbbf24"
            fillOpacity={0.95}
          />
          <text
            x={left + node.width - 33}
            y={top + 1}
            textAnchor="middle"
            fontFamily="ui-monospace, 'JetBrains Mono', monospace"
            fontSize={9}
            fontWeight={600}
            fill="#451a03"
          >
            click ✓/✗
          </text>
        </g>
      ) : null}
      <StatusIcon status={node.status} cx={left + 16} cy={node.y} color={accent} />
      <text
        x={left + 30}
        y={node.y + 4}
        fontFamily="ui-monospace, 'JetBrains Mono', monospace"
        fontSize={12}
        fill="#e5e7eb"
      >
        {/* Native SVG <title> renders as a tooltip on hover so the
            full label is recoverable when truncate() drops chars. */}
        <title>{node.label}</title>
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
      {/*
        Stop-reason badge on truncated turns: MAX_TOKENS / ERROR / etc
        get a small amber tag at the bottom-right of the turn node so the
        reader can tell at a glance why a turn ended without a normal
        final-text response. END_TURN (the default) shows nothing.
      */}
      {node.type === 'turn' &&
      typeof node.metadata?.['stopReason'] === 'string' &&
      node.metadata['stopReason'] !== 'END_TURN' ? (
        <text
          x={left + node.width - 8}
          y={top + node.height - 6}
          textAnchor="end"
          fontFamily="ui-monospace, 'JetBrains Mono', monospace"
          fontSize={9}
          fill="#fbbf24"
        >
          ⚠ {String(node.metadata['stopReason']).toLowerCase()}
        </text>
      ) : null}
    </motion.g>
  );
}
