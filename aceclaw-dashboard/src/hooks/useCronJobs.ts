/**
 * useCronJobs — sidebar data source for the cron-jobs panel (#459 layer 2).
 *
 * Owns its OWN WebSocket connection (separate from
 * {@link useExecutionTree} and {@link useSessions}) for the same reason
 * sidebars do: a per-session reducer can't observe daemon-wide events.
 * Three responsibilities:
 *
 *   1. On open, request {@code scheduler.cron.status} and seed the job
 *      list from the one-shot reply (snapshot of state at request time).
 *   2. Listen for live {@code scheduler.job_triggered/completed/failed/skipped}
 *      envelopes — those carry the {@code __global__} sentinel sessionId
 *      so {@link useExecutionTree} naturally ignores them. We apply each
 *      as a delta so the list is fresh without re-fetching.
 *   3. Reconnect with exponential backoff, mirroring {@link useSessions}.
 *
 * The wire shape from the daemon ({@code scheduler.cron.status.result}
 * and {@code scheduler.job_*}) lives in
 * {@code aceclaw-daemon/.../AceClawDaemon.java}.
 */

import { useEffect, useState } from 'react';

/** One row in the cron-jobs sidebar. Mirrors the daemon's reply shape. */
export interface CronJobInfo {
  id: string;
  name: string;
  /** Five-field cron expression. */
  expression: string;
  enabled: boolean;
  /** Best-effort kind hint from the daemon: "scheduled", "one-shot", … */
  kind?: string;
  description?: string;
  /** ISO-8601 of the last run start, if any. */
  lastRunAt?: string;
  /** ISO-8601 of the next computed fire time, if the expression is valid. */
  nextFireAt?: string;
  /** Last error message — present only when the most recent run failed. */
  lastError?: string;
  /**
   * Synthesised by the hook from the most recent {@code scheduler.job_*}
   * envelope so the sidebar can render a status dot without re-fetching:
   *   - {@code "running"}  while a triggered run hasn't completed
   *   - {@code "completed"} after a clean run
   *   - {@code "failed"}    after a failure (also see {@link lastError})
   *   - {@code "skipped"}   when the daemon skipped a fire (e.g. previous run busy)
   *   - {@code undefined}   when no event has been observed since connect
   */
  lastStatus?: 'running' | 'completed' | 'failed' | 'skipped';
}

const STATUS_REQUEST = JSON.stringify({ method: 'scheduler.cron.status' });
const RECONNECT_INITIAL_MS = 500;
const RECONNECT_MAX_MS = 30_000;

/**
 * Subscribes to the daemon's cron-job set. Returns a stable array sorted
 * by job id (matching the daemon's wire order) so React's keyed children
 * stay stable across re-renders.
 *
 * @param wsUrl daemon bridge URL — same as {@link useExecutionTree}.
 *              Pass {@code null} to disable the connection.
 */
