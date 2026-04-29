/**
 * useExecutionTree (issue #435).
 *
 * Owns one WebSocket to the daemon's bridge (#431) and feeds every inbound
 * {@link DaemonEventEnvelope} to {@link executionTreeReducer}. The hook is
 * responsible for transport concerns the reducer must stay pure of:
 *
 * - JSON parsing of WS frames.
 * - Envelope shape validation, including a per-method critical-field check
 *   for the events that can't recover from a missing field (e.g.
 *   {@code permission.request} without {@code requestId}).
 * - Method allow-listing — unknown methods (Tier 2/3 forward-compat) are
 *   dropped at the boundary so the reducer's switch can stay exhaustive in
 *   the type system.
 * - Cross-session filter — the WebSocket bridge broadcasts every active
 *   session's events to every connected client (the reducer does not filter).
 *   This hook is single-session by contract and drops envelopes whose
 *   {@code sessionId} does not match the {@code sessionId} prop.
 * - Tree reset when {@code sessionId} changes — switching sessions in a tab
 *   must clear stale tree state.
 * - Reconnect with exponential backoff. The snapshot+replay handshake (#432)
 *   plugs in here once that endpoint exists; the watermark is already tracked
 *   on {@code state.lastEventId} so the reconnect path can send it as-is.
 */

import { useCallback, useEffect, useState } from 'react';
import type {
  DaemonEvent,
  DaemonEventEnvelope,
  UnknownDaemonEvent,
} from '../types/events';
import { emptyTree, type ExecutionTree } from '../types/tree';
import { executionTreeReducer } from '../reducers/treeReducer';

/** Connection lifecycle states surfaced to the renderer. */
export type WsStatus =
  | 'connecting'
  | 'open'
  | 'reconnecting'
  | 'closed'
  | 'error';

/** Shape returned to consumers — tree state plus a connection indicator. */
export interface UseExecutionTreeResult {
  tree: ExecutionTree;
  status: WsStatus;
}

/**
 * Methods the reducer's switch handles. Used to gate inbound messages so the
 * reducer never sees a Tier 2/3 forward-compat event whose params shape is
 * not in {@link DaemonEvent}.
 */
const KNOWN_METHODS = new Set<DaemonEvent['method']>([
  'stream.session_started',
  'stream.turn_started',
  'stream.turn_completed',
  'stream.thinking',
  'stream.text',
  'stream.tool_use',
  'stream.tool_completed',
  'stream.heartbeat',
  'stream.error',
  'stream.cancelled',
  'stream.budget_exhausted',
  'stream.compaction',
  'stream.subagent.start',
  'stream.subagent.end',
  'stream.usage',
  'stream.gate',
  'stream.plan_created',
  'stream.plan_step_started',
  'stream.plan_step_completed',
  'stream.plan_step_fallback',
  'stream.plan_replanned',
  'stream.plan_completed',
  'stream.plan_escalated',
  'permission.request',
]);

/**
 * Per-method critical-field allow-list. The reducer copes with most missing
 * params (numeric fields default to 0, optional strings stay undefined), but
 * a handful of events drive flow-control decisions and would silently corrupt
 * downstream state if a key field were missing — e.g. a {@code permission.request}
 * without {@code requestId} would orphan the response future. This map is
 * NOT exhaustive on purpose; it only guards the events whose handlers can't
 * recover from a malformed param shape.
 */
const CRITICAL_FIELDS: Partial<Record<DaemonEvent['method'], readonly string[]>> = {
  'permission.request': ['requestId', 'description', 'tool'],
  'stream.tool_use': ['id', 'name'],
  'stream.tool_completed': ['id', 'name'],
  'stream.turn_started': ['requestId', 'turnNumber'],
  'stream.turn_completed': ['requestId', 'turnNumber'],
  'stream.session_started': ['sessionId', 'model'],
  'stream.plan_created': ['planId', 'steps'],
  'stream.plan_step_started': ['planId', 'stepIndex'],
  'stream.plan_step_completed': ['planId', 'stepIndex'],
};

const RECONNECT_INITIAL_MS = 500;
const RECONNECT_MAX_MS = 30_000;

/**
 * Subscribes to the daemon's WebSocket bridge and exposes the current
 * execution tree. Unmounting the consuming component closes the socket and
 * stops any pending reconnect timer.
 *
 * Switching {@code sessionId} after mount resets the tree — listeners in a
 * multi-session UI typically remount via {@code key={sessionId}} but this
 * hook is also defensible on its own.
 *
 * @param wsUrl     e.g. {@code "ws://localhost:3141/ws"} — daemon-bound, never null.
 * @param sessionId session this tree belongs to. Envelopes for any other
 *                  session are dropped before dispatch.
 */
