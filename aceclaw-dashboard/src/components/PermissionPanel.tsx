/**
 * PermissionPanel — inline approval card for a paused tool node (issue #437).
 *
 * Rendered as an HTML overlay anchored to the paused node's screen-space
 * coordinates inside {@link ExecutionTree}. Three states drive the visual:
 *
 *   1. {@code awaiting} — interactive: Approve / Deny buttons, countdown
 *      ring (gold → red as the deadline closes), keyboard shortcuts
 *      (A approves, D denies). Esc dismisses the panel without responding
 *      so the user can keep watching the tree.
 *   2. {@code resolved-cli} — CLI answered first (first-response-wins via
 *      the daemon, #433). Buttons disabled, panel grays out and shows
 *      "Approved via CLI" or "Denied via CLI"; auto-dismisses after
 *      DISMISS_AFTER_RESOLVED_MS so the tree returns to its normal state.
 *   3. {@code resolving} — user just clicked. Optimistic local resolve
 *      strips awaitingInput, which unmounts the panel — so this state is
 *      effectively never rendered. Kept on the type union for symmetry
 *      and so a future "approving…" overlay can slot in without churn.
 *
 * Anchoring uses pre-computed screen-space coordinates (pan + zoom
 * applied) rather than SVG {@code <foreignObject>}: HTML buttons in
 * foreignObject have spotty pointer-event behaviour across browsers,
 * and the panel needs to stay legible at any zoom — so we DON'T scale
 * with the tree. The container in {@link ExecutionTree} computes the
 * anchor each frame and feeds it as {@code anchorX} / {@code anchorY}.
 */

import { motion } from 'framer-motion';
import { useEffect, useMemo, useState } from 'react';
import type { ExecutionNode } from '../types/tree';

/** Daemon-side timeout — mirrors CancelAwareStreamContext.PERMISSION_RESPONSE_TIMEOUT_MS. */
export const PERMISSION_TIMEOUT_MS = 120_000;

/** How long to leave a CLI-resolved panel on screen before auto-dismissing. */
const DISMISS_AFTER_RESOLVED_MS = 1500;

/** Threshold (in seconds remaining) at which the countdown turns urgent red. */
const URGENT_THRESHOLD_S = 30;

interface PermissionPanelProps {
  node: ExecutionNode;
  /** Top-left anchor in container-relative pixels. */
  anchorX: number;
  anchorY: number;
  /** Called when the user clicks Approve. */
  onApprove: (requestId: string) => void;
  /** Called when the user clicks Deny. */
  onDeny: (requestId: string) => void;
  /** Called when the panel times out OR the CLI-resolved fade-out elapses. */
  onDismiss: (requestId: string) => void;
}

/**
 * Discriminator describing how the panel should render. Computed from
 * node metadata so the parent doesn't have to track its own resolution
 * state — everything funnels through the reducer.
 */
type PanelState =
  | { kind: 'awaiting' }
  | { kind: 'resolved-cli'; approved: boolean }
  | { kind: 'resolving'; approved: boolean };

function derivePanelState(node: ExecutionNode): PanelState {
  const resolvedBy = node.metadata?.['resolvedBy'];
  const resolvedApproved = node.metadata?.['resolvedApproved'];
  if (resolvedBy === 'cli' && typeof resolvedApproved === 'boolean') {
    return { kind: 'resolved-cli', approved: resolvedApproved };
  }
  if (resolvedBy === 'browser' && typeof resolvedApproved === 'boolean') {
    return { kind: 'resolving', approved: resolvedApproved };
  }
  return { kind: 'awaiting' };
}

/**
 * Best-effort split of the daemon's free-form description into a one-line
 * "intent" (verb phrase) and a "subject" (file path / command / etc.) the
 * panel can highlight separately. The daemon's prompt typically reads
 * something like {@code "Tool 'edit_file' wants to write to /path/to/file"}
 * — surface the path on its own line in monospace. Falls back to showing
 * the whole prompt when no recognisable subject is found.
 */
function splitDescription(prompt: string): { intent: string; subject?: string } {
  const trimmed = prompt.trim();
  // Path-like subject: the longest run that looks like a fs path or URL.
  const pathMatch = trimmed.match(/(["'`])?((?:[/~][\w./@\-:]+)|(?:[A-Za-z]:[\\/][^\s'"]+))\1?/);
  if (pathMatch?.[2]) {
    const subject = pathMatch[2];
    // Strip the matched subject from the intent so we don't show it twice.
    const intent = trimmed.replace(pathMatch[0], '').replace(/\s+/g, ' ').trim();
    return intent ? { intent, subject } : { intent: trimmed, subject };
  }
  // Bash command-like: the description starts with "Run " or "Execute "
  const cmdMatch = trimmed.match(/^(?:Run|Execute)[: ]\s*(.+)$/i);
  if (cmdMatch?.[1]) {
    return { intent: 'Run command', subject: cmdMatch[1] };
  }
  return { intent: trimmed };
}

