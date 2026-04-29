import { useState } from 'react';
import { ExecutionTree } from './components/ExecutionTree';
import { useExecutionTree } from './hooks/useExecutionTree';

/**
 * Tier 1 dashboard root (issues #434 → #436). Wires three pieces:
 *
 *   1. {@link useExecutionTree} — opens a WebSocket to the daemon's bridge
 *      (#431) and exposes a reactive {@link ExecutionTreeState}.
 *   2. {@link ExecutionTree} — SVG tree with dagre layout + Framer Motion
 *      animations (#436).
 *   3. A small status bar with the connection state, session id, and live
 *      stats from the reducer.
 *
 * Reading WS URL + session from query params keeps this Tier 1 friendly:
 * a developer can hit
 *   http://localhost:5173/?session=&ws=ws://localhost:3141/ws
 * once the daemon is running with webSocket.enabled and an allowlisted
 * origin, and see live trees without rebuilding.
 */

const DEFAULT_WS_URL = 'ws://localhost:3141/ws';

function readQueryParam(name: string): string | null {
  if (typeof window === 'undefined') return null;
  return new URLSearchParams(window.location.search).get(name);
}

export function App() {
  const initialSession = readQueryParam('session') ?? '';
  const initialWs = readQueryParam('ws') ?? DEFAULT_WS_URL;
  const [sessionId, setSessionId] = useState(initialSession);
  const [wsUrl] = useState(initialWs);

  if (!sessionId.trim()) {
    return <SessionPrompt onSubmit={setSessionId} defaultWsUrl={initialWs} />;
  }

  return <DashboardConnected sessionId={sessionId.trim()} wsUrl={wsUrl} />;
}

interface SessionPromptProps {
  onSubmit: (sessionId: string) => void;
  defaultWsUrl: string;
}

function SessionPrompt({ onSubmit, defaultWsUrl }: SessionPromptProps) {
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
  const { tree, status } = useExecutionTree(wsUrl, sessionId);
  return (
    <div className="flex h-full flex-col">
      <StatusBar sessionId={sessionId} status={status} stats={tree.stats} />
      <div className="flex-1">
        <ExecutionTree tree={tree} />
      </div>
    </div>
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
