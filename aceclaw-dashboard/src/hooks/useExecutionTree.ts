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

import { useCallback, useEffect, useRef, useState } from 'react';
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
  'stream.session_ended',
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
 * Per-method type-guard validators. Presence-only checks let payloads like
 * {@code stream.plan_created} with {@code steps: {}} slip through to
 * {@code addPlanSkeleton}, which then crashes on {@code params.steps.map(...)}
 * — one bad bridge frame would take down the whole hook. These validators
 * assert actual shapes rather than mere key existence.
 *
 * Not exhaustive on purpose — only events whose handlers can't recover from
 * a malformed param shape need a guard. Events without an entry pass through
 * once they're inside {@link KNOWN_METHODS}.
 */
type ParamGuard = (p: Record<string, unknown>) => boolean;

const isString = (v: unknown): v is string => typeof v === 'string';
const isNumber = (v: unknown): v is number => typeof v === 'number';

const VALIDATORS: Partial<Record<DaemonEvent['method'], ParamGuard>> = {
  'permission.request': (p) =>
    isString(p['requestId']) && isString(p['description']) && isString(p['tool']),
  'stream.tool_use': (p) => isString(p['id']) && isString(p['name']),
  'stream.tool_completed': (p) =>
    isString(p['id']) && isString(p['name']) && typeof p['durationMs'] === 'number',
  'stream.turn_started': (p) => isString(p['requestId']) && isNumber(p['turnNumber']),
  'stream.turn_completed': (p) =>
    isString(p['requestId']) && isNumber(p['turnNumber']) && isNumber(p['durationMs']),
  'stream.session_started': (p) => isString(p['sessionId']) && isString(p['model']),
  'stream.session_ended': (p) =>
    isString(p['sessionId']) && isString(p['timestamp']) && isString(p['reason']),
  // Critical: addPlanSkeleton calls steps.map — must be Array, not just present.
  'stream.plan_created': (p) =>
    isString(p['planId']) && Array.isArray(p['steps']),
  'stream.plan_step_started': (p) =>
    isString(p['planId']) && isNumber(p['stepIndex']) && isString(p['stepName']),
  'stream.plan_step_completed': (p) =>
    isString(p['planId']) &&
    isNumber(p['stepIndex']) &&
    typeof p['success'] === 'boolean',
  'stream.subagent.start': (p) => isString(p['agentType']),
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
  // Tracks the sessionId the current tree was initialised under so the reset
  // effect below only fires on actual sessionId transitions. Without this
  // guard, mount runs the lazy useState initializer AND the effect's
  // setTree(...) — costing an extra render per consumer mount.
  const prevSessionIdRef = useRef(sessionId);

  // Reset tree state whenever the consumer flips sessions. We use useState
  // (not useReducer) precisely so this reset is one line; a useReducer-based
  // version would need a synthetic 'reset' action threaded through every
  // dispatch site.
  useEffect(() => {
    if (prevSessionIdRef.current !== sessionId) {
      setTree(emptyTree(sessionId));
      prevSessionIdRef.current = sessionId;
    }
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
        const result = parseEnvelope(e.data);
        if (!result) return;
        // Cross-session filter: the bridge broadcasts every session's events
        // to every connected client. A multi-tenant browser tab on its own
        // session would otherwise mix two sessions' trees together.
        if (result.sessionId !== sessionId) return;
        if (result.kind === 'unknown') {
          // Forward-compat (Tier 2/3) methods bypass the reducer but still
          // bump the watermark — without this, #432's snapshot replay would
          // re-deliver them indefinitely whenever they're the newest events
          // the browser saw.
          setTree((prev) =>
            prev.lastEventId >= result.eventId
              ? prev
              : { ...prev, lastEventId: result.eventId },
          );
          return;
        }
        dispatch(result.envelope);
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
 * Discriminated parse result. {@code known} envelopes flow through the
 * reducer; {@code unknown} ones bypass it but still need to advance the
 * watermark so reconnect/replay (#432) doesn't re-deliver them forever.
 * {@code null} means malformed or invalid for its method — drop entirely.
 */
export type ParseResult =
  | {
      kind: 'known';
      eventId: number;
      sessionId: string;
      envelope: DaemonEventEnvelope<DaemonEvent>;
    }
  | { kind: 'unknown'; eventId: number; sessionId: string }
  | null;

/**
 * Parses a raw WS frame into a discriminated {@link ParseResult}.
 *
 * - Returns {@code null} for any malformed top-level shape (bad JSON,
 *   missing eventId / sessionId / event / method / params), and for any
 *   known method whose params don't pass the per-method type guard
 *   (see {@link VALIDATORS}).
 * - Returns {@code kind: 'unknown'} for methods outside {@link KNOWN_METHODS} —
 *   the caller is expected to bump the watermark and skip dispatch.
 * - Returns {@code kind: 'known'} for everything else, with the envelope
 *   narrowed to {@link DaemonEvent}.
 *
 * Exported so unit tests can exercise the boundary without spinning up a
 * mock WebSocket.
 */
export function parseEnvelope(raw: string): ParseResult {
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
    // Forward-compat (Tier 2/3): the caller bumps the watermark before
    // returning to keep the dedup gate accurate across reconnects.
    return { kind: 'unknown', eventId, sessionId };
  }

  const guard = VALIDATORS[method as DaemonEvent['method']];
  if (guard && !guard(params)) {
    // Malformed payload that would crash a downstream handler — drop the
    // event entirely. Watermark NOT advanced so the daemon can retry.
    return null;
  }

  // Trust the schema once method + per-method type guard pass. Cast goes
  // through `unknown` because {method: string, params: Record<string, unknown>}
  // doesn't structurally overlap with the discriminated DaemonEvent union.
  return {
    kind: 'known',
    eventId,
    sessionId,
    envelope: {
      eventId,
      sessionId,
      receivedAt,
      event: { method, params } as unknown as DaemonEvent,
    },
  };
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

// Re-export the unknown-event type for callers that want to inspect drops.
export type { UnknownDaemonEvent };
