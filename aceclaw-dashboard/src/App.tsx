import { useState } from 'react';
import { ExecutionTree } from './components/ExecutionTree';
import { SessionList } from './components/SessionList';
import { useExecutionTree } from './hooks/useExecutionTree';
import { useSessions } from './hooks/useSessions';

/**
 * Tier 1 dashboard root (issues #434 → #436, sidebar from #445).
 *
 * Layout:
 *   ┌──────────────┬──────────────────────────────────────┐
 *   │ SessionList  │ StatusBar                            │
 *   │   sidebar    ├──────────────────────────────────────┤
 *   │  (#445)      │ ExecutionTree (#436)                 │
 *   │              │                                      │
 *   └──────────────┴──────────────────────────────────────┘
 *
 * Two independent WebSockets (one per hook). The sidebar's
 * {@link useSessions} sends {@code sessions.list} once on connect and then
 * tails {@code stream.session_started} / {@code stream.session_ended} for
 * live deltas. The tree's {@link useExecutionTree} filters by sessionId so
 * only the selected session's events drive the rendered tree.
 *
 * Reading WS URL + session from query params keeps Tier 1 friendly:
 *   http://localhost:5173/?session=<id>&ws=ws://localhost:3141/ws
 * once the daemon is running. Selecting a session in the sidebar updates
 * the URL in place so reloads remember the selection.
 */

const DEFAULT_WS_URL = 'ws://localhost:3141/ws';

function readQueryParam(name: string): string | null {
  if (typeof window === 'undefined') return null;
  return new URLSearchParams(window.location.search).get(name);
}

/**
 * Validates that {@code raw} is a usable WebSocket URL. The {@code WebSocket}
 * constructor throws synchronously on garbage input ({@code "localhost:3141"},
 * {@code "foo"}, missing protocol), which would otherwise take down the
 * dashboard before the React error boundary can render anything. We
 * pre-check the protocol and return null on anything unfit, letting the
 * caller fall back to the default URL. Exported for unit testing.
 */
export function sanitiseWsUrl(raw: string | null | undefined): string | null {
  if (!raw) return null;
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return null;
  }
  if (parsed.protocol !== 'ws:' && parsed.protocol !== 'wss:') return null;
  return parsed.toString();
}

export function App() {
  const initialSession = readQueryParam('session') ?? '';
  const initialWs = sanitiseWsUrl(readQueryParam('ws')) ?? DEFAULT_WS_URL;
  const [sessionId, setSessionId] = useState(initialSession);
  const [wsUrl] = useState(initialWs);
  const sessions = useSessions(wsUrl);

  // Selecting a session in the sidebar replaces the URL so reload preserves
  // the selection — replaceState rather than pushState so the back button
  // doesn't treat session-switching as navigation history.
  const selectSession = (newSessionId: string): void => {
    setSessionId(newSessionId);
    if (typeof window !== 'undefined') {
      const url = new URL(window.location.href);
      url.searchParams.set('session', newSessionId);
      window.history.replaceState(null, '', url.toString());
    }
  };

  return (
    <div className="flex h-full">
      <SessionList
        sessions={sessions}
        selectedSessionId={sessionId || null}
        onSelect={selectSession}
      />
      <div className="flex flex-1 flex-col overflow-hidden">
        {sessionId ? (
          <DashboardConnected sessionId={sessionId.trim()} wsUrl={wsUrl} />
        ) : (
          <SessionPrompt
            onSubmit={selectSession}
            defaultWsUrl={initialWs}
            hasSessions={sessions.length > 0}
          />
        )}
      </div>
    </div>
  );
}

interface SessionPromptProps {
  onSubmit: (sessionId: string) => void;
  defaultWsUrl: string;
  hasSessions: boolean;
}

