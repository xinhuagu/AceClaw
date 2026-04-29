import { describe, expect, it } from 'vitest';
import { parseEnvelope } from '../src/hooks/useExecutionTree';

/**
 * Tests for the WS frame validator that lives at the hook boundary. These
 * pin the contracts that protect the reducer:
 * - presence + type validation per known method (no more "steps: {}" crashes
 *   in addPlanSkeleton)
 * - discriminated 'known' vs 'unknown' result so callers can advance the
 *   watermark for forward-compat methods (Tier 2/3) without dispatching
 *   them through the reducer
 */

const ENV_BASE = {
  eventId: 1,
  sessionId: 'sess-1',
  receivedAt: '2026-04-29T00:00:00.000Z',
};

function frame(method: string, params: unknown): string {
  return JSON.stringify({ ...ENV_BASE, event: { method, params } });
}

describe('parseEnvelope: top-level shape', () => {
  it('rejects non-JSON', () => {
    expect(parseEnvelope('not json')).toBeNull();
  });

  it('rejects missing eventId', () => {
    expect(
      parseEnvelope(
        JSON.stringify({ sessionId: 's', receivedAt: 't', event: { method: 'stream.text', params: {} } }),
      ),
    ).toBeNull();
  });

  it('rejects an event payload that is not a plain object', () => {
    expect(
      parseEnvelope(JSON.stringify({ ...ENV_BASE, event: 'string-event' })),
    ).toBeNull();
  });
});

describe('parseEnvelope: forward-compat unknown methods', () => {
  it('returns kind:unknown so the caller can bump the watermark', () => {
    const result = parseEnvelope(frame('stream.swarm_started', { foo: 1 }));
    expect(result).not.toBeNull();
    expect(result?.kind).toBe('unknown');
    if (result?.kind === 'unknown') {
      expect(result.eventId).toBe(1);
      expect(result.sessionId).toBe('sess-1');
    }
  });
});

describe('parseEnvelope: per-method type guards (not just key presence)', () => {
  it('rejects stream.plan_created when steps is an object instead of an array', () => {
    // The whole point of this guard: addPlanSkeleton calls steps.map and
    // would otherwise crash the entire onmessage handler.
    expect(
      parseEnvelope(
        frame('stream.plan_created', { planId: 'p', steps: {} }),
      ),
    ).toBeNull();
  });

  it('accepts stream.plan_created when steps is a (possibly empty) array', () => {
    const result = parseEnvelope(
      frame('stream.plan_created', { planId: 'p', steps: [] }),
    );
    expect(result?.kind).toBe('known');
  });

  it('rejects permission.request missing requestId', () => {
    expect(
      parseEnvelope(
        frame('permission.request', { tool: 't', description: 'd' }),
      ),
    ).toBeNull();
  });

  it('rejects stream.tool_completed when durationMs is the wrong type', () => {
    // String "123" is a common JSON-bridge mishap and would skew the stats
    // counters if it slipped through.
    expect(
      parseEnvelope(
        frame('stream.tool_completed', {
          id: 't1',
          name: 'bash',
          durationMs: '123',
          isError: false,
        }),
      ),
    ).toBeNull();
  });

  it('accepts a fully-typed stream.tool_completed', () => {
    const result = parseEnvelope(
      frame('stream.tool_completed', {
        id: 't1',
        name: 'bash',
        durationMs: 12,
        isError: false,
      }),
    );
    expect(result?.kind).toBe('known');
  });
});

describe('parseEnvelope: methods without explicit guards still pass through', () => {
  it('stream.heartbeat has no validator and passes when shape is plausible', () => {
    const result = parseEnvelope(
      frame('stream.heartbeat', { phase: 'warmup' }),
    );
    expect(result?.kind).toBe('known');
  });
});
