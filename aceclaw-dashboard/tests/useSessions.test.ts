import { describe, expect, it } from 'vitest';
import { parseSessionsListResult } from '../src/hooks/useSessions';

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
});
