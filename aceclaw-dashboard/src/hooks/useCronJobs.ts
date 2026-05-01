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

/** Discriminator for {@link CronEventRecord}. */
export type CronEventKind = 'triggered' | 'completed' | 'failed' | 'skipped';

/**
 * One scheduler-event record in the timeline buffer. Sourced from two
 * places that share the same daemon-side typing:
 *   - Backfill on connect via {@code scheduler.events.poll.result}
 *   - Live deltas via {@code scheduler.job_*} envelopes
 *
 * Optional fields are present only for the kinds that carry them — the
 * union is wide so the timeline component can render every variant
 * without per-case schema gymnastics.
 */
export interface CronEventRecord {
  jobId: string;
  kind: CronEventKind;
  /** ISO-8601 daemon timestamp — used to position the block on the time axis. */
  timestamp: string;
  /** completed only. */
  durationMs?: number;
  /** triggered only. */
  cronExpression?: string;
  /** failed only. */
  error?: string;
  /** failed only — current attempt number. */
  attempt?: number;
  /** failed only — retry budget. */
  maxAttempts?: number;
  /** skipped only. */
  reason?: string;
  /** completed only — daemon-summarised LLM output. */
  summary?: string;
}

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
/**
 * On connect we ask for the most recent N events so the timeline isn't
 * blank for the first minute. 100 covers ~1.5h of a per-minute job
 * (typical) and ~1.7 days of an hourly job — enough to show meaningful
 * history without bloating the wire payload.
 */
const EVENTS_BACKFILL_REQUEST = JSON.stringify({
  method: 'scheduler.events.poll',
  params: { afterSeq: 0, limit: 100 },
});
/**
 * In-memory cap for the rolling event buffer. The timeline only
 * renders events within its visible time window (default 1h), so this
 * mostly bounds memory after a long-running tab — 200 is generous
 * relative to the daemon-side ring's 256.
 */
const RECENT_EVENTS_CAP = 200;
const RECONNECT_INITIAL_MS = 500;
const RECONNECT_MAX_MS = 30_000;

/** Combined return shape: the sidebar's job list AND the timeline's event window. */
export interface UseCronJobsResult {
  jobs: CronJobInfo[];
  /** Rolling buffer of recent {@code scheduler.job_*} events, oldest → newest. */
  recentEvents: CronEventRecord[];
}

/**
 * Subscribes to the daemon's cron-job set AND the recent-event stream.
 * Returns both because layer 3's timeline widget shares this hook's
 * connection rather than opening its own — see #462 for the broader
 * shared-connection plan.
 *
 * @param wsUrl daemon bridge URL — same as {@link useExecutionTree}.
 *              Pass {@code null} to disable the connection.
 */
