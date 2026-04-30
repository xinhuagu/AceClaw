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
  ClientCommand,
  DaemonEvent,
  DaemonEventEnvelope,
  UnknownDaemonEvent,
} from '../types/events';
import { emptyTree, type ExecutionTree } from '../types/tree';
import {
  dismissPermissionPanel,
  executionTreeReducer,
  resolvePermissionLocally,
} from '../reducers/treeReducer';

/** Connection lifecycle states surfaced to the renderer. */
export type WsStatus =
  | 'connecting'
  | 'open'
  | 'reconnecting'
  | 'closed'
  | 'error';

/**
 * Disposition of a {@code sendCommand} call. Three terminal states:
 *
 *   - {@code 'sent'}    — written directly to an open socket.
 *   - {@code 'queued'}  — buffered for flush on the next {@code onopen}.
 *   - {@code 'dropped'} — pre-open buffer is full (cap {@link PENDING_SEND_CAP}).
 *     The caller should avoid running optimistic local updates so the
 *     daemon's eventual timeout/event remains the source of truth.
 */
export type SendCommandResult = 'sent' | 'queued' | 'dropped';

/** Shape returned to consumers — tree state plus a connection indicator. */
export interface UseExecutionTreeResult {
  tree: ExecutionTree;
  status: WsStatus;
  /**
   * Sends a {@link ClientCommand} (browser → daemon) over the WS. Used by
   * the PermissionPanel (#437) to post {@code permission.response}. If the
   * socket isn't open yet, the command is buffered and flushed on
   * {@code onopen}; reconnect re-sends nothing — pending permission
   * approvals time out anyway and the daemon will re-emit a fresh
   * {@code permission.request}. Returns the disposition of the command
   * so callers can avoid running optimistic local updates when the
   * command was dropped (e.g. don't strip awaitingInput just because we
   * couldn't notify the daemon).
   */
  sendCommand: (cmd: ClientCommand) => SendCommandResult;
  /**
   * Same as a reducer-driven {@code permission.response} broadcast: flips
   * the paused node out of awaiting-input state in the local tree. The
   * caller is expected to invoke {@link UseExecutionTreeResult.sendCommand}
   * separately so the daemon also learns of the decision — keeping the
   * two calls split avoids double-resolving when the user clicks twice
   * before the optimistic update lands.
   */
  resolvePermission: (requestId: string, approved: boolean) => void;
  /**
   * Tears down the permission panel for {@code requestId} after a
   * CLI-resolved request has shown its "Approved/Denied via CLI" state
   * long enough to register. Idempotent — a no-op when the request is
   * no longer pending.
   */
  dismissPermission: (requestId: string) => void;
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
 * Soft cap on the pre-open send buffer. Permission flows emit one
 * outbound command per click; a click-storm during reconnect that fills
 * 32 slots is already pathological and probably indicates the user is
 * unaware the WS is down — drop further commands and surface
 * {@code 'dropped'} to the caller so it can refrain from optimistic
 * local updates.
 */
const PENDING_SEND_CAP = 32;

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

  // Stable refs the connect effect can read without re-tearing the socket
  // when their identities change. wsRef is the live socket; pendingSendRef
  // is the outbound command queue we flush in onopen.
  const wsRef = useRef<WebSocket | null>(null);
  const pendingSendRef = useRef<ClientCommand[]>([]);