export function useExecutionTree(
  wsUrl: string,
  sessionId: string,
): UseExecutionTreeResult {
  const [tree, setTree] = useState<ExecutionTree>(() => emptyTree(sessionId));
  const [status, setStatus] = useState<WsStatus>('connecting');

  // Reset tree state whenever the consumer flips sessions. We use useState
  // (not useReducer) precisely so this reset is one line; a useReducer-based
  // version would need a synthetic 'reset' action threaded through every
  // dispatch site.
  useEffect(() => {
    setTree(emptyTree(sessionId));
  }, [sessionId]);

  const dispatch = useCallback(
    (envelope: DaemonEventEnvelope<DaemonEvent>) => {
      setTree((prev) => executionTreeReducer(prev, envelope));
    },
    [],
  );

  useEffect(() => {
    let ws: WebSocket | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let backoffMs = RECONNECT_INITIAL_MS;
    let cancelled = false;

    const connect = (): void => {
      if (cancelled) return;
      setStatus('connecting');
      ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        backoffMs = RECONNECT_INITIAL_MS;
        setStatus('open');
        // TODO(#432): once the snapshot endpoint exists, send
        // { method: "snapshot.subscribe", sessionId, lastEventId: tree.lastEventId }
        // here so the daemon replays only events the browser hasn't seen.
      };

      ws.onmessage = (e: MessageEvent<string>) => {
        const envelope = parseEnvelope(e.data);
        if (!envelope) return;
        // Cross-session filter: the bridge broadcasts every session's events
        // to every connected client. A multi-tenant browser tab on its own
        // session would otherwise mix two sessions' trees together.
        if (envelope.sessionId !== sessionId) return;
        dispatch(envelope);
      };

      ws.onerror = () => {
        setStatus('error');
      };

      ws.onclose = () => {
        if (cancelled) return;
        setStatus('reconnecting');
        // Exponential backoff capped at RECONNECT_MAX_MS — surveillance UIs
        // that leave a tab open overnight should not hammer a downed daemon.
        reconnectTimer = setTimeout(connect, backoffMs);
        backoffMs = Math.min(backoffMs * 2, RECONNECT_MAX_MS);
      };
    };

    connect();

    return () => {
      cancelled = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (ws) {
        ws.onclose = null;
        ws.onerror = null;
        ws.onmessage = null;
        ws.close();
      }
      setStatus('closed');
    };
  }, [wsUrl, sessionId, dispatch]);

  return { tree, status };
}

// ---------------------------------------------------------------------------
// Inbound validation — keep parsing concerns out of the reducer
// ---------------------------------------------------------------------------

/**
 * Parses a raw WS frame into a typed envelope, or returns null if the frame
 * is malformed, carries an event method outside {@link DaemonEvent}, or is
 * missing a critical field for its method (see {@link CRITICAL_FIELDS}).
 */
function parseEnvelope(raw: string): DaemonEventEnvelope<DaemonEvent> | null {
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!isPlainObject(data)) return null;
  const eventId = data['eventId'];
  const sessionId = data['sessionId'];
  const receivedAt = data['receivedAt'];
  const event = data['event'];
  if (
    typeof eventId !== 'number' ||
    typeof sessionId !== 'string' ||
    typeof receivedAt !== 'string' ||
    !isPlainObject(event)
  ) {
    return null;
  }
  const method = event['method'];
  const params = event['params'];
  if (typeof method !== 'string' || !isPlainObject(params)) return null;
  if (!KNOWN_METHODS.has(method as DaemonEvent['method'])) {
    // Forward-compat: unknown methods are reserved for Tier 2/3 expansion;
    // dropping them at the boundary keeps the reducer's switch type-safe.
    return null;
  }
  // Per-method critical-field check. Most methods don't have entries here
  // and pass through; the ones that do cannot recover from a missing field.
  const required = CRITICAL_FIELDS[method as DaemonEvent['method']];
  if (required && !required.every((k) => k in params)) {
    return null;
  }
  // Trust the schema once method + critical fields are validated. Cast goes
  // through `unknown` because `{method: string, params: Record<string, unknown>}`
  // doesn't structurally overlap with the discriminated DaemonEvent union.
  return {
    eventId,
    sessionId,
    receivedAt,
    event: { method, params } as unknown as DaemonEvent,
  };
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

// Re-export the unknown-event type for callers that want to inspect drops.
export type { UnknownDaemonEvent };
