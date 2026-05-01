import { describe, expect, it } from 'vitest';
import {
  isCronSessionId,
  mergeSnapshot,
  parseSessionsListResult,
  sessionInfoFromSessionStarted,
} from '../src/hooks/useSessions';
import type { SessionInfo } from '../src/hooks/useSessions';

const session = (overrides: Partial<SessionInfo> & { sessionId: string }): SessionInfo => ({
  sessionId: overrides.sessionId,
  projectPath: overrides.projectPath ?? '/p',
  createdAt: overrides.createdAt ?? '2026-04-29T08:00:00.000Z',
  active: overrides.active ?? true,
  ...(overrides.model !== undefined ? { model: overrides.model } : {}),
});

/**
 * Tests for the sessions.list result parser. Hook behaviour (the WebSocket
 * subscribe + delta path) is integration-level and lives in #438; here we
 * pin the boundary that decides which entries the sidebar trusts.
 */

describe('parseSessionsListResult', () => {
  it('returns null when sessions field is missing', () => {
    expect(parseSessionsListResult({})).toBeNull();
  });

  it('returns null when sessions is not an array', () => {
    expect(parseSessionsListResult({ sessions: {} })).toBeNull();
    expect(parseSessionsListResult({ sessions: 'foo' })).toBeNull();
  });

  it('returns the list when every entry has the expected shape', () => {
    const result = parseSessionsListResult({
      sessions: [
        {
          sessionId: 's1',
          projectPath: '/a/b',
          createdAt: '2026-04-29T08:00:00.000Z',
          active: true,
        },
        {
          sessionId: 's2',
          projectPath: '/x/y',
          createdAt: '2026-04-29T07:00:00.000Z',
          active: false,
        },
      ],
    });
    expect(result).toHaveLength(2);
    expect(result?.[0]!.sessionId).toBe('s1');
    expect(result?.[1]!.active).toBe(false);
  });

  it('drops entries with malformed types instead of failing the whole list', () => {
    // sessionId: number, missing active flag, projectPath wrong type.
    const result = parseSessionsListResult({
      sessions: [
        {
          sessionId: 's1',
          projectPath: '/a/b',
          createdAt: '2026-04-29T08:00:00.000Z',
          active: true,
        },
        { sessionId: 42, projectPath: '/c/d', createdAt: 't', active: true },
        { sessionId: 's3', projectPath: '/e/f', createdAt: 't' /* no active */ },
        { sessionId: 's4', projectPath: 99, createdAt: 't', active: false },
      ],
    });
    // Only the first row is well-formed; the rest are dropped silently so a
    // bad daemon push doesn't take the sidebar down.
    expect(result).toHaveLength(1);
    expect(result?.[0]!.sessionId).toBe('s1');
  });

  it('tolerates extra forward-compat fields without dropping the row', () => {
    const result = parseSessionsListResult({
      sessions: [
        {
          sessionId: 's1',
          projectPath: '/a',
          createdAt: 't',
          active: true,
          // Future-tier field — should not affect parsing.
          tier: 3,
        },
      ],
    });
    expect(result).toHaveLength(1);
  });

  it('captures the model field when present and well-typed', () => {
    const result = parseSessionsListResult({
      sessions: [
        {
          sessionId: 's1',
          projectPath: '/a',
          createdAt: 't',
          active: true,
          model: 'claude-opus-4-7',
        },
      ],
    });
    expect(result).toHaveLength(1);
    expect(result?.[0]!.model).toBe('claude-opus-4-7');
  });

  it('omits model when the field is missing or wrong-typed (older daemons)', () => {
    const result = parseSessionsListResult({
      sessions: [
        // No model field — backward-compat with daemons that don't emit it.
        { sessionId: 's1', projectPath: '/a', createdAt: 't', active: true },
        // Wrong type — should be dropped silently, but the row stays.
        { sessionId: 's2', projectPath: '/b', createdAt: 't', active: true, model: 42 },
      ],
    });
    expect(result).toHaveLength(2);
    expect(result?.[0]!.model).toBeUndefined();
    expect(result?.[1]!.model).toBeUndefined();
  });
});

