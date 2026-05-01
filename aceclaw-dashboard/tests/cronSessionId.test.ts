/**
 * Pins the {@code "cron-{jobId}"} sessionId convention (#459).
 *
 * Both sides of the wire need to agree on this string — daemon
 * broadcasts cron-run events under it (CronScheduler.runJobOnce →
 * BroadcastOnlyStreamContext), and the dashboard navigates to it when
 * the user clicks a row in CronJobsList. A drift between the two
 * would silently produce an empty ExecutionTree.
 */

import { describe, expect, it } from 'vitest';
import { cronSessionId } from '../src/components/CronJobsList';

describe('cronSessionId', () => {
  it('prefixes the job id with "cron-"', () => {
    expect(cronSessionId('daily-backup')).toBe('cron-daily-backup');
  });

  it('matches the daemon-side prefix exactly', () => {
    // The daemon constructs this string at CronScheduler.runJobOnce:
    //   "cron-" + job.id()
    // Any drift here breaks the dashboard's session navigation.
    expect(cronSessionId('x')).toBe('cron-x');
  });
});