function SessionPrompt({ onSubmit, defaultWsUrl, hasSessions }: SessionPromptProps) {
  const [draft, setDraft] = useState('');
  return (
    <main className="flex h-full flex-col items-center justify-center gap-6 p-8">
      <h1 className="text-2xl font-semibold tracking-tight">AceClaw Dashboard</h1>
      <form
        className="flex w-full max-w-md flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (draft.trim()) onSubmit(draft);
        }}
      >
        <p className="text-sm text-zinc-400">
          {hasSessions
            ? 'Pick a session from the sidebar, or paste an ID below.'
            : 'No sessions yet — start one with the CLI, or paste an ID below.'}
        </p>
        <label className="flex flex-col gap-1 text-sm text-zinc-400">
          Session ID
          <input
            autoFocus
            className="rounded-md border border-zinc-800 bg-zinc-900 px-3 py-2 font-mono text-sm text-zinc-100 outline-none focus:border-zinc-600"
            placeholder="paste a session id from the CLI"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
        </label>
        <p className="text-xs text-zinc-500">
          Connecting to <span className="font-mono">{defaultWsUrl}</span>. Daemon
          must have <span className="font-mono">webSocket.enabled</span> and{' '}
          <span className="font-mono">allowedOrigins</span> set for this origin.
        </p>
        <button
          type="submit"
          className="rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-500 disabled:opacity-50"
          disabled={!draft.trim()}
        >
          Watch session
        </button>
      </form>
    </main>
  );
}

interface DashboardConnectedProps {
  sessionId: string;
  wsUrl: string;
}

function DashboardConnected({ sessionId, wsUrl }: DashboardConnectedProps) {
  const { tree, status, sendCommand, resolvePermission, dismissPermission } =
    useExecutionTree(wsUrl, sessionId);

  // The Approve / Deny click does TWO things:
  //   1. POST permission.response to the daemon over the WS so the
  //      blocked tool resumes (or returns "Permission denied").
  //   2. Optimistically update the local tree so the panel disappears
  //      and the tool node returns to the running/cancelled state
  //      without waiting for the daemon's tool_completed echo.
  // First-response-wins on the daemon (#433) makes a duplicate fine —
  // if the CLI already answered, our send is dropped server-side and
  // the local optimistic update gets superseded by the next
  // tool_completed event. Stamping resolvedBy='browser' beforehand also
  // guards against the reducer mistaking that completion for a CLI
  // resolution.
  const handleApprove = (requestId: string): void => {
    sendCommand({
      method: 'permission.response',
      params: { requestId, approved: true },
    });
    resolvePermission(requestId, true);
  };
  const handleDeny = (requestId: string): void => {
    sendCommand({
      method: 'permission.response',
      params: { requestId, approved: false },
    });
    resolvePermission(requestId, false);
  };

  return (
    <>
      <StatusBar sessionId={sessionId} status={status} stats={tree.stats} />
      <div className="flex-1">
        <ExecutionTree
          tree={tree}
          onApprovePermission={handleApprove}
          onDenyPermission={handleDeny}
          onDismissPermission={dismissPermission}
        />
      </div>
    </>
  );
}

interface StatusBarProps {
  sessionId: string;
  status: ReturnType<typeof useExecutionTree>['status'];
  stats: ReturnType<typeof useExecutionTree>['tree']['stats'];
}

const STATUS_DOT: Record<StatusBarProps['status'], string> = {
  connecting: 'bg-zinc-500',
  open: 'bg-emerald-500',
  reconnecting: 'bg-amber-500',
  closed: 'bg-zinc-600',
  error: 'bg-red-500',
};

function StatusBar({ sessionId, status, stats }: StatusBarProps) {
  return (
    <header className="flex items-center gap-4 border-b border-zinc-800 bg-zinc-900/60 px-4 py-2 text-xs text-zinc-300">
      <div className="flex items-center gap-2">
        <span className={`h-2 w-2 rounded-full ${STATUS_DOT[status]}`} />
        <span className="font-mono uppercase">{status}</span>
      </div>
      <div className="font-mono text-zinc-500">{sessionId}</div>
      <div className="ml-auto flex items-center gap-4 font-mono text-zinc-400">
        <span>turns {stats.totalTurns}</span>
        <span>
          tools {stats.completedTools}/{stats.totalTools}
          {stats.failedTools > 0 ? ` (${stats.failedTools}✗)` : ''}
        </span>
        <span>
          tok {stats.inputTokens}↓ {stats.outputTokens}↑
        </span>
      </div>
    </header>
  );
}
