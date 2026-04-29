import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useExecutionTree } from '../src/hooks/useExecutionTree';

/**
 * Pins the snapshot handshake gate (codex P1 finding on PR #448).
 *
 * The race the gate fixes:
 *
 *   1. Tab opens WS, sends {@code snapshot.request}.
 *   2. Daemon broadcasts a fresh live event (e.g. eventId=120) BEFORE its
 *      snapshot handler runs ctx.send. Both messages are queued on the
 *      same socket; whichever {@code send()} ran first is delivered first.
 *   3. If the live event lands first, the reducer's lastEventId watermark
 *      jumps to 120. The subsequent snapshot.response, with envelopes
 *      1..120, is then entirely dedup'd by the watermark — and the tree
 *      misses every structural node it was supposed to recover.
 *
 * The fix: while the hook is waiting for snapshot.response, every live
 * envelope is QUEUED instead of dispatched. After snapshot replay lands,
 * the queue is drained in arrival order through the reducer; events that
 * fall inside the snapshot's range are correctly dedup'd, events newer
 * than the snapshot are applied normally.
 *
 * The race is impossible to deterministically reproduce against a real
 * Jetty server in unit tests, so this file mocks {@code WebSocket} and
 * drives the message order from the test.
 */

interface FakeMessageHandler {
  (event: { data: string }): void;
}

class FakeWebSocket {
  static last: FakeWebSocket | null = null;

  url: string;
  // Captured handlers — onopen/onmessage are assigned by the hook's
  // useEffect, and the test invokes them directly to simulate ordering.
  onopen: (() => void) | null = null;
  onmessage: FakeMessageHandler | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  // Frames the hook sent on this socket — used to confirm
  // snapshot.request was dispatched before any deliver() calls.
  sent: string[] = [];

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.last = this;
  }

  // Browsers' WebSocket constructor immediately tries to connect; the
  // real DOM API would fire onopen on success. The hook synchronously
  // sets onopen during the same tick, so the test triggers open()
  // explicitly to keep the order-of-events assertable.
  open(): void {
    this.onopen?.();
  }

  deliver(payload: object): void {
    this.onmessage?.({ data: JSON.stringify(payload) });
  }

  send(message: string): void {
    this.sent.push(message);
  }

  close(): void {
    this.onclose?.();
  }
}

