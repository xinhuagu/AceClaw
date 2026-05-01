/**
 * CronTimeline (#459 layer 3) — compact horizontal timeline that turns
 * the daemon's scheduling into a swimlane chart.
 *
 * One row per cron job. Past triggers render as filled rectangles
 * coloured by outcome (green/red/blue/amber); the next scheduled fire
 * for each job renders as an outlined rect at its predicted time. The
 * "now" line slides right as wall-clock time advances.
 *
 * Sources its data from the same {@code useCronJobs} hook the sidebar
 * uses — no second WebSocket. See #462 for the multi-connection
 * trade-off.
 */

import { useEffect, useState } from 'react';
import type { CronEventRecord, CronJobInfo } from '../hooks/useCronJobs';

interface CronTimelineProps {
  jobs: CronJobInfo[];
  recentEvents: CronEventRecord[];
  /** Width of the visible time window. Defaults to 1h. */
  windowMs?: number;
}

const DEFAULT_WINDOW_MS = 60 * 60 * 1000; // 1 hour
const LANE_HEIGHT_PX = 18;
const LANE_GAP_PX = 4;
const LEFT_GUTTER_PX = 96; // job-name column
const RIGHT_PAD_PX = 16;
const AXIS_HEIGHT_PX = 18;
const NOW_TICK_INTERVAL_MS = 10_000;

