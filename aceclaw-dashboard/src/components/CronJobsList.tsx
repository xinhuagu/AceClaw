/**
 * CronJobsList — sidebar section showing the daemon's cron jobs (#459).
 *
 * Each row is clickable: selecting a job navigates the main pane to the
 * cron's session tree (sessionId = {@code "cron-" + jobId}). The mental
 * model is **a cron job IS a session, each fire IS a turn** — so the
 * existing ExecutionTree component renders cron history naturally as
 * accumulated turns under one tree, no new visualization needed.
 */

import type { CronJobInfo } from '../hooks/useCronJobs';

interface CronJobsListProps {
  jobs: CronJobInfo[];
  /**
   * Currently-selected sessionId from the main pane. Used to highlight
   * the active row when the user is viewing a cron's tree. Pass the
   * same value the SessionList receives — App keeps a single
   * selectedSessionId for both lists.
   */
  selectedSessionId: string | null;
  /**
   * Invoked with the cron's sessionId ({@code "cron-" + jobId}) when
   * the user clicks a row. App routes this through the same
   * selectSession handler the SessionList uses.
   */
  onSelect: (sessionId: string) => void;
}

/**
 * Maps a job id to the deterministic sessionId the daemon broadcasts under.
 *
 * <p><b>Daemon coupling</b>: the {@code 'cron-'} prefix mirrors
 * {@code CronScheduler.CRON_SESSION_PREFIX} on the Java side. The sentinel
 * test {@code cronSessionPrefixIsStableForDashboardCompat} (in
 * CronSchedulerTest) fires if the daemon constant changes without a
 * paired update here.
 */
export function cronSessionId(jobId: string): string {
  return `cron-${jobId}`;
}

export function CronJobsList({ jobs, selectedSessionId, onSelect }: CronJobsListProps) {
  return (
    <section className="flex shrink-0 flex-col border-t border-zinc-800">
      <header className="flex items-center justify-between border-b border-zinc-800 px-3 py-2 text-[11px] uppercase tracking-wider text-zinc-500">
        <span>Scheduled jobs</span>
        <span className="font-mono text-zinc-600">{jobs.length}</span>
      </header>
      {jobs.length === 0 ? (
        <p className="px-3 py-4 text-xs text-zinc-500">
          No cron jobs.{' '}
          <span className="text-zinc-600">Add one with the CLI.</span>
        </p>
      ) : (
        <ul className="max-h-72 overflow-y-auto">
          {jobs.map((job) => (
            <CronJobRow
              key={job.id}
              job={job}
              selected={selectedSessionId === cronSessionId(job.id)}
              onSelect={onSelect}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

interface CronJobRowProps {
  job: CronJobInfo;
  selected: boolean;
  onSelect: (sessionId: string) => void;
}

function CronJobRow({ job, selected, onSelect }: CronJobRowProps) {
  const dotColor = statusDotColor(job);
  const subtitle = subtitleFor(job);
  // Mirror the SessionRow visual contract: left border + lighter bg
  // when active, hover state otherwise. Same vocabulary across both
  // lists keeps the sidebar coherent.
  const baseBg = selected ? 'bg-zinc-800/60' : 'hover:bg-zinc-800/40';
  const accent = selected ? 'border-l-2 border-blue-500' : 'border-l-2 border-transparent';
  return (
    <li>
      <button
        type="button"
        onClick={() => onSelect(cronSessionId(job.id))}
        className={`flex w-full items-start gap-2 px-3 py-2 text-left text-xs transition-colors ${baseBg} ${accent}`}
        title={job.lastError ?? job.description ?? job.id}
      >
        <span
          className={`mt-1 h-2 w-2 shrink-0 rounded-full ${dotColor}`}
          aria-label={`status: ${job.lastStatus ?? 'idle'}`}
        />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-zinc-200">{job.name}</span>
            {!job.enabled && (
              <span className="rounded bg-zinc-800 px-1 py-px font-mono text-[9px] uppercase text-zinc-500">
                disabled
              </span>
            )}
          </div>
          <div className="flex items-center gap-2 text-[10px] text-zinc-500">
            <span className="truncate font-mono">{job.expression}</span>
            <span>·</span>
            <span className="whitespace-nowrap">{subtitle}</span>
          </div>
        </div>
      </button>
    </li>
  );
}

/**
 * Picks the dot colour from the most recent observed status, falling back
 * to grey when nothing's happened since the dashboard connected.
 *
 * Priority order:
 *   1. awaitingPermission → yellow pulse (operator action needed —
 *      wins over everything; a paused cron is the most actionable
 *      signal regardless of run history)
 *   2. disabled → dim grey
 *   3. lastStatus running/completed/failed/skipped → mapped colours
 *   4. fallback from snapshot metadata
 *
 * Exported for unit tests so the priority rules can't drift unnoticed.
 */
export function statusDotColor(job: CronJobInfo): string {
  if (job.awaitingPermission) return 'bg-amber-400 animate-pulse';
  if (!job.enabled) return 'bg-zinc-700';
  switch (job.lastStatus) {
    case 'running':
      return 'bg-blue-500 animate-pulse';
    case 'completed':
      return 'bg-emerald-500';
    case 'failed':
      return 'bg-rose-500';
    case 'skipped':
      return 'bg-amber-500';
    default:
      // No event observed yet but the daemon may have run it pre-connect:
      // surface failed-from-snapshot first, then last-run-at, else idle.
      if (job.lastError) return 'bg-rose-500';
      if (job.lastRunAt) return 'bg-emerald-700';
      return 'bg-zinc-600';
  }
}

/**
 * Per-row second line. Prefer "next fires in Xm" when we have it (this is
 * the unique value a scheduler dashboard adds over a generic log viewer);
 * fall back to "ran Xm ago" so a job that's already past its last fire
 * still surfaces something useful.
 */
function subtitleFor(job: CronJobInfo): string {
  if (job.nextFireAt) {
    const next = relativeFutureTime(job.nextFireAt);
    if (next) return `next ${next}`;
  }
  if (job.lastRunAt) {
    return `ran ${relativePastTime(job.lastRunAt)}`;
  }
  return 'never run';
}

/** "in 23m" / "in 2h" / "in 3d" — null when the timestamp is in the past. */
function relativeFutureTime(iso: string): string | null {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return null;
  const seconds = Math.floor((t - Date.now()) / 1000);
  if (seconds < 0) return null;
  if (seconds < 60) return `in ${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `in ${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `in ${hours}h`;
  const days = Math.floor(hours / 24);
  return `in ${days}d`;
}

/** "Xs ago" / "Xm ago" / "Xh ago" / "Xd ago" — same format as SessionList. */
function relativePastTime(iso: string): string {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '—';
  const seconds = Math.max(0, Math.floor((Date.now() - t) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}
