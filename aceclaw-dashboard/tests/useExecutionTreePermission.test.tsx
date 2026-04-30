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

  /**
   * If set, every send() call throws this error instead of recording.
   * Used by the flush-failure test to simulate a socket transitioning
   * to CLOSING mid-flush.
   */
  throwOnSend: Error | null = null;

  send(message: string): void {
    if (this.throwOnSend) throw this.throwOnSend;
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

  it('sendCommand can carry remember:true for the Always Allow button', () => {
    // The "Always Allow this tool this session" button sends a
    // permission.response with remember=true so the daemon stops asking
    // about this tool for the rest of the session. Verify the flag
    // makes it onto the wire — the optional shape was never the
    // default, and silently dropping it would defeat the feature.
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;
    act(() => ws.open());
    act(() => {
      result.current.sendCommand({
        method: 'permission.response',
        params: { requestId: 'perm-1', approved: true, remember: true },
      });
    });
    const parsed = JSON.parse(ws.sent[ws.sent.length - 1]!) as {
      params: { remember?: boolean };
    };
    expect(parsed.params.remember).toBe(true);
  });

  it('sendCommand writes a permission.response frame once open', () => {
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;
    act(() => ws.open());

    let immediate: 'sent' | 'queued' | 'dropped' | undefined;
    act(() => {
      immediate = result.current.sendCommand({
        method: 'permission.response',
        params: { requestId: 'perm-1', approved: true },
      });
    });
    expect(immediate).toBe('sent');

    // Last frame on the wire (after the handshake's snapshot.request) is
    // the permission.response. JSON-string compare on the parsed object
    // so we don't depend on key order.
    const parsed = JSON.parse(ws.sent[ws.sent.length - 1]!) as {
      method: string;
      params: { requestId: string; approved: boolean; sessionId?: string };
    };
    expect(parsed.method).toBe('permission.response');
    expect(parsed.params.requestId).toBe('perm-1');
    expect(parsed.params.approved).toBe(true);
    // The hook MUST inject sessionId — the daemon's cross-session guard
    // (#433 / #454) drops responses without it. Manual testing on the
    // first ship of #437 caught a silent-drop bug here: every browser
    // approval was rejected server-side because the dashboard never
    // stamped sessionId. Pin it.
    expect(parsed.params.sessionId).toBe('sess-1');
  });

  it('auto-injects sessionId even when the caller omitted it', () => {
    // Defensive: if a caller's params lack sessionId (the typical
    // App.tsx path), the hook fills it in from its own sessionId prop
    // before the frame leaves the socket. Without this, a daemon with
    // the cross-session guard silently drops every approval.
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-other'),
    );
    const ws = FakeWebSocket.last!;
    act(() => ws.open());
    act(() => {
      result.current.sendCommand({
        method: 'permission.response',
        // Notice: no sessionId in the caller's payload at all.
        params: { requestId: 'perm-1', approved: false },
      });
    });
    const parsed = JSON.parse(ws.sent[ws.sent.length - 1]!) as {
      params: { sessionId?: string };
    };
    expect(parsed.params.sessionId).toBe('sess-other');
  });

  it('buffers commands sent before the socket opens and flushes them on open', () => {
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;
    // Socket NOT opened yet — readyState is 0, so the first send must
    // queue. Without the queue, pre-open clicks would silently no-op.
    let immediate: 'sent' | 'queued' | 'dropped' | undefined;
    act(() => {
      immediate = result.current.sendCommand({
        method: 'permission.response',
        params: { requestId: 'perm-1', approved: false },
      });
    });
    expect(immediate).toBe('queued');
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

  it('returns "dropped" when the pre-open buffer is full and refuses to queue more', () => {
    // The 33rd command sent before the socket opens must be rejected
    // rather than silently dropped — App.tsx uses this signal to skip
    // the optimistic local resolve so the panel keeps rendering and the
    // daemon doesn't get a divergent client view.
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const dispositions: Array<'sent' | 'queued' | 'dropped'> = [];
    act(() => {
      for (let i = 0; i < 33; i += 1) {
        dispositions.push(
          result.current.sendCommand({
            method: 'permission.response',
            // Distinct ids so the dedup path doesn't collapse them.
            params: { requestId: `perm-${i}`, approved: true },
          }),
        );
      }
    });
    // First 32 queue, the 33rd drops.
    expect(dispositions.slice(0, 32).every((d) => d === 'queued')).toBe(true);
    expect(dispositions[32]).toBe('dropped');
  });

  it('preserves unsent commands when ws.send throws mid-flush', () => {
    // Real bug from review (codex P2): the flush loop cleared the
    // queue BEFORE iterating, so a ws.send throw mid-flush (socket
    // transitioning to CLOSING during reconnect churn) silently
    // dropped the remaining commands. With optimistic local resolves
    // already applied for those clicks, the daemon would never get
    // the decision and would 120s-timeout the tool.
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;
    // Queue three commands while socket is closed.
    act(() => {
      result.current.sendCommand({
        method: 'permission.response',
        params: { requestId: 'perm-1', approved: true },
      });
      result.current.sendCommand({
        method: 'permission.response',
        params: { requestId: 'perm-2', approved: false },
      });
      result.current.sendCommand({
        method: 'permission.response',
        params: { requestId: 'perm-3', approved: true },
      });
    });
    // Flip send to throw on the SECOND call: snapshot.request goes
    // through (call 1), perm-1 goes through (call 2 — but throws
    // because of our trigger), so set throwOnSend BEFORE perm-2 by
    // counting via a custom wrapper.
    const originalSend = ws.send.bind(ws);
    let callCount = 0;
    ws.send = (message: string) => {
      callCount += 1;
      // Throw on the perm-2 attempt (call sequence: snapshot.request,
      // perm-1, perm-2). Specifically throw when the message is
      // perm-2's response.
      if (message.includes('"requestId":"perm-2"')) {
        throw new Error('socket CLOSING');
      }
      originalSend(message);
    };
    // Open the socket — flush runs, perm-2 throws, perm-3 should
    // remain queued.
    act(() => ws.open());
    // perm-1 made it to the wire; perm-3 should not have, and should
    // remain in the pendingSendRef for next flush.
    const sent = ws.sent.filter((s) => s.includes('permission.response'));
    expect(sent.some((s) => s.includes('"requestId":"perm-1"'))).toBe(true);
    expect(sent.some((s) => s.includes('"requestId":"perm-3"'))).toBe(false);
    // Now restore send and trigger a flush by sending one more
    // command — sendCommand sees socket OPEN so writes immediately,
    // but the previously-failed perm-2/perm-3 are still queued.
    // Verify by inspecting that the next reconnect would flush them
    // (we close + open to trigger).
    ws.send = originalSend;
    act(() => ws.close());
    // New socket on reconnect:
    act(() => {
      const next = FakeWebSocket.last!;
      next.open();
    });
    const sentAfter = FakeWebSocket.last!.sent.filter((s) =>
      s.includes('permission.response'),
    );
    // perm-2 and perm-3 land on the new socket.
    expect(sentAfter.some((s) => s.includes('"requestId":"perm-2"'))).toBe(true);
    expect(sentAfter.some((s) => s.includes('"requestId":"perm-3"'))).toBe(true);
  });

  it('dedups queued permission.response by requestId so back-to-back clicks collapse', () => {
    // User clicks Approve, then Deny, while the WS is reconnecting.
    // Both queue. On flush the daemon's first-response-wins will pick
    // whichever the bridge happened to deliver first — but on a daemon
    // without #433 the second click might never land. Replace the
    // earlier entry instead so the LATEST decision is what flushes.
    const { result } = renderHook(() =>
      useExecutionTree('ws://test/ws', 'sess-1'),
    );
    const ws = FakeWebSocket.last!;
    act(() => {
      result.current.sendCommand({
        method: 'permission.response',
        params: { requestId: 'perm-1', approved: true },
      });
      result.current.sendCommand({
        method: 'permission.response',
        params: { requestId: 'perm-1', approved: false },
      });
    });
    act(() => ws.open());
    const permFrames = ws.sent
      .filter((s) => s.includes('permission.response'))
      .map((s) => JSON.parse(s) as { params: { approved: boolean } });
    expect(permFrames).toHaveLength(1);
    expect(permFrames[0]!.params.approved).toBe(false);
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

    // Tool may live under a synthetic thinking parent (no
    // stream.thinking arrived) so walk the tree by id rather than
    // hard-coding the depth.
    function findTool(): import('../src/types/tree').ExecutionNode | null {
      function search(
        nodes: readonly import('../src/types/tree').ExecutionNode[],
      ): import('../src/types/tree').ExecutionNode | null {
        for (const n of nodes) {
          if (n.id === 't1') return n;
          const f = search(n.children);
          if (f) return f;
        }
        return null;
      }
      return search(result.current.tree.rootNodes);
    }

    await waitFor(() => {
      expect(findTool()?.awaitingInput).toBe(true);
    });

    // Optimistic local resolve — does NOT push anything onto the wire
    // (App.tsx's handler is the one that pairs sendCommand with
    // resolvePermission; the hook leaves them split so the panel can
    // exercise either path independently).
    const sentBefore = ws.sent.length;
    act(() => result.current.resolvePermission('perm-1', true));
    expect(ws.sent.length).toBe(sentBefore);

    const tool = findTool()!;
    expect(tool.status).toBe('running');
    expect(tool.awaitingInput).toBeUndefined();
    expect(tool.metadata?.['resolvedBy']).toBe('browser');
  });
});