describe('mergeSnapshot', () => {
  it('returns the snapshot unchanged when local state is empty', () => {
    const snap = [session({ sessionId: 's1' }), session({ sessionId: 's2' })];
    const merged = mergeSnapshot([], snap);
    expect(merged.map((s) => s.sessionId).sort()).toEqual(['s1', 's2']);
  });

  it('drops local-only sessions that the snapshot omits (post-reconnect heal)', () => {
    // After a disconnect we may have missed a stream.session_ended for s2.
    // The fresh snapshot is authoritative on membership — keeping s2 here
    // would leave a permanent stale "active" row that never self-corrects.
    const local = [
      session({ sessionId: 's2', createdAt: '2026-04-29T09:00:00.000Z', active: true }),
    ];
    const snap = [session({ sessionId: 's1', createdAt: '2026-04-29T08:00:00.000Z' })];
    const merged = mergeSnapshot(local, snap);
    expect(merged.map((s) => s.sessionId)).toEqual(['s1']);
  });

  it("preserves a locally-observed end (active=false) over a stale snapshot active=true", () => {
    // Race: session_ended for s1 arrived locally before sessions.list reply.
    const local = [session({ sessionId: 's1', active: false })];
    const snap = [session({ sessionId: 's1', active: true })];
    const merged = mergeSnapshot(local, snap);
    expect(merged).toHaveLength(1);
    expect(merged[0]!.active).toBe(false);
  });

  it('lets the snapshot resolve a stale local active=true (snapshot says ended)', () => {
    // Inverse race — local thinks active, snapshot is authoritative on ended.
    const local = [session({ sessionId: 's1', active: true })];
    const snap = [session({ sessionId: 's1', active: false })];
    const merged = mergeSnapshot(local, snap);
    expect(merged[0]!.active).toBe(false);
  });

  it('uses snapshot for immutable fields (projectPath, model, createdAt) on overlap', () => {
    const local = [
      session({
        sessionId: 's1',
        projectPath: '(unknown)', // session_started fallback
        createdAt: '2026-04-29T09:00:00.000Z', // client clock
        active: true,
      }),
    ];
    const snap = [
      session({
        sessionId: 's1',
        projectPath: '/real/path',
        createdAt: '2026-04-29T08:59:59.000Z',
        active: true,
        model: 'claude-opus-4-7',
      }),
    ];
    const merged = mergeSnapshot(local, snap);
    expect(merged[0]!.projectPath).toBe('/real/path');
    expect(merged[0]!.model).toBe('claude-opus-4-7');
    expect(merged[0]!.createdAt).toBe('2026-04-29T08:59:59.000Z');
  });
});

describe('sessionInfoFromSessionStarted (issue #452)', () => {
  it('reads projectPath from the event payload when the daemon emits it', () => {
    // The fix this guards: daemons ≥ #452 include projectPath on
    // stream.session_started so the sidebar shows the real path
    // immediately, instead of falling back to "(unknown)" until the
    // next sessions.list refresh.
    const row = sessionInfoFromSessionStarted({
      sessionId: 's1',
      model: 'claude-opus-4-7',
      timestamp: '2026-04-30T10:00:00.000Z',
      projectPath: '/Users/me/project',
    });
    expect(row).not.toBeNull();
    expect(row!.sessionId).toBe('s1');
    expect(row!.projectPath).toBe('/Users/me/project');
    expect(row!.model).toBe('claude-opus-4-7');
    expect(row!.createdAt).toBe('2026-04-30T10:00:00.000Z');
    expect(row!.active).toBe(true);
  });

  it('falls back to "(unknown)" when older daemons omit projectPath', () => {
    const row = sessionInfoFromSessionStarted({
      sessionId: 's1',
      model: 'claude-opus-4-7',
      timestamp: '2026-04-30T10:00:00.000Z',
    });
    expect(row!.projectPath).toBe('(unknown)');
  });

  it('falls back to "(unknown)" for blank or wrong-typed projectPath', () => {
    expect(
      sessionInfoFromSessionStarted({
        sessionId: 's1',
        projectPath: '',
      })!.projectPath,
    ).toBe('(unknown)');
    expect(
      sessionInfoFromSessionStarted({
        sessionId: 's1',
        projectPath: 42,
      })!.projectPath,
    ).toBe('(unknown)');
  });

  it('omits the model field when missing rather than stamping empty-string', () => {
    const row = sessionInfoFromSessionStarted({
      sessionId: 's1',
      projectPath: '/p',
    });
    expect(row!.model).toBeUndefined();
  });

  it('returns null when sessionId is missing', () => {
    expect(sessionInfoFromSessionStarted({ projectPath: '/p' })).toBeNull();
    expect(sessionInfoFromSessionStarted({ sessionId: 42 })).toBeNull();
  });

  it('uses the current time as createdAt when timestamp is missing', () => {
    const before = Date.now();
    const row = sessionInfoFromSessionStarted({ sessionId: 's1' });
    const stamped = Date.parse(row!.createdAt);
    const after = Date.now();
    // createdAt is a fresh ISO timestamp; sanity-check it lands in
    // the wall-clock window the call ran in.
    expect(stamped).toBeGreaterThanOrEqual(before);
    expect(stamped).toBeLessThanOrEqual(after);
  });
});

describe('isCronSessionId', () => {
  // The hook filters cron-as-session events out of the user-facing
  // SessionList (#459). Daemon emits cron run events under
  // "cron-{jobId}" so the dashboard's reducer can scaffold the tree
  // properly — but we don't want them showing up as user sessions.
  it('returns true for cron-prefixed sessionIds', () => {
    expect(isCronSessionId('cron-daily-backup')).toBe(true);
    expect(isCronSessionId('cron-x')).toBe(true);
  });

  it('returns false for regular sessionIds', () => {
    expect(isCronSessionId('s1')).toBe(false);
    expect(isCronSessionId('a-cron-prefix-but-not-at-start')).toBe(false);
    expect(isCronSessionId('Cron-uppercase')).toBe(false);
  });
});
