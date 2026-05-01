/**
 * Unit tests for useCronJobs's pure helpers (#459 layer 2).
 *
 * The hook itself wires WebSocket plumbing — we test it via the helpers
 * (parseStatusResult, applyEventDelta) so wire-format and delta logic
 * have a fast safety net independent of a live socket.
 */

import { describe, expect, it } from 'vitest';
import {
  appendCapped,
  applyEventDelta,
  hasJob,
  parseEventsPollResult,
  parseStatusResult,
  recordFromLiveEvent,
  type CronJobInfo,
} from '../src/hooks/useCronJobs';

describe('parseStatusResult', () => {
  it('returns null for non-array jobs', () => {
    expect(parseStatusResult({})).toBeNull();
    expect(parseStatusResult({ jobs: 'oops' })).toBeNull();
  });

  it('parses a minimal valid job', () => {
    const out = parseStatusResult({
      jobs: [{ id: 'a', name: 'Daily', expression: '0 2 * * *', enabled: true }],
    });
    expect(out).toEqual([
      { id: 'a', name: 'Daily', expression: '0 2 * * *', enabled: true },
    ]);
  });

  it('skips rows missing required fields', () => {
    const out = parseStatusResult({
      jobs: [
        { id: 'a', name: 'ok', expression: '* * * * *', enabled: true },
        { id: 42, name: 'bad-id', expression: '* * * * *' }, // bad: id not string
        { id: 'c', name: 'no-expr' },                          // bad: missing expression
        'not-an-object',                                       // bad: not object
      ],
    });
    expect(out).toHaveLength(1);
    expect(out![0]!.id).toBe('a');
  });

  it('passes through optional fields when present and valid', () => {
    const out = parseStatusResult({
      jobs: [{
        id: 'a',
        name: 'Daily',
        expression: '0 2 * * *',
        enabled: true,
        kind: 'scheduled',
        description: 'cleanup',
        lastRunAt: '2026-05-01T10:00:00Z',
        nextFireAt: '2026-05-02T02:00:00Z',
        lastError: 'previous failure',
      }],
    });
    expect(out![0]).toMatchObject({
      kind: 'scheduled',
      description: 'cleanup',
      lastRunAt: '2026-05-01T10:00:00Z',
      nextFireAt: '2026-05-02T02:00:00Z',
      lastError: 'previous failure',
    });
  });

  it('omits optional fields rather than assigning undefined (exactOptionalPropertyTypes)', () => {
    const out = parseStatusResult({
      jobs: [{ id: 'a', name: 'X', expression: '* * * * *', enabled: true }],
    });
    // Property must not exist, so consumers using `'kind' in row` style checks
    // get truthful answers. (in undefined === false in JS, but the test
    // documents the contract.)
    expect(Object.prototype.hasOwnProperty.call(out![0], 'kind')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(out![0], 'lastRunAt')).toBe(false);
  });

  it('defaults enabled=true when the field is missing or non-boolean', () => {
    const out = parseStatusResult({
      jobs: [{ id: 'a', name: 'X', expression: '* * * * *' }],
    });
    expect(out![0]!.enabled).toBe(true);
  });
});