export function CronTimeline({
  jobs,
  recentEvents,
  windowMs = DEFAULT_WINDOW_MS,
}: CronTimelineProps) {
  // Re-render every 10s so the "now" line drifts visibly without
  // requiring user interaction. Heavier ticking would be a waste —
  // events themselves trigger renders when something changes.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), NOW_TICK_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, []);

  // Hide entirely when no jobs are configured — keeps the dashboard
  // chrome clean for users who don't use cron.
  if (jobs.length === 0) return null;

  const totalHeight =
    AXIS_HEIGHT_PX + jobs.length * LANE_HEIGHT_PX + (jobs.length - 1) * LANE_GAP_PX;
  const windowStart = now - windowMs;

  return (
    <div className="border-b border-zinc-800 bg-zinc-900/40 px-2 py-2">
      <div className="mb-1 flex items-center gap-2 text-[10px] uppercase tracking-wider text-zinc-500">
        <span>Schedule timeline</span>
        <span className="font-mono text-zinc-600">last {Math.round(windowMs / 60_000)}m</span>
      </div>
      <svg
        className="block w-full"
        viewBox={`0 0 1000 ${totalHeight}`}
        preserveAspectRatio="none"
        style={{ height: totalHeight }}
        role="img"
        aria-label="Cron schedule timeline"
      >
        <Axis windowStart={windowStart} windowEnd={now} y={AXIS_HEIGHT_PX - 4} />
        {jobs.map((job, idx) => {
          const laneY = AXIS_HEIGHT_PX + idx * (LANE_HEIGHT_PX + LANE_GAP_PX);
          const events = filterEventsForJob(recentEvents, job.id, windowStart, now);
          return (
            <JobLane
              key={job.id}
              job={job}
              events={events}
              y={laneY}
              windowStart={windowStart}
              windowEnd={now}
            />
          );
        })}
        <NowLine y={AXIS_HEIGHT_PX} height={totalHeight - AXIS_HEIGHT_PX} />
      </svg>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

interface AxisProps {
  windowStart: number;
  windowEnd: number;
  y: number;
}

function Axis({ windowStart, windowEnd, y }: AxisProps) {
  // Ticks at -60m / -45m / -30m / -15m / now (assuming 1h window).
  // Rendered on the SVG's own coordinate system (0..1000 + LEFT_GUTTER_PX).
  const ticks: Array<{ label: string; t: number }> = [];
  const span = windowEnd - windowStart;
  for (let i = 0; i <= 4; i += 1) {
    const t = windowStart + (span * i) / 4;
    const minutesAgo = Math.round((windowEnd - t) / 60_000);
    ticks.push({
      t,
      label: minutesAgo === 0 ? 'now' : `-${minutesAgo}m`,
    });
  }
  return (
    <g>
      {ticks.map(({ t, label }) => {
        const x = projectX(t, windowStart, windowEnd);
        return (
          <g key={`tick-${label}`}>
            <line
              x1={x}
              x2={x}
              y1={y - 2}
              y2={y + 2}
              stroke="#3f3f46"
              strokeWidth={1}
            />
            <text
              x={x}
              y={y - 4}
              fontSize={9}
              fill="#71717a"
              fontFamily="ui-monospace, 'JetBrains Mono', monospace"
              textAnchor="middle"
            >
              {label}
            </text>
          </g>
        );
      })}
    </g>
  );
}

interface JobLaneProps {
  job: CronJobInfo;
  events: CronEventRecord[];
  y: number;
  windowStart: number;
  windowEnd: number;
}

function JobLane({ job, events, y, windowStart, windowEnd }: JobLaneProps) {
  const nextX =
    job.nextFireAt && Date.parse(job.nextFireAt) <= windowEnd + (windowEnd - windowStart)
      ? projectX(Date.parse(job.nextFireAt), windowStart, windowEnd)
      : null;
  return (
    <g>
      {/* Job name column */}
      <text
        x={LEFT_GUTTER_PX - 8}
        y={y + LANE_HEIGHT_PX / 2 + 3}
        fontSize={10}
        fill="#a1a1aa"
        fontFamily="ui-monospace, 'JetBrains Mono', monospace"
        textAnchor="end"
      >
        {truncate(job.name, 14)}
        <title>{job.id}</title>
      </text>
      {/* Lane background */}
      <rect
        x={LEFT_GUTTER_PX}
        y={y}
        width={1000 - LEFT_GUTTER_PX - RIGHT_PAD_PX}
        height={LANE_HEIGHT_PX}
        fill="#18181b"
        rx={2}
      />
      {/* Past events */}
      {events.map((evt, i) => {
        const t = Date.parse(evt.timestamp);
        if (Number.isNaN(t)) return null;
        const x = projectX(t, windowStart, windowEnd);
        const color = eventColor(evt.kind);
        return (
          <rect
            key={`${evt.timestamp}-${i}`}
            x={x - 2}
            y={y + 2}
            width={4}
            height={LANE_HEIGHT_PX - 4}
            fill={color}
            rx={1}
          >
            <title>
              {evt.kind} @ {evt.timestamp}
              {evt.durationMs ? ` (${evt.durationMs}ms)` : ''}
              {evt.error ? `\n${evt.error}` : ''}
              {evt.reason ? `\n${evt.reason}` : ''}
            </title>
          </rect>
        );
      })}
      {/* Next scheduled fire — only render when it falls in (or near) the window */}
      {nextX !== null && nextX >= LEFT_GUTTER_PX && nextX <= 1000 - RIGHT_PAD_PX ? (
        <rect
          x={nextX - 2}
          y={y + 2}
          width={4}
          height={LANE_HEIGHT_PX - 4}
          fill="none"
          stroke="#71717a"
          strokeDasharray="2,1"
          rx={1}
        >
          <title>next fire @ {job.nextFireAt}</title>
        </rect>
      ) : null}
    </g>
  );
}

interface NowLineProps {
  y: number;
  height: number;
}

function NowLine({ y, height }: NowLineProps) {
  // "now" sits at the right edge of the window (we project windowEnd
  // there) — drawing a line here gives the user a visual anchor for
  // "everything left of this is past, everything right is forecast".
  const x = 1000 - RIGHT_PAD_PX;
  return (
    <line
      x1={x}
      x2={x}
      y1={y}
      y2={y + height}
      stroke="#3f3f46"
      strokeDasharray="1,2"
      strokeWidth={1}
    />
  );
}

// ---------------------------------------------------------------------------
// Pure helpers (exported for unit tests)
// ---------------------------------------------------------------------------

/**
 * Maps a timestamp to an x-coordinate in the SVG's 0..1000 viewBox.
 * Times outside the window are clamped to the gutter / right edge,
 * so a caller still gets a sensible value when filtering's a thin slice.
 */
export function projectX(t: number, windowStart: number, windowEnd: number): number {
  const span = windowEnd - windowStart;
  if (span <= 0) return LEFT_GUTTER_PX;
  const ratio = (t - windowStart) / span;
  const drawWidth = 1000 - LEFT_GUTTER_PX - RIGHT_PAD_PX;
  return LEFT_GUTTER_PX + Math.max(0, Math.min(1, ratio)) * drawWidth;
}

/** Filters and sorts events for a given job within [windowStart, windowEnd]. */
export function filterEventsForJob(
  events: CronEventRecord[],
  jobId: string,
  windowStart: number,
  windowEnd: number,
): CronEventRecord[] {
  return events
    .filter((e) => {
      if (e.jobId !== jobId) return false;
      const t = Date.parse(e.timestamp);
      if (Number.isNaN(t)) return false;
      return t >= windowStart && t <= windowEnd;
    })
    .sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp));
}

/** Status palette mirrors the sidebar so the visual vocabulary stays consistent. */
export function eventColor(kind: CronEventRecord['kind']): string {
  switch (kind) {
    case 'triggered':
      return '#3b82f6'; // blue-500
    case 'completed':
      return '#10b981'; // emerald-500
    case 'failed':
      return '#f43f5e'; // rose-500
    case 'skipped':
      return '#f59e0b'; // amber-500
  }
}

function truncate(s: string, max: number): string {
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}
