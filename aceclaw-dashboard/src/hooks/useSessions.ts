/**
 * useSessions (issue #445).
 *
 * Owns its OWN WebSocket connection (separate from {@link useExecutionTree})
 * to drive the SessionList sidebar. Two responsibilities:
 *
 *   1. On open, send the daemon a {@code sessions.list} request and seed the
 *      list from the one-shot reply (snapshot of state at request time).
 *   2. Listen for {@code stream.session_started} / {@code stream.session_ended}
 *      envelopes broadcast on the same socket and apply them as live deltas.
 *
 * Why a second WS connection rather than sharing the tree's? The tree hook
 * filters envelopes by sessionId, so it can't observe other-session events.
 * Splitting keeps each hook's contract narrow (one tree per session, one
 * sidebar per dashboard) and keeps the wire format unchanged. Two
 * localhost connections are essentially free.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
/** One row in the sidebar — what the daemon's sessions.list reply carries. */
export interface SessionInfo {
  sessionId: string;
  projectPath: string;
  /** ISO-8601 timestamp; daemon-assigned. */
  createdAt: string;
  active: boolean;
  /**
   * Effective model for the session (override or daemon default). Optional
   * for forward/backward compat with daemons that don't yet emit it.
   */
  model?: string;
}

const SESSIONS_LIST_REQUEST = JSON.stringify({ method: 'sessions.list' });
const RECONNECT_INITIAL_MS = 500;
const RECONNECT_MAX_MS = 30_000;

/**
 * Subscribes to the daemon's session set. Returns a stable array sorted by
 * createdAt descending so the most recently started session appears first.
 *
 * Reconnect: mirrors {@link useExecutionTree}'s exponential-backoff pattern
 * so a daemon restart (the most common trigger in dev) doesn't leave the
 * sidebar showing stale entries forever. Every successful reopen re-sends
 * {@code sessions.list} so the snapshot is freshly authoritative.
 *
 * @param wsUrl daemon bridge URL — same as used by {@link useExecutionTree}.
 *              Pass {@code null} to disable the connection (e.g. while the
 *              user is still on the session-prompt screen).
 */
export function useSessions(wsUrl: string | null): SessionInfo[] {
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  // Stable handler so the useEffect dep array doesn't churn.
  const apply = useCallback((next: SessionInfo[]) => setSessions(next), []);

  useEffect(() => {
    if (!wsUrl) return;
    let ws: WebSocket | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let backoffMs = RECONNECT_INITIAL_MS;
    let cancelled = false;

    const connect = (): void => {
      if (cancelled) return;
      try {
        ws = new WebSocket(wsUrl);
      } catch {
        // Malformed URL — caller should sanitise before passing in. No
        // retry: the URL won't fix itself.
        return;
      }

      ws.onopen = () => {
        if (cancelled) return;
        backoffMs = RECONNECT_INITIAL_MS;
        // Re-request the snapshot on every reopen so the sidebar resyncs
        // after a daemon restart instead of trusting stale entries.
        ws?.send(SESSIONS_LIST_REQUEST);
      };

      ws.onmessage = (e: MessageEvent<string>) => {
        if (cancelled) return;
        let data: unknown;
        try {
          data = JSON.parse(e.data);
        } catch {
          return;
        }
        if (!isPlainObject(data)) return;

        // sessions.list.result — non-enveloped one-shot reply. Snapshot of
        // active sessions at the moment we asked.
        if (data['method'] === 'sessions.list.result') {
          const list = parseSessionsListResult(data);
          if (list) apply(sortByCreatedDesc(list));
          return;
        }

        // Otherwise it's a regular envelope; pluck the session_started /
        // session_ended events and ignore everything else (the tree hook
        // handles its own dispatch). Validate the shape ourselves rather
        // than reusing useExecutionTree's parseEnvelope — we need the wider
        // event view (no critical-field gate) so a missing optional doesn't
        // drop the whole envelope.
        const event = data['event'];
        if (!isPlainObject(event)) return;
        const method = typeof event['method'] === 'string' ? event['method'] : null;
        const rawParams = event['params'];
        const params: Record<string, unknown> = isPlainObject(rawParams) ? rawParams : {};
        if (method === 'stream.session_started') {
          const sessionId = typeof params['sessionId'] === 'string' ? params['sessionId'] : null;
          if (!sessionId) return;
          // The session_started event doesn't carry projectPath, so we fall
          // back to "(unknown)" until the next sessions.list refresh — the
          // sidebar will replace it on its next mount/reconnect.
          const created =
            typeof params['timestamp'] === 'string'
              ? params['timestamp']
              : new Date().toISOString();
          setSessions((prev) =>
            prev.some((s) => s.sessionId === sessionId)
              ? prev
              : sortByCreatedDesc([
                  ...prev,
                  {
                    sessionId,
                    projectPath: '(unknown)',
                    createdAt: created,
                    active: true,
                  },
                ]),
          );
        } else if (method === 'stream.session_ended') {
          const sessionId = typeof params['sessionId'] === 'string' ? params['sessionId'] : null;
          if (!sessionId) return;
          setSessions((prev) =>
            prev.map((s) => (s.sessionId === sessionId ? { ...s, active: false } : s)),
          );
        }
      };

      ws.onclose = () => {
        if (cancelled) return;
        // Schedule a reconnect with exponential backoff capped at
        // RECONNECT_MAX_MS so a long-down daemon doesn't get hammered.
        reconnectTimer = setTimeout(connect, backoffMs);
        backoffMs = Math.min(backoffMs * 2, RECONNECT_MAX_MS);
      };

      ws.onerror = () => {
        // onerror always precedes onclose for hard failures; the close
        // handler does the reconnect, so no work needed here.
      };
    };

    connect();

    return () => {
      cancelled = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (ws) {
        ws.onopen = null;
        ws.onmessage = null;
        ws.onclose = null;
        ws.onerror = null;
        ws.close();
      }
    };
  }, [wsUrl, apply]);

  return sessions;
}

// ---------------------------------------------------------------------------
// Validation helpers
// ---------------------------------------------------------------------------

/**
 * Validates the daemon's sessions.list.result reply and narrows it to a
 * typed array. Tolerates extra fields (forward-compat) but drops any entry
 * missing a critical key (sessionId, projectPath, …) — bad rows get
 * silently filtered rather than crashing the sidebar.
 *
 * Exported for unit testing.
 */
export function parseSessionsListResult(
  data: Record<string, unknown>,
): SessionInfo[] | null {
  const raw = data['sessions'];
  if (!Array.isArray(raw)) return null;
  const out: SessionInfo[] = [];
  for (const entry of raw) {
    if (!isPlainObject(entry)) continue;
    const sessionId = entry['sessionId'];
    const projectPath = entry['projectPath'];
    const createdAt = entry['createdAt'];
    const active = entry['active'];
    const model = entry['model'];
    if (
      typeof sessionId !== 'string' ||
      typeof projectPath !== 'string' ||
      typeof createdAt !== 'string' ||
      typeof active !== 'boolean'
    ) {
      continue;
    }
    // model is optional — drop it silently if it's the wrong type rather
    // than failing the whole row. exactOptionalPropertyTypes forbids
    // assigning `undefined`, so spread it conditionally.
    out.push({
      sessionId,
      projectPath,
      createdAt,
      active,
      ...(typeof model === 'string' ? { model } : {}),
    });
  }
  return out;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function sortByCreatedDesc(list: SessionInfo[]): SessionInfo[] {
  return [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}