export function useCronJobs(wsUrl: string | null): CronJobInfo[] {
  const [jobs, setJobs] = useState<CronJobInfo[]>([]);

  useEffect(() => {
    if (!wsUrl) {
      // Drop any rows from a prior connection so the sidebar doesn't
      // ghost stale jobs while the dashboard is detached.
      setJobs([]);
      return;
    }
    let ws: WebSocket | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let backoffMs = RECONNECT_INITIAL_MS;
    let cancelled = false;

    const connect = (): void => {
      if (cancelled) return;
      try {
        ws = new WebSocket(wsUrl);
      } catch {
        return;
      }

      ws.onopen = () => {
        if (cancelled) return;
        backoffMs = RECONNECT_INITIAL_MS;
        // Refresh on every reopen so a daemon restart doesn't leave us
        // showing stale lastRunAt values.
        ws?.send(STATUS_REQUEST);
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

        // Point-to-point reply to our STATUS_REQUEST. Snapshot of the
        // current job set; replace local state.
        if (data['method'] === 'scheduler.cron.status.result') {
          const next = parseStatusResult(data);
          if (next) setJobs(next);
          return;
        }

        // Live envelope. Filter by the GLOBAL_SESSION_ID sentinel so we
        // don't accidentally pick up stream.* events from any session.
        if (data['sessionId'] !== '__global__') return;
        const event = data['event'];
        if (!isPlainObject(event)) return;
        const method = typeof event['method'] === 'string' ? event['method'] : null;
        if (!method || !method.startsWith('scheduler.job_')) return;
        const rawParams = event['params'];
        const params: Record<string, unknown> = isPlainObject(rawParams) ? rawParams : {};
        const jobId = typeof params['jobId'] === 'string' ? params['jobId'] : null;
        if (!jobId) return;

        setJobs((prev) => applyEventDelta(prev, method, jobId, params));
      };

      ws.onclose = () => {
        if (cancelled) return;
        reconnectTimer = setTimeout(connect, backoffMs);
        backoffMs = Math.min(backoffMs * 2, RECONNECT_MAX_MS);
      };

      ws.onerror = () => {
        // onerror always precedes onclose for hard failures; the close
        // handler reconnects, so no work needed here.
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
  }, [wsUrl]);

  return jobs;
}

// ---------------------------------------------------------------------------
// Validation + delta helpers (exported for unit tests)
// ---------------------------------------------------------------------------

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isString(v: unknown): v is string {
  return typeof v === 'string';
}

/**
 * Parses a {@code scheduler.cron.status.result} payload. Returns null when
 * the shape is unusable (so the hook falls back to whatever it had).
 *
 * Exported so unit tests can pin the wire-format contract directly.
 */
export function parseStatusResult(data: Record<string, unknown>): CronJobInfo[] | null {
  const jobs = data['jobs'];
  if (!Array.isArray(jobs)) return null;
  const out: CronJobInfo[] = [];
  for (const j of jobs) {
    if (!isPlainObject(j)) continue;
    if (!isString(j['id']) || !isString(j['name']) || !isString(j['expression'])) continue;
    const row: CronJobInfo = {
      id: j['id'],
      name: j['name'],
      expression: j['expression'],
      enabled: typeof j['enabled'] === 'boolean' ? j['enabled'] : true,
    };
    if (isString(j['kind'])) row.kind = j['kind'];
    if (isString(j['description'])) row.description = j['description'];
    if (isString(j['lastRunAt'])) row.lastRunAt = j['lastRunAt'];
    if (isString(j['nextFireAt'])) row.nextFireAt = j['nextFireAt'];
    if (isString(j['lastError'])) row.lastError = j['lastError'];
    out.push(row);
  }
  return out;
}

/**
 * Folds a {@code scheduler.job_*} event into the existing job list. If
 * the event references a job we don't know about (e.g. a job created
 * after our snapshot), the call is a no-op — the next reconnect /
 * status refresh will pick it up.
 *
 * Exported for unit tests so the four event types are pinned by name.
 */
export function applyEventDelta(
  prev: CronJobInfo[],
  method: string,
  jobId: string,
  params: Record<string, unknown>,
): CronJobInfo[] {
  const ts = isString(params['timestamp']) ? params['timestamp'] : null;
  return prev.map((j) => {
    if (j.id !== jobId) return j;
    switch (method) {
      case 'scheduler.job_triggered': {
        const next: CronJobInfo = { ...j, lastStatus: 'running' };
        if (ts) next.lastRunAt = ts;
        delete next.lastError;
        return next;
      }
      case 'scheduler.job_completed': {
        const next: CronJobInfo = { ...j, lastStatus: 'completed' };
        if (ts) next.lastRunAt = ts;
        delete next.lastError;
        return next;
      }
      case 'scheduler.job_failed': {
        const next: CronJobInfo = { ...j, lastStatus: 'failed' };
        if (ts) next.lastRunAt = ts;
        if (isString(params['error'])) next.lastError = params['error'];
        return next;
      }
      case 'scheduler.job_skipped':
        return { ...j, lastStatus: 'skipped' };
      default:
        return j;
    }
  });
}