  const sendCommand = useCallback((cmd: ClientCommand): SendCommandResult => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(cmd));
      return 'sent';
    }
    // Buffer until onopen flushes. Two queue invariants:
    //   1. Per-requestId dedup for permission.response — if the user
    //      clicks Approve, then quickly Deny while the WS is reconnecting,
    //      both would otherwise queue and the daemon's first-response-wins
    //      would honour whichever arrived first; on a daemon without #433
    //      the second click never lands. Replace the prior entry instead.
    //   2. Hard cap at PENDING_SEND_CAP — beyond that, drop and signal
    //      back to the caller so it can avoid optimistic local updates.
    const queue = pendingSendRef.current;
    if (cmd.method === 'permission.response') {
      const dupIdx = queue.findIndex(
        (q) =>
          q.method === 'permission.response' &&
          q.params.requestId === cmd.params.requestId,
      );
      if (dupIdx >= 0) {
        queue[dupIdx] = cmd;
        return 'queued';
      }
    }
    if (queue.length >= PENDING_SEND_CAP) {
      return 'dropped';
    }
    queue.push(cmd);
    return 'queued';
  }, []);

  const resolvePermission = useCallback(
    (requestId: string, approved: boolean) => {
      setTree((prev) => resolvePermissionLocally(prev, requestId, approved));
    },
    [],
  );

  const dismissPermission = useCallback((requestId: string) => {
    setTree((prev) => dismissPermissionPanel(prev, requestId));
  }, []);

  useEffect(() => {
    let ws: WebSocket | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let backoffMs = RECONNECT_INITIAL_MS;
    let cancelled = false;
    // Snapshot handshake gate. While true, every live envelope arriving
    // on the socket is BUFFERED instead of dispatched — the reducer's
    // {@code lastEventId} watermark is the dedup gate against snapshot
    // replay, and a live event slipping in before the snapshot lands
    // would advance that watermark past every snapshot envelope (which
    // are necessarily older), so all snapshot replay would be deduped
    // and the tree would miss its structural roots. Once
    // {@code snapshot.response} is applied we flush the queue in
    // arrival order through the reducer (same dedup rules apply, so a
    // queued event whose eventId is <= the snapshot watermark is
    // correctly dropped — but events that ARE newer than the snapshot
    // get applied normally). Reset on every reconnect, since a fresh
    // handshake is needed for each new socket.
    let snapshotPending = false;
    let pendingLive: Array<DaemonEventEnvelope<DaemonEvent>> = [];

    const connect = (): void => {
      if (cancelled) return;
      setStatus('connecting');
      ws = new WebSocket(wsUrl);
      wsRef.current = ws;

      ws.onopen = () => {
        backoffMs = RECONNECT_INITIAL_MS;
        setStatus('open');
        // Reset handshake state for this fresh socket. Reconnect after
        // a drop runs through the same path: send snapshot.request, gate
        // live events until the response lands, flush queue, run live.
        snapshotPending = true;
        pendingLive = [];
        // Ask the daemon to replay every envelope it still holds for this
        // session (issue #432). Without this, a tab opened mid-execution
        // would only see events emitted AFTER it connected — root node,
        // first turn, and any in-flight tools would all be missing.
        ws?.send(
          JSON.stringify({ method: 'snapshot.request', params: { sessionId } }),
        );
        // Flush any commands the user queued before the socket opened
        // (e.g. clicked Approve while the tab was reconnecting). Drained
        // in arrival order — the daemon's first-response-wins (#433)
        // makes back-to-back duplicates safe.
        if (pendingSendRef.current.length > 0) {
          const queued = pendingSendRef.current;
          pendingSendRef.current = [];
          for (const cmd of queued) {
            ws?.send(JSON.stringify(cmd));
          }
        }
      };

      ws.onmessage = (e: MessageEvent<string>) => {
        // snapshot.response is non-enveloped (point-to-point reply, like
        // sessions.list.result from #445). Detect and replay before falling
        // through to the live-envelope path.
        let data: unknown;
        try {
          data = JSON.parse(e.data);
        } catch {
          return;
        }
        if (
          isPlainObject(data) &&
          data['method'] === 'snapshot.response' &&
          data['sessionId'] === sessionId &&
          Array.isArray(data['events'])
        ) {
          for (const env of data['events']) {
            const parsed = parseEnvelopeFromObject(env);
            if (!parsed) continue;
            if (parsed.sessionId !== sessionId) continue;
            if (parsed.kind === 'unknown') {
              setTree((prev) =>
                prev.lastEventId >= parsed.eventId
                  ? prev
                  : { ...prev, lastEventId: parsed.eventId },
              );
              continue;
            }
            dispatch(parsed.envelope);
          }
          // Flush every live envelope that arrived during the snapshot
          // wait. Order is preserved (it's just the receive order off the
          // socket), and the reducer's dedup gate handles overlap with
          // the snapshot — events whose eventId fell into the snapshot's
          // range are skipped, the rest apply.
          const queued = pendingLive;
          pendingLive = [];
          snapshotPending = false;
          for (const envelope of queued) {
            dispatch(envelope);
          }
          return;
        }

        const result = parseEnvelopeFromObject(data);
        if (!result) return;
        // Cross-session filter: the bridge broadcasts every session's events
        // to every connected client. A multi-tenant browser tab on its own
        // session would otherwise mix two sessions' trees together.
        if (result.sessionId !== sessionId) return;
        if (result.kind === 'unknown') {
          // Forward-compat (Tier 2/3) methods bypass the reducer but still
          // bump the watermark — without this, #432's snapshot replay would
          // re-deliver them indefinitely whenever they're the newest events
          // the browser saw. During the snapshot handshake we deliberately
          // skip the watermark advance (and skip the event) — the snapshot
          // path will set lastEventId via its own envelope replay, and an
          // unknown live event arriving during the wait shouldn't poison
          // that watermark.
          if (snapshotPending) return;
          setTree((prev) =>
            prev.lastEventId >= result.eventId
              ? prev
              : { ...prev, lastEventId: result.eventId },
          );
          return;
        }
        // GATE: hold live envelopes until snapshot replay lands, then
        // flush them in order. Without this, a live event arriving before
        // snapshot.response would advance lastEventId past every snapshot
        // envelope and dedup the whole replay.
        if (snapshotPending) {
          pendingLive.push(result.envelope);
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
      wsRef.current = null;
      setStatus('closed');
    };
  }, [wsUrl, sessionId, dispatch]);

  return { tree, status, sendCommand, resolvePermission, dismissPermission };
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
  return parseEnvelopeFromObject(data);
}

/**
 * Same shape and validation as {@link parseEnvelope}, but takes an
 * already-parsed JSON value. Used by {@code snapshot.response} replay so
 * each event in the array runs through the exact same validation gate as
 * a live frame, without paying for a JSON round-trip per event.
 */
export function parseEnvelopeFromObject(data: unknown): ParseResult {
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