export function useCronJobs(wsUrl: string | null): UseCronJobsResult {
  const [jobs, setJobs] = useState<CronJobInfo[]>([]);
  const [recentEvents, setRecentEvents] = useState<CronEventRecord[]>([]);

  useEffect(() => {
    if (!wsUrl) {
      // Drop any rows from a prior connection so the sidebar doesn't
      // ghost stale jobs while the dashboard is detached.
      setJobs([]);
      setRecentEvents([]);
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
        // showing stale lastRunAt values + a stale event window.
        ws?.send(STATUS_REQUEST);
        ws?.send(EVENTS_BACKFILL_REQUEST);
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

        // Point-to-point reply to our EVENTS_BACKFILL_REQUEST. Replace
        // the entire recentEvents buffer with the daemon's snapshot.
        // A tiny race window exists where a live event arrives between
        // our request and the reply — that event would already be in
        // recentEvents and would get clobbered here. Acceptable: the
        // next live event will re-populate, and the timeline doesn't
        // visually distinguish "happened 1.0s ago" from "happened 1.1s
        // ago" anyway.
        if (data['method'] === 'scheduler.events.poll.result') {
          const next = parseEventsPollResult(data);
          if (next) setRecentEvents(next);
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

        // Append to the rolling event buffer for the timeline. This
        // path runs alongside the sidebar's setJobs below.
        const eventRecord = recordFromLiveEvent(method, jobId, params);
        if (eventRecord) {
          setRecentEvents((prev) => appendCapped(prev, eventRecord, RECENT_EVENTS_CAP));
        }

        setJobs((prev) => {
          if (!hasJob(prev, jobId)) {
            // Job was created after our snapshot — applyEventDelta would
            // silently drop the event. Re-request the status so the new
            // row appears (the snapshot reply will replace this state
            // wholesale; returning `prev` here just keeps the UI stable
            // for the few ms of round-trip). The send is idempotent so
            // React's strict-mode double-invocation is harmless.
            try {
              ws?.send(STATUS_REQUEST);
            } catch {
              // Socket transitioning — next reconnect's onopen will
              // re-fetch anyway.
            }
            return prev;
          }
          return applyEventDelta(prev, method, jobId, params);
        });
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

  return { jobs, recentEvents };
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
 * Parses a {@code scheduler.events.poll.result} payload into the
 * timeline's event-record shape. Returns null when the wire shape is
 * unusable. Daemon-side type discriminator (`type`) is mapped to the
 * dashboard's {@link CronEventKind}; events with unknown types are
 * dropped silently so a future daemon adding a 5th kind doesn't crash
 * older dashboards.
 *
 * Exported for unit tests so the wire-format mapping is pinned.
 */
export function parseEventsPollResult(
  data: Record<string, unknown>,
): CronEventRecord[] | null {
  const events = data['events'];
  if (!Array.isArray(events)) return null;
  const out: CronEventRecord[] = [];
  for (const e of events) {
    if (!isPlainObject(e)) continue;
    if (!isString(e['jobId']) || !isString(e['type']) || !isString(e['timestamp'])) continue;
    const kind = parseEventKind(e['type']);
    if (!kind) continue;
    const rec: CronEventRecord = {
      jobId: e['jobId'],
      kind,
      timestamp: e['timestamp'],
    };
    if (typeof e['durationMs'] === 'number') rec.durationMs = e['durationMs'];
    if (isString(e['cronExpression'])) rec.cronExpression = e['cronExpression'];
    if (isString(e['error'])) rec.error = e['error'];
    if (typeof e['attempt'] === 'number') rec.attempt = e['attempt'];
    if (typeof e['maxAttempts'] === 'number') rec.maxAttempts = e['maxAttempts'];
    if (isString(e['reason'])) rec.reason = e['reason'];
    if (isString(e['summary'])) rec.summary = e['summary'];
    out.push(rec);
  }
  return out;
}

function parseEventKind(t: string): CronEventKind | null {
  switch (t) {
    case 'triggered':
    case 'completed':
    case 'failed':
    case 'skipped':
      return t;
    default:
      return null;
  }
}

/**
 * Builds a {@link CronEventRecord} from a live {@code scheduler.job_*}
 * envelope. Returns null when the method is unknown or critical fields
 * are missing — same defensive shape as {@link parseEventsPollResult}.
 *
 * Exported for unit tests.
 */
export function recordFromLiveEvent(
  method: string,
  jobId: string,
  params: Record<string, unknown>,
): CronEventRecord | null {
  const timestamp = isString(params['timestamp']) ? params['timestamp'] : null;
  if (!timestamp) return null;
  const kind = liveMethodToKind(method);
  if (!kind) return null;
  const rec: CronEventRecord = { jobId, kind, timestamp };
  if (typeof params['durationMs'] === 'number') rec.durationMs = params['durationMs'];
  if (isString(params['cronExpression'])) rec.cronExpression = params['cronExpression'];
  if (isString(params['error'])) rec.error = params['error'];
  if (typeof params['attempt'] === 'number') rec.attempt = params['attempt'];
  if (typeof params['maxAttempts'] === 'number') rec.maxAttempts = params['maxAttempts'];
  if (isString(params['reason'])) rec.reason = params['reason'];
  if (isString(params['summary'])) rec.summary = params['summary'];
  return rec;
}

function liveMethodToKind(method: string): CronEventKind | null {
  switch (method) {
    case 'scheduler.job_triggered':
      return 'triggered';
    case 'scheduler.job_completed':
      return 'completed';
    case 'scheduler.job_failed':
      return 'failed';
    case 'scheduler.job_skipped':
      return 'skipped';
    default:
      return null;
  }
}

/**
 * Appends {@code item} to {@code prev}, dropping the oldest entries if
 * the result would exceed {@code cap}. Pure / immutable so React state
 * updates stay safe; exported so a unit test can pin the cap behavior.
 */
export function appendCapped<T>(prev: T[], item: T, cap: number): T[] {
  const next = prev.length + 1 > cap ? prev.slice(prev.length + 1 - cap) : prev.slice();
  next.push(item);
  return next;
}

/**
 * True when {@code prev} contains a job with the given id. Exposed
 * separately from {@link applyEventDelta} so the hook can fire a
 * snapshot refresh BEFORE applying the delta when the event references
 * a job created after our last snapshot — without that refresh the
 * sidebar would silently drop events for newly-created jobs.
 */
export function hasJob(prev: CronJobInfo[], jobId: string): boolean {
  return prev.some((j) => j.id === jobId);
}

/**
 * Folds a {@code scheduler.job_*} event into the existing job list. If
 * the event references a job we don't know about (e.g. a job created
 * after our snapshot), the call is a no-op — the caller should fire
 * a status refresh, see {@link hasJob}.
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
