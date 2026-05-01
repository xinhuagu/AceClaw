/**
 * Pure-helper tests for {@link CronTimeline} (#459 layer 3).
 *
 * The component itself is mostly SVG glue; the layout math
 * (projectX, filterEventsForJob) and the colour palette are the
 * places typos would silently produce wrong-looking output. Each is
 * exported for direct testing.
 */

import { describe, expect, it } from 'vitest';
import {
  eventColor,
  filterEventsForJob,
  projectX,
} from '../src/components/CronTimeline';
import type { CronEventRecord } from '../src/hooks/useCronJobs';

describe('projectX', () => {
  // The timeline's SVG viewBox is 0..1000. The left gutter (96px)
  // hosts job-name labels; the right pad (16px) makes the now-line
  // sit cleanly inside the frame.

  it('maps windowEnd to the right edge minus the right pad', () => {
    const x = projectX(1000, 0, 1000);
    expect(x).toBe(1000 - 16);
  });

  it('maps windowStart to the left gutter', () => {
    expect(projectX(0, 0, 1000)).toBe(96);
  });

  it('clamps a timestamp before windowStart to the left gutter', () => {
    expect(projectX(-100, 0, 1000)).toBe(96);
  });

  it('clamps a timestamp after windowEnd to the right edge', () => {
    expect(projectX(2000, 0, 1000)).toBe(1000 - 16);
  });

  it('handles a zero-span window without dividing by zero', () => {
    expect(projectX(500, 500, 500)).toBe(96);
  });
});

describe('filterEventsForJob', () => {
  const ev = (jobId: string, ts: string): CronEventRecord => ({
    jobId,
    kind: 'completed',
    timestamp: ts,
  });

  it('filters by jobId', () => {
    const events = [ev('a', '2026-05-01T10:00:00Z'), ev('b', '2026-05-01T10:00:01Z')];
    const out = filterEventsForJob(events, 'a',
      Date.parse('2026-05-01T09:00:00Z'),
      Date.parse('2026-05-01T11:00:00Z'));
    expect(out).toHaveLength(1);
    expect(out[0]!.jobId).toBe('a');
  });

  it('drops events outside the window', () => {
    const events = [
      ev('a', '2026-05-01T05:00:00Z'), // way before
      ev('a', '2026-05-01T10:00:00Z'), // inside
      ev('a', '2026-05-01T15:00:00Z'), // way after
    ];
    const out = filterEventsForJob(events, 'a',
      Date.parse('2026-05-01T09:00:00Z'),
      Date.parse('2026-05-01T11:00:00Z'));
    expect(out).toHaveLength(1);
    expect(out[0]!.timestamp).toBe('2026-05-01T10:00:00Z');
  });

  it('drops events with malformed timestamps', () => {
    const events = [ev('a', 'not-a-date'), ev('a', '2026-05-01T10:00:00Z')];
    const out = filterEventsForJob(events, 'a',
      Date.parse('2026-05-01T09:00:00Z'),
      Date.parse('2026-05-01T11:00:00Z'));
    expect(out).toHaveLength(1);
  });

  it('returns events sorted by timestamp ascending', () => {
    const events = [
      ev('a', '2026-05-01T10:00:30Z'),
      ev('a', '2026-05-01T10:00:00Z'),
      ev('a', '2026-05-01T10:00:15Z'),
    ];
    const out = filterEventsForJob(events, 'a',
      Date.parse('2026-05-01T09:00:00Z'),
      Date.parse('2026-05-01T11:00:00Z'));
    expect(out.map((e) => e.timestamp)).toEqual([
      '2026-05-01T10:00:00Z',
      '2026-05-01T10:00:15Z',
      '2026-05-01T10:00:30Z',
    ]);
  });
});

describe('eventColor', () => {
  // Pinning so a future palette refactor doesn't accidentally swap
  // green for red and quietly invert the user's reading of "the job
  // is healthy".
  it('returns a distinct hex code per kind', () => {
    const colors = new Set([
      eventColor('triggered'),
      eventColor('completed'),
      eventColor('failed'),
      eventColor('skipped'),
    ]);
    expect(colors.size).toBe(4);
  });

  it('uses green-ish for completed', () => {
    expect(eventColor('completed')).toMatch(/^#10/i); // emerald-500 starts with #10
  });

  it('uses red-ish for failed', () => {
    expect(eventColor('failed')).toMatch(/^#f4/i); // rose-500 starts with #f4
  });
});
