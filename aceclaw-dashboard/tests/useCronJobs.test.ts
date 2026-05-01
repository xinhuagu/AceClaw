/**
 * Unit tests for useCronJobs's pure helpers (#459 layer 2).
 *
 * The hook itself wires WebSocket plumbing — we test it via the helpers
 * (parseStatusResult, applyEventDelta) so wire-format and delta logic
 * have a fast safety net independent of a live socket.
 */

import { describe, expect, it } from 'vitest';
import {
  applyEventDelta,
  parseStatusResult,
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