describe('applyEventDelta', () => {
  const baseJob = (over: Partial<CronJobInfo> = {}): CronJobInfo => ({
    id: 'a',
    name: 'Daily',
    expression: '0 2 * * *',
    enabled: true,
    ...over,
  });

  it('marks the matching job running on triggered + clears prior error', () => {
    const prev = [baseJob({ lastError: 'old failure' })];
    const next = applyEventDelta(prev, 'scheduler.job_triggered', 'a', {
      timestamp: '2026-05-01T10:00:00Z',
    });
    expect(next[0]).toEqual({
      ...prev[0]!,
      lastError: undefined, // delete leaves no own key — undefined access OK
      lastRunAt: '2026-05-01T10:00:00Z',
      lastStatus: 'running',
    });
    expect('lastError' in next[0]!).toBe(false); // delete removed the key
  });

  it('marks the matching job completed on completed', () => {
    const prev = [baseJob()];
    const next = applyEventDelta(prev, 'scheduler.job_completed', 'a', {
      timestamp: '2026-05-01T10:01:00Z',
    });
    expect(next[0]!.lastStatus).toBe('completed');
    expect(next[0]!.lastRunAt).toBe('2026-05-01T10:01:00Z');
  });

  it('marks failed and stamps the error text', () => {
    // Field-swap regression guard: a copy-paste typo that put params.error
    // into the wrong field would silently render an empty tooltip.
    const prev = [baseJob()];
    const next = applyEventDelta(prev, 'scheduler.job_failed', 'a', {
      error: 'tool exec timeout',
      attempt: 2,
      maxAttempts: 5,
      timestamp: '2026-05-01T10:02:00Z',
    });
    expect(next[0]!.lastStatus).toBe('failed');
    expect(next[0]!.lastError).toBe('tool exec timeout');
    expect(next[0]!.lastRunAt).toBe('2026-05-01T10:02:00Z');
  });

  it('marks skipped without disturbing lastRunAt', () => {
    const prev = [baseJob({ lastRunAt: '2026-05-01T09:00:00Z' })];
    const next = applyEventDelta(prev, 'scheduler.job_skipped', 'a', {
      reason: 'previous run still active',
    });
    expect(next[0]!.lastStatus).toBe('skipped');
    // skipped does not change lastRunAt — the previous run remains the
    // most recent actual execution.
    expect(next[0]!.lastRunAt).toBe('2026-05-01T09:00:00Z');
  });

  it('is a no-op when the jobId is unknown', () => {
    // The hook's responsibility is to detect this case via `hasJob` and
    // fire a status refresh — the delta itself stays a pure no-op so
    // the function can keep its plain (prev → next) shape.
    const prev = [baseJob()];
    const next = applyEventDelta(prev, 'scheduler.job_triggered', 'unknown-id', {
      timestamp: '2026-05-01T10:00:00Z',
    });
    expect(next).toEqual(prev);
  });

  it('does not mutate the input array', () => {
    const prev = [baseJob()];
    applyEventDelta(prev, 'scheduler.job_completed', 'a', {
      timestamp: '2026-05-01T10:01:00Z',
    });
    expect(prev[0]!.lastStatus).toBeUndefined();
  });
});

describe('parseEventsPollResult', () => {
  // Pins the wire-format mapping for the timeline backfill (#459 layer 3).
  // A typo'd `cronExpression` vs `cronExpr` would silently render blank
  // tooltips with no test failure — this guards.

  it('returns null when events is not an array', () => {
    expect(parseEventsPollResult({})).toBeNull();
    expect(parseEventsPollResult({ events: 'oops' })).toBeNull();
  });

  it('parses each of the four event types with their kind-specific fields', () => {
    const out = parseEventsPollResult({
      events: [
        { jobId: 'a', type: 'triggered', timestamp: '2026-05-01T10:00:00Z',
          cronExpression: '* * * * *' },
        { jobId: 'a', type: 'completed', timestamp: '2026-05-01T10:00:01Z',
          durationMs: 1234, summary: 'done' },
        { jobId: 'a', type: 'failed', timestamp: '2026-05-01T10:00:02Z',
          error: 'boom', attempt: 2, maxAttempts: 5 },
        { jobId: 'a', type: 'skipped', timestamp: '2026-05-01T10:00:03Z',
          reason: 'previous run still active' },
      ],
    });
    expect(out).toHaveLength(4);
    expect(out![0]).toMatchObject({ kind: 'triggered', cronExpression: '* * * * *' });
    expect(out![1]).toMatchObject({ kind: 'completed', durationMs: 1234, summary: 'done' });
    expect(out![2]).toMatchObject({
      kind: 'failed',
      error: 'boom',
      attempt: 2,
      maxAttempts: 5,
    });
    expect(out![3]).toMatchObject({ kind: 'skipped', reason: 'previous run still active' });
  });

  it('skips events with unknown type so a future 5th kind doesn\'t crash old dashboards', () => {
    const out = parseEventsPollResult({
      events: [
        { jobId: 'a', type: 'triggered', timestamp: '2026-05-01T10:00:00Z' },
        { jobId: 'a', type: 'magicked-into-existence', timestamp: '2026-05-01T10:00:01Z' },
      ],
    });
    expect(out).toHaveLength(1);
    expect(out![0]!.kind).toBe('triggered');
  });

  it('skips events missing required fields', () => {
    const out = parseEventsPollResult({
      events: [
        { jobId: 'a', type: 'triggered', timestamp: '2026-05-01T10:00:00Z' },
        { type: 'triggered', timestamp: '2026-05-01T10:00:01Z' },     // missing jobId
        { jobId: 'b', type: 'completed' },                            // missing timestamp
      ],
    });
    expect(out).toHaveLength(1);
  });
});