export function PermissionPanel({
  node,
  anchorX,
  anchorY,
  onApprove,
  onDeny,
  onDismiss,
}: PermissionPanelProps) {
  const requestId = node.permissionRequestId;
  const requestedAt =
    typeof node.metadata?.['permissionRequestedAt'] === 'number'
      ? (node.metadata['permissionRequestedAt'] as number)
      : Date.now();

  const state = derivePanelState(node);
  const tool =
    (typeof node.metadata?.['permissionTool'] === 'string'
      ? (node.metadata['permissionTool'] as string)
      : null) ??
    (typeof node.metadata?.['tool'] === 'string'
      ? (node.metadata['tool'] as string)
      : 'tool');

  const { intent, subject } = useMemo(
    () => splitDescription(node.inputPrompt ?? `${tool} requires permission`),
    [node.inputPrompt, tool],
  );

  // Live "now" so the countdown ticks and the urgency colour shifts. 250 ms
  // resolution is plenty for a seconds counter and avoids re-rendering the
  // panel 60×/s; the timer clears the moment we leave the awaiting state.
  const [now, setNow] = useState<number>(() => Date.now());
  useEffect(() => {
    if (state.kind !== 'awaiting') return;
    const id = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(id);
  }, [state.kind]);

  // Auto-dismiss after the daemon's own deadline elapses — once we cross
  // the timeout the daemon will reject any incoming response anyway, so
  // leaving the panel on screen would just be misleading. onDismiss is
  // wired to the reducer's panel-clearing action; the daemon will emit a
  // tool_completed (likely with an error) shortly after.
  useEffect(() => {
    if (state.kind !== 'awaiting' || !requestId) return;
    const elapsed = now - requestedAt;
    if (elapsed >= PERMISSION_TIMEOUT_MS) onDismiss(requestId);
  }, [now, requestedAt, requestId, state.kind, onDismiss]);

  // CLI-resolved panels self-dismiss after the brief "via CLI" reveal so
  // the operator knows the daemon was the one that answered.
  useEffect(() => {
    if (state.kind !== 'resolved-cli' || !requestId) return;
    const id = window.setTimeout(
      () => onDismiss(requestId),
      DISMISS_AFTER_RESOLVED_MS,
    );
    return () => window.clearTimeout(id);
  }, [state.kind, requestId, onDismiss]);

  // Keyboard shortcuts — only active in the awaiting state.
  useEffect(() => {
    if (state.kind !== 'awaiting' || !requestId) return;
    const onKey = (e: KeyboardEvent) => {
      // Don't hijack typing in inputs/textareas (none in Tier 1, but defensive).
      const target = e.target as HTMLElement | null;
      if (target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA') return;
      if (e.key === 'a' || e.key === 'A') {
        e.preventDefault();
        onApprove(requestId);
      } else if (e.key === 'd' || e.key === 'D') {
        e.preventDefault();
        onDeny(requestId);
      } else if (e.key === 'Escape') {
        e.preventDefault();
        onDismiss(requestId);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [state.kind, requestId, onApprove, onDeny, onDismiss]);

  if (!requestId) return null;

  const remainingMs = Math.max(0, PERMISSION_TIMEOUT_MS - (now - requestedAt));
  const remainingS = Math.ceil(remainingMs / 1000);
  const ratio = remainingMs / PERMISSION_TIMEOUT_MS;
  const urgent = remainingS <= URGENT_THRESHOLD_S;

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.92, x: -12 }}
      animate={{ opacity: 1, scale: 1, x: 0 }}
      exit={{ opacity: 0, scale: 0.92, transition: { duration: 0.18 } }}
      transition={{ type: 'spring', stiffness: 280, damping: 26 }}
      className="pointer-events-auto absolute z-30 select-text"
      style={{
        left: anchorX,
        top: anchorY,
        // Anchor by the panel's left middle so the panel grows down/right
        // from the connector point.
        transform: 'translateY(-50%)',
      }}
      role="dialog"
      aria-label={`Permission required for ${tool}`}
    >
      {/* Connector — small triangular notch pointing back to the node so the
          panel reads as visually attached. Positioned just to the panel's
          left edge. */}
      <div
        aria-hidden
        className="absolute left-[-7px] top-1/2 h-3 w-3 -translate-y-1/2 rotate-45 border-l border-t border-amber-400/50 bg-zinc-900/95"
      />
      <div
        className={[
          'relative w-[320px] overflow-hidden rounded-xl border backdrop-blur-md',
          'bg-zinc-900/95 shadow-2xl shadow-amber-500/10',
          state.kind === 'resolved-cli'
            ? 'border-zinc-700/70 opacity-80'
            : 'border-amber-400/50',
        ].join(' ')}
      >
        {/* Top edge accent — gold gradient that matches the paused node's hue. */}
        <div
          className={[
            'h-[2px] w-full',
            state.kind === 'resolved-cli'
              ? 'bg-gradient-to-r from-zinc-600 via-zinc-500 to-zinc-700'
              : 'bg-gradient-to-r from-amber-400 via-amber-300 to-amber-500',
          ].join(' ')}
        />

        <div className="flex items-start gap-3 px-4 pt-3">
          <div className="flex flex-1 flex-col gap-0.5">
            <div className="flex items-center gap-2 text-[10px] font-medium uppercase tracking-[0.14em] text-amber-300/90">
              <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-amber-400" />
              Permission required
            </div>
            <div className="font-mono text-sm text-zinc-100">{tool}</div>
          </div>
          {state.kind === 'awaiting' ? (
            <CountdownRing ratio={ratio} seconds={remainingS} urgent={urgent} />
          ) : null}
        </div>

        <div className="px-4 pb-3 pt-2">
          <p className="text-xs leading-snug text-zinc-300">{intent}</p>
          {subject ? (
            <code className="mt-2 block max-h-[60px] overflow-y-auto rounded-md border border-zinc-800/60 bg-zinc-950/70 px-2 py-1.5 font-mono text-[11px] leading-snug text-amber-100/90 break-all">
              {subject}
            </code>
          ) : null}
        </div>

        {state.kind === 'awaiting' ? (
          <div className="flex gap-2 border-t border-zinc-800/60 px-4 py-3">
            <button
              type="button"
              onClick={() => onApprove(requestId)}
              className={[
                'flex-1 rounded-md px-3 py-1.5 text-xs font-semibold tracking-wide',
                'bg-emerald-500/90 text-emerald-50 shadow-sm',
                'transition hover:bg-emerald-400 hover:shadow-emerald-500/40',
                'focus-visible:outline focus-visible:outline-2 focus-visible:outline-emerald-300',
              ].join(' ')}
              aria-label="Approve permission"
            >
              <span>Approve</span>
              <kbd className="ml-2 rounded bg-emerald-900/50 px-1 py-0 font-mono text-[10px] opacity-80">
                A
              </kbd>
            </button>
            <button
              type="button"
              onClick={() => onDeny(requestId)}
              className={[
                'flex-1 rounded-md px-3 py-1.5 text-xs font-semibold tracking-wide',
                'border border-zinc-700/80 bg-zinc-900/40 text-zinc-200',
                'transition hover:border-rose-500/70 hover:bg-rose-500/10 hover:text-rose-200',
                'focus-visible:outline focus-visible:outline-2 focus-visible:outline-rose-400',
              ].join(' ')}
              aria-label="Deny permission"
            >
              <span>Deny</span>
              <kbd className="ml-2 rounded bg-zinc-800/80 px-1 py-0 font-mono text-[10px] opacity-80">
                D
              </kbd>
            </button>
          </div>
        ) : (
          <div className="flex items-center gap-2 border-t border-zinc-800/60 px-4 py-3">
            <span
              className={[
                'inline-flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-bold',
                state.kind === 'resolving' || state.kind === 'resolved-cli'
                  ? state.approved
                    ? 'bg-emerald-500/20 text-emerald-300'
                    : 'bg-rose-500/20 text-rose-300'
                  : 'bg-zinc-700 text-zinc-300',
              ].join(' ')}
              aria-hidden
            >
              {state.kind === 'resolving' || state.kind === 'resolved-cli'
                ? state.approved
                  ? '✓'
                  : '✗'
                : '·'}
            </span>
            <span className="text-xs text-zinc-300">
              {state.kind === 'resolved-cli'
                ? state.approved
                  ? 'Approved via CLI'
                  : 'Denied via CLI'
                : state.approved
                  ? 'Approving…'
                  : 'Denying…'}
            </span>
          </div>
        )}
      </div>
    </motion.div>
  );
}

interface CountdownRingProps {
  /** 0..1 — fraction of total time remaining. */
  ratio: number;
  seconds: number;
  urgent: boolean;
}

/**
 * Compact circular countdown — drawn in SVG so the stroke can animate
 * from full to empty along {@code strokeDasharray} as time elapses.
 * Centre shows the seconds-remaining number; the ring's hue shifts from
 * gold to red as urgency rises.
 */
function CountdownRing({ ratio, seconds, urgent }: CountdownRingProps) {
  const RADIUS = 16;
  const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
  const offset = CIRCUMFERENCE * (1 - Math.max(0, Math.min(1, ratio)));
  const stroke = urgent ? '#f43f5e' : '#fbbf24';
  return (
    <div className="relative flex h-9 w-9 shrink-0 items-center justify-center">
      <svg
        viewBox="0 0 40 40"
        className="absolute inset-0 -rotate-90"
        aria-hidden
      >
        <circle
          cx={20}
          cy={20}
          r={RADIUS}
          fill="none"
          stroke="rgb(63 63 70 / 0.6)"
          strokeWidth={2.5}
        />
        <circle
          cx={20}
          cy={20}
          r={RADIUS}
          fill="none"
          stroke={stroke}
          strokeWidth={2.5}
          strokeLinecap="round"
          strokeDasharray={CIRCUMFERENCE}
          strokeDashoffset={offset}
          style={{ transition: 'stroke-dashoffset 250ms linear, stroke 200ms ease' }}
        />
      </svg>
      <span
        className={[
          'relative font-mono text-[10px] tabular-nums',
          urgent ? 'text-rose-300' : 'text-amber-200',
        ].join(' ')}
        aria-label={`${seconds} seconds remaining`}
      >
        {seconds}
      </span>
    </div>
  );
}