describe('useExecutionTree snapshot handshake gate (P1)', () => {
  beforeEach(() => {
    vi.stubGlobal('WebSocket', FakeWebSocket);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    FakeWebSocket.last = null;
  });

  /** Builds a DaemonEventEnvelope JSON object the way the bridge would. */
  function envelope(
    eventId: number,
    sessionId: string,
    method: string,
    params: Record<string, unknown>,
  ): Record<string, unknown> {
    return {
      eventId,
      sessionId,
      receivedAt: '2026-04-29T00:00:00.000Z',
      event: { method, params },
    };
  }

  it('queues a live envelope that arrives before snapshot.response and applies it after replay', async () => {
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;

    act(() => ws.open());
    // Confirm the hook sent snapshot.request on open — that's the gate
    // signal: until snapshot.response arrives, live envelopes queue.
    expect(ws.sent[0]).toContain('"method":"snapshot.request"');

    // A live event arrives first (race scenario): tool_use with
    // eventId=10. Without the gate this would advance the reducer's
    // watermark to 10 and dedup every snapshot envelope.
    act(() =>
      ws.deliver(
        envelope(10, 'sess-1', 'stream.tool_use', { id: 't1', name: 'bash' }),
      ),
    );

    // Tree is still empty: the gate is buffering, not dispatching.
    expect(result.current.tree.rootNodes).toHaveLength(0);
    expect(result.current.tree.lastEventId).toBe(0);

    // Now snapshot.response arrives with the structural events the tab
    // missed (session_started + turn_started). lastEventId in the
    // snapshot is 5; the queued live tool_use (eventId=10) is newer and
    // SHOULD survive the dedup gate after the flush.
    act(() =>
      ws.deliver({
        method: 'snapshot.response',
        sessionId: 'sess-1',
        lastEventId: 5,
        events: [
          envelope(1, 'sess-1', 'stream.session_started', {
            sessionId: 'sess-1',
            model: 'claude-opus-4-7',
          }),
          envelope(5, 'sess-1', 'stream.turn_started', {
            requestId: 'r1',
            turnNumber: 1,
          }),
        ],
      }),
    );

    // After flush: session root + turn child + tool_use under the turn
    // (or under whatever the reducer chooses — without thinking it's
    // the turn). The key invariant: the QUEUED live event was NOT
    // dropped just because it raced ahead of snapshot.response.
    await waitFor(() => {
      expect(result.current.tree.rootNodes).toHaveLength(1);
    });
    const session = result.current.tree.rootNodes[0]!;
    expect(session.type).toBe('session');
    const turn = session.children[0]!;
    expect(turn.type).toBe('turn');
    const tool = turn.children.find((c) => c.type === 'tool');
    expect(tool).toBeDefined();
    expect(tool!.id).toBe('t1');
    // Watermark advanced past the queued live event — subsequent live
    // events with eventId<=10 dedup correctly.
    expect(result.current.tree.lastEventId).toBeGreaterThanOrEqual(10);
  });

  it('drops a queued live event whose eventId falls inside the snapshot range', async () => {
    // The dedup gate (eventId<=lastEventId) still works for queued
    // events — anything that was already in the snapshot doesn't get
    // double-applied when the queue flushes.
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;
    act(() => ws.open());

    // Live event with eventId=3 arrives during the wait. The snapshot
    // we'll send next includes events 1..5, so this 3 is inside the
    // range and should be dropped on flush.
    act(() =>
      ws.deliver(
        envelope(3, 'sess-1', 'stream.tool_use', {
          id: 'duplicate',
          name: 'bash',
        }),
      ),
    );

    act(() =>
      ws.deliver({
        method: 'snapshot.response',
        sessionId: 'sess-1',
        lastEventId: 5,
        events: [
          envelope(1, 'sess-1', 'stream.session_started', {
            sessionId: 'sess-1',
            model: 'claude-opus-4-7',
          }),
          envelope(2, 'sess-1', 'stream.turn_started', {
            requestId: 'r1',
            turnNumber: 1,
          }),
          envelope(3, 'sess-1', 'stream.tool_use', {
            id: 't-canonical',
            name: 'bash',
          }),
        ],
      }),
    );

    await waitFor(() => {
      expect(result.current.tree.rootNodes).toHaveLength(1);
    });
    const turn = result.current.tree.rootNodes[0]!.children[0]!;
    const tools = turn.children.filter((c) => c.type === 'tool');
    // Only the snapshot's canonical tool exists; the queued live tool
    // with the same eventId was dedup'd.
    expect(tools).toHaveLength(1);
    expect(tools[0]!.id).toBe('t-canonical');
  });

  it('after snapshot lands, subsequent live events flow normally', async () => {
    // Once the gate releases, the hook is back to plain dispatch — no
    // re-gating until the next reconnect.
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;
    act(() => ws.open());

    act(() =>
      ws.deliver({
        method: 'snapshot.response',
        sessionId: 'sess-1',
        lastEventId: 0,
        events: [],
      }),
    );

    act(() =>
      ws.deliver(
        envelope(1, 'sess-1', 'stream.session_started', {
          sessionId: 'sess-1',
          model: 'm',
        }),
      ),
    );
    act(() =>
      ws.deliver(
        envelope(2, 'sess-1', 'stream.turn_started', {
          requestId: 'r1',
          turnNumber: 1,
        }),
      ),
    );

    await waitFor(() => {
      expect(result.current.tree.rootNodes).toHaveLength(1);
    });
    expect(result.current.tree.lastEventId).toBe(2);
  });
});