describe('recordFromLiveEvent', () => {
  it('returns null on unknown method', () => {
    expect(recordFromLiveEvent('scheduler.unrelated', 'a', { timestamp: 't' })).toBeNull();
  });

  it('returns null when timestamp is missing', () => {
    expect(recordFromLiveEvent('scheduler.job_triggered', 'a', {})).toBeNull();
  });

  it('maps each live method to the right kind', () => {
    expect(recordFromLiveEvent('scheduler.job_triggered', 'a',
      { timestamp: 't' })?.kind).toBe('triggered');
    expect(recordFromLiveEvent('scheduler.job_completed', 'a',
      { timestamp: 't' })?.kind).toBe('completed');
    expect(recordFromLiveEvent('scheduler.job_failed', 'a',
      { timestamp: 't' })?.kind).toBe('failed');
    expect(recordFromLiveEvent('scheduler.job_skipped', 'a',
      { timestamp: 't' })?.kind).toBe('skipped');
  });

  it('preserves kind-specific fields', () => {
    const r = recordFromLiveEvent('scheduler.job_failed', 'a', {
      timestamp: '2026-05-01T10:00:00Z',
      error: 'boom',
      attempt: 1,
      maxAttempts: 3,
    });
    expect(r).toMatchObject({
      jobId: 'a',
      kind: 'failed',
      error: 'boom',
      attempt: 1,
      maxAttempts: 3,
    });
  });
});

describe('appendCapped', () => {
  it('appends below the cap', () => {
    expect(appendCapped([1, 2], 3, 5)).toEqual([1, 2, 3]);
  });

  it('drops oldest when at cap', () => {
    expect(appendCapped([1, 2, 3], 4, 3)).toEqual([2, 3, 4]);
  });

  it('drops multiple when exceeding cap', () => {
    // Edge case — we wouldn't normally hit this since the buffer is
    // appended one item at a time, but the math has to be right.
    expect(appendCapped([1, 2, 3, 4, 5], 6, 3)).toEqual([4, 5, 6]);
  });

  it('does not mutate the input array', () => {
    const prev = [1, 2, 3];
    appendCapped(prev, 4, 3);
    expect(prev).toEqual([1, 2, 3]);
  });
});

describe('hasJob', () => {
  // The hook uses hasJob to gate "apply delta vs fire snapshot refresh"
  // — so a `scheduler.job_*` event for a job created after our snapshot
  // triggers a re-fetch instead of being silently dropped. This is the
  // counterpart to applyEventDelta's "no-op on unknown id" contract.
  const baseJob = (id: string): CronJobInfo => ({
    id,
    name: 'X',
    expression: '* * * * *',
    enabled: true,
  });

  it('returns true for an id present in the list', () => {
    expect(hasJob([baseJob('a'), baseJob('b')], 'b')).toBe(true);
  });

  it('returns false for an id missing from the list', () => {
    expect(hasJob([baseJob('a')], 'unknown')).toBe(false);
  });

  it('returns false for an empty list', () => {
    expect(hasJob([], 'a')).toBe(false);
  });
});
