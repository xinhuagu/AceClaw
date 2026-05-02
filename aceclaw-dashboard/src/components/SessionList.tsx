/**
 * SessionList sidebar (issue #445).
 *
 * Lists every active (and recently-closed) session the daemon knows about,
 * sourced from {@link useSessions}. Click an entry → switch the dashboard's
 * focus to that session via the {@code onSelect} callback (App keeps the
 * actual sessionId state).
 *
 * Visual contract:
 *   - Currently-selected session is highlighted (left border + lighter bg)
 *   - Active sessions get a green pulse dot, closed sessions a grey dot
 *   - Project path is shown tail-truncated (only the last 2 segments) so the
 *     sidebar stays narrow while still distinguishing repos
 *   - "started Xm ago" relative timestamp, computed at render time (no
 *     ticking — the dashboard isn't a stopwatch)
 */

import type { SessionInfo } from '../hooks/useSessions';

interface SessionListProps {
  sessions: SessionInfo[];
  selectedSessionId: string | null;
  onSelect: (sessionId: string) => void;
  /**
   * Optional content rendered at the bottom of the sidebar, beneath the
   * session rows. Used by App to mount the CronJobsList panel (#459)
   * without wrapping SessionList in another container — the parent
   * &lt;aside&gt; here is already the dashboard's left rail.
   */
  footer?: React.ReactNode;
}

export function SessionList({
  sessions,
  selectedSessionId,
  onSelect,
  footer,
}: SessionListProps) {
  return (
    <aside className="flex h-full w-60 shrink-0 flex-col border-r border-zinc-800 bg-zinc-900/40">
      <header className="flex items-center justify-between border-b border-zinc-800 px-3 py-2 text-[11px] uppercase tracking-wider text-zinc-500">
        <span>Sessions</span>
        <span className="font-mono text-zinc-600">{sessions.length}</span>
      </header>
      {sessions.length === 0 ? (
        <p className="px-3 py-4 text-xs text-zinc-500">
          No active sessions.{' '}
          <span className="text-zinc-600">Start one with the CLI.</span>
        </p>
      ) : (
        <ul className="flex-1 overflow-y-auto">
          {sessions.map((s) => (
            <SessionRow
              key={s.sessionId}
              session={s}
              selected={s.sessionId === selectedSessionId}
              onSelect={onSelect}
            />
          ))}
        </ul>
      )}
      {footer}
    </aside>
  );
}

interface SessionRowProps {
  session: SessionInfo;
  selected: boolean;
  onSelect: (sessionId: string) => void;
}

function SessionRow({ session, selected, onSelect }: SessionRowProps) {
  const dot = sessionStatusDot(session);
  const baseBg = selected ? 'bg-zinc-800/60' : 'hover:bg-zinc-800/40';
  const accent = selected ? 'border-l-2 border-blue-500' : 'border-l-2 border-transparent';
  return (
    <li>
      <button
        type="button"
        onClick={() => onSelect(session.sessionId)}
        className={`flex w-full items-start gap-2 px-3 py-2 text-left text-xs transition-colors ${baseBg} ${accent}`}
      >
        <span
          className={`mt-1 h-2 w-2 shrink-0 rounded-full ${dot}`}
          aria-label={`status: ${session.executionStatus ?? (session.active ? 'idle' : 'closed')}`}
        />
        <div className="min-w-0 flex-1">
          <div className="truncate font-mono text-zinc-200" title={session.projectPath}>
            {tailPath(session.projectPath)}
          </div>
          <div className="flex items-center gap-2 text-[10px] text-zinc-500">
            <span className="font-mono">{shortId(session.sessionId)}</span>
            <span>·</span>
            <span>{relativeTime(session.createdAt)}</span>
          </div>
        </div>
      </button>
    </li>
  );
}

/** Last two path segments — keeps the sidebar narrow but still informative. */
function tailPath(path: string): string {
  if (!path) return '(unknown)';
  const parts = path.split(/[/\\]/).filter(Boolean);
  if (parts.length <= 2) return path;
  return `…/${parts.slice(-2).join('/')}`;
}

/** First eight chars of the sessionId — enough to disambiguate in practice. */
function shortId(id: string): string {
  return id.slice(0, 8);
}

/**
 * Picks the status dot's tailwind class from the session's live
 * execution status. Falls back to the older active/inactive boolean
 * when nothing has been observed yet — a snapshot-replayed session
 * that hasn't received any envelope still gets a sensible colour.
 *
 * Colour rules (mirror CronJobsList for consistency):
 *   - awaiting → amber, pulsing (operator action needed)
 *   - running  → blue, pulsing
 *   - completed → emerald
 *   - failed → rose
 *   - active but no event yet → faint green
 *   - inactive (closed) → grey
 *
 * Exported for unit tests so the rule table can't drift unnoticed.
 */
export function sessionStatusDot(session: SessionInfo): string {
  switch (session.executionStatus) {
    case 'awaiting':
      return 'bg-amber-400 animate-pulse';
    case 'running':
      return 'bg-blue-500 animate-pulse';
    case 'completed':
      return 'bg-emerald-500';
    case 'failed':
      return 'bg-rose-500';
    default:
      return session.active ? 'bg-emerald-700' : 'bg-zinc-600';
  }
}

/** Coarse human-friendly elapsed time. Rendered once per repaint, no ticking. */
function relativeTime(iso: string): string {
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
