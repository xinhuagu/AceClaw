import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useExecutionTree } from '../src/hooks/useExecutionTree';

/**
 * Tests for the WS hook's outbound command + permission-resolution APIs
 * added by issue #437. Three behaviours pinned here:
 *
 *   1. {@code sendCommand} writes JSON onto the socket once the WS is
 *      open, so PermissionPanel's Approve/Deny click reaches the daemon
 *      as a {@code permission.response} frame.
 *   2. Commands sent BEFORE the socket opens are buffered and flushed
 *      in arrival order on {@code onopen}. The user can click Approve
 *      while the tab is reconnecting and the click still lands.
 *   3. {@code resolvePermission} mutates the local tree without round-
 *      tripping through the WS — the panel disappears immediately
 *      instead of waiting for the daemon to echo a tool_completed.
 *
 * The snapshot-handshake gate is exercised by a sibling file
 * ({@code useExecutionTreeSnapshotGate.test.tsx}); we share its
 * {@link FakeWebSocket} pattern but keep the suites separate so each
 * stays focused on one acceptance area.
 */

interface FakeMessageHandler {
  (event: { data: string }): void;
}

class FakeWebSocket {
  static last: FakeWebSocket | null = null;
  static OPEN = 1;

  url: string;
  // Mirrors the real DOM contract — readyState toggles to OPEN on open()
  // so the hook's synchronous-send path works the same as in production.
  readyState: number = 0;
  onopen: (() => void) | null = null;
  onmessage: FakeMessageHandler | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  sent: string[] = [];

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.last = this;
  }

  open(): void {
    this.readyState = FakeWebSocket.OPEN;
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

describe('useExecutionTree permission flow (issue #437)', () => {
  beforeEach(() => {
    // The WebSocket constants the hook reads (WebSocket.OPEN) need to be
    // present on the stub or the readyState gate falls back to "buffer
    // everything", masking the immediate-send case.
    vi.stubGlobal('WebSocket', FakeWebSocket);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    FakeWebSocket.last = null;
  });

  function envelope(
    eventId: number,
    sessionId: string,
    method: string,
    params: Record<string, unknown>,
  ): Record<string, unknown> {
    return {
      eventId,
      sessionId,
      receivedAt: '2026-04-30T00:00:00.000Z',
      event: { method, params },
    };
  }

  it('sendCommand writes a permission.response frame once open', () => {
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;
    act(() => ws.open());

    let immediate: boolean | undefined;
    act(() => {
      immediate = result.current.sendCommand({
        method: 'permission.response',
        params: { requestId: 'perm-1', approved: true },
      });
    });
    expect(immediate).toBe(true);

    // Last frame on the wire (after the handshake's snapshot.request) is
    // the permission.response. JSON-string compare on the parsed object
    // so we don't depend on key order.
    const parsed = JSON.parse(ws.sent[ws.sent.length - 1]!) as {
      method: string;
      params: { requestId: string; approved: boolean };
    };
    expect(parsed.method).toBe('permission.response');
    expect(parsed.params.requestId).toBe('perm-1');
    expect(parsed.params.approved).toBe(true);
  });

  it('buffers commands sent before the socket opens and flushes them on open', () => {
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;
    // Socket NOT opened yet — readyState is 0, so the first send must
    // queue. Without the queue, pre-open clicks would silently no-op.
    let immediate: boolean | undefined;
    act(() => {
      immediate = result.current.sendCommand({
        method: 'permission.response',
        params: { requestId: 'perm-1', approved: false },
      });
    });
    expect(immediate).toBe(false);
    // Nothing on the wire yet.
    expect(ws.sent.filter((s) => s.includes('permission.response'))).toHaveLength(0);

    // Open the socket — the queue should drain. snapshot.request goes
    // first (the handshake), then the buffered permission.response.
    act(() => ws.open());
    const permFrames = ws.sent.filter((s) => s.includes('permission.response'));
    expect(permFrames).toHaveLength(1);
    expect(JSON.parse(permFrames[0]!)).toMatchObject({
      method: 'permission.response',
      params: { requestId: 'perm-1', approved: false },
    });
  });

  it('resolvePermission flips a paused tool node optimistically without going through the WS', async () => {
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;
    act(() => ws.open());

    // Build the tree state via snapshot replay so we don't have to spin
    // up a separate dispatch path. session → turn → paused tool.
    act(() =>
      ws.deliver({
        method: 'snapshot.response',
        sessionId: 'sess-1',
        lastEventId: 0,
        events: [
          envelope(1, 'sess-1', 'stream.session_started', {
            sessionId: 'sess-1',
            model: 'claude-opus-4-7',
          }),
          envelope(2, 'sess-1', 'stream.turn_started', {
            requestId: 'r1',
            turnNumber: 1,
          }),
          envelope(3, 'sess-1', 'stream.tool_use', { id: 't1', name: 'edit_file' }),
          envelope(4, 'sess-1', 'permission.request', {
            tool: 'edit_file',
            description: 'edit_file wants to write to /tmp/foo.txt',
            requestId: 'perm-1',
          }),
        ],
      }),
    );

    await waitFor(() => {
      const tool = result.current.tree.rootNodes[0]!.children[0]!.children[0]!;
      expect(tool.awaitingInput).toBe(true);
    });

    // Optimistic local resolve — does NOT push anything onto the wire
    // (App.tsx's handler is the one that pairs sendCommand with
    // resolvePermission; the hook leaves them split so the panel can
    // exercise either path independently).
    const sentBefore = ws.sent.length;
    act(() => result.current.resolvePermission('perm-1', true));
    expect(ws.sent.length).toBe(sentBefore);

    const tool = result.current.tree.rootNodes[0]!.children[0]!.children[0]!;
    expect(tool.status).toBe('running');
    expect(tool.awaitingInput).toBeUndefined();
    expect(tool.metadata?.['resolvedBy']).toBe('browser');
  });
});
