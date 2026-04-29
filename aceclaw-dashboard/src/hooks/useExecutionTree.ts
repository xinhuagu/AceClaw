/**
 * useExecutionTree (issue #435).
 *
 * Owns one WebSocket to the daemon's bridge (#431) and feeds every inbound
 * {@link DaemonEventEnvelope} to {@link executionTreeReducer}. The hook is
 * responsible for transport concerns the reducer must stay pure of:
 *
 * - JSON parsing of WS frames.
 * - Envelope shape validation.
 * - Method allow-listing — unknown methods (Tier 2/3 forward-compat) are
 *   logged at debug level and dropped, so the reducer's switch can stay
 *   exhaustive in the type system.
 * - Reconnect with exponential backoff. The snapshot+replay handshake (#432)
 *   plugs in here once that endpoint exists; the watermark is already tracked
 *   on {@code state.lastEventId} so the reconnect path can send it as-is.
 */

import { useEffect, useReducer, useRef } from 'react';
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

const RECONNECT_INITIAL_MS = 500;
const RECONNECT_MAX_MS = 30_000;

/**
 * Subscribes to the daemon's WebSocket bridge and exposes the current
 * execution tree. Unmounting the consuming component closes the socket and
 * stops any pending reconnect timer.
 *
 * @param wsUrl  e.g. {@code "ws://localhost:3141/ws"} — daemon-bound, never null.
 * @param sessionId session this tree belongs to. Multi-session demuxing is
 *                  caller's concern: spin up one hook per visible session.
 */
export function useExecutionTree(
  wsUrl: string,
  sessionId: string,
): UseExecutionTreeResult {
  const [tree, dispatch] = useReducer(executionTreeReducer, sessionId, emptyTree);
  const statusRef = useRef<WsStatus>('connecting');
  const [, forceRender] = useReducer((x: number) => x + 1, 0);

  const setStatus = (s: WsStatus): void => {
    statusRef.current = s;
    forceRender();
  };

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
        // { method: "snapshot.subscribe", lastEventId: tree.lastEventId }
        // here so the daemon replays only events the browser hasn't seen.
      };

      ws.onmessage = (e: MessageEvent<string>) => {
        const envelope = parseEnvelope(e.data);
        if (envelope) dispatch(envelope);
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
    // dispatch is stable (useReducer); wsUrl is the only real dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wsUrl]);

  return { tree, status: statusRef.current };
}

// ---------------------------------------------------------------------------
// Inbound validation — keep parsing concerns out of the reducer
// ---------------------------------------------------------------------------

/**
 * Parses a raw WS frame into a typed envelope, or returns null if the frame
 * is malformed or carries an event method outside {@link DaemonEvent}. We
 * accept the wider {@link UnknownDaemonEvent} shape here only to prove that
 * known-method narrowing is sound at the boundary; the reducer never sees
 * the unknown branch.
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
    // Forward-compat: log once per unknown method bucket, then drop.
    return null;
  }
  // Trust the schema once method is in the allow-list. The reducer's switch
  // narrows further on `event.method` and pulls fields off `event.params`
  // without per-case runtime checks. Cast goes through `unknown` because
  // `{method: string, params: Record<string, unknown>}` doesn't structurally
  // overlap with the discriminated DaemonEvent union — TypeScript would
  // otherwise reject the direct widening.
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
