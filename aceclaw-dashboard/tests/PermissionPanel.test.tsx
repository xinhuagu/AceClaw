import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, screen, act, cleanup } from '@testing-library/react';
import { PermissionPanel, PERMISSION_TIMEOUT_MS } from '../src/components/PermissionPanel';
import type { ExecutionNode } from '../src/types/tree';

/**
 * Render-level tests for the PermissionPanel — issue #437.
 *
 * Three things matter end-to-end and aren't covered by reducer or hook
 * tests:
 *   1. Awaiting state renders Approve / Deny and wires their click to
 *      the respective callback with the request id.
 *   2. Keyboard shortcuts (A / D / Esc) invoke the same callbacks.
 *   3. CLI-resolved state shows "Approved/Denied via CLI", disables
 *      buttons (replaced by an indicator), and self-dismisses after
 *      its 1.5 s timer elapses.
 *
 * We use fake timers so the panel's countdown + auto-dismiss timers can
 * be advanced deterministically without making the suite slow.
 */

function awaitingNode(overrides: Partial<ExecutionNode> = {}): ExecutionNode {
  return {
    id: 't1',
    type: 'tool',
    status: 'paused',
    label: 'edit_file',
    children: [],
    awaitingInput: true,
    inputPrompt: 'edit_file wants to write to /tmp/foo.txt',
    permissionRequestId: 'perm-1',
    metadata: {
      permissionTool: 'edit_file',
      permissionRequestedAt: Date.now(),
    },
    ...overrides,
  };
}

describe('PermissionPanel — awaiting state', () => {
  beforeEach(() => {
    // Pin Date.now so the countdown ring's "elapsed" calc is stable
    // across the test. The panel reads its own clock via setInterval,
    // which we drive with vi.advanceTimersByTime where relevant.
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-04-30T12:00:00Z'));
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('renders Approve / Deny / Always Allow and routes clicks back with the requestId', () => {
    const onApprove = vi.fn();
    const onAlwaysAllow = vi.fn();
    const onDeny = vi.fn();
    const onDismiss = vi.fn();
    render(
      <PermissionPanel
        node={awaitingNode()}
        anchorX={100}
        anchorY={100}
        onApprove={onApprove}
        onAlwaysAllow={onAlwaysAllow}
        onDeny={onDeny}
        onDismiss={onDismiss}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /^approve permission$/i }));
    expect(onApprove).toHaveBeenCalledWith('perm-1');

    fireEvent.click(screen.getByRole('button', { name: /deny permission/i }));
    expect(onDeny).toHaveBeenCalledWith('perm-1');

    fireEvent.click(
      screen.getByRole('button', { name: /always allow this tool/i }),
    );
    expect(onAlwaysAllow).toHaveBeenCalledWith('perm-1');
  });

  it('extracts a path-like subject from the prompt and renders it as code', () => {
    render(
      <PermissionPanel
        node={awaitingNode()}
        anchorX={0}
        anchorY={0}
        onApprove={vi.fn()} onAlwaysAllow={vi.fn()}
        onDeny={vi.fn()}
        onDismiss={vi.fn()}
      />,
    );
    // "/tmp/foo.txt" is the longest path-like token in the prompt — the
    // splitter should pull it out so the user sees the file separately
    // from the verb phrase.
    const subject = screen.getByText('/tmp/foo.txt');
    expect(subject.tagName.toLowerCase()).toBe('code');
  });

  it('A / D / Esc keyboard shortcuts invoke the right callbacks', () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    const onDismiss = vi.fn();
    render(
      <PermissionPanel
        node={awaitingNode()}
        anchorX={0}
        anchorY={0}
        onApprove={onApprove} onAlwaysAllow={vi.fn()}
        onDeny={onDeny}
        onDismiss={onDismiss}
      />,
    );
    fireEvent.keyDown(window, { key: 'a' });
    expect(onApprove).toHaveBeenCalledWith('perm-1');
    fireEvent.keyDown(window, { key: 'd' });
    expect(onDeny).toHaveBeenCalledWith('perm-1');
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onDismiss).toHaveBeenCalledWith('perm-1');
  });

  it('auto-dismisses once the deadline elapses', () => {
    const onDismiss = vi.fn();
    render(
      <PermissionPanel
        node={awaitingNode()}
        anchorX={0}
        anchorY={0}
        onApprove={vi.fn()} onAlwaysAllow={vi.fn()}
        onDeny={vi.fn()}
        onDismiss={onDismiss}
      />,
    );
    // Deadline + a tick of the panel's 250 ms interval so the effect runs
    // with a "now" past the threshold.
    act(() => {
      vi.advanceTimersByTime(PERMISSION_TIMEOUT_MS + 300);
    });
    expect(onDismiss).toHaveBeenCalledWith('perm-1');
  });

  it('immediately calls onDismiss when mounted past the deadline (snapshot replay)', () => {
    // A tab opening mid-execution via snapshot replay receives a
    // permission.request whose `permissionRequestedAt` is already older
    // than the 120 s deadline. The panel must dismiss on first render
    // rather than waiting up to 250 ms for the next interval tick —
    // otherwise an obviously-stale panel flashes on screen.
    const onDismiss = vi.fn();
    const stale = awaitingNode({
      metadata: {
        permissionTool: 'edit_file',
        permissionRequestedAt: Date.now() - PERMISSION_TIMEOUT_MS - 5_000,
      },
    });
    render(
      <PermissionPanel
        node={stale}
        anchorX={0}
        anchorY={0}
        onApprove={vi.fn()} onAlwaysAllow={vi.fn()}
        onDeny={vi.fn()}
        onDismiss={onDismiss}
      />,
    );
    expect(onDismiss).toHaveBeenCalledWith('perm-1');
  });

  it('secondary panels do not respond to A/D keyboard shortcuts', () => {
    // With parallel pending permissions, only the primary (most-recent)
    // panel binds the global keyboard shortcuts so a keystroke has a
    // single, predictable target. Secondary panels render and remain
    // click-interactive.
    const onApprove = vi.fn();
    render(
      <PermissionPanel
        node={awaitingNode()}
        anchorX={0}
        anchorY={0}
        onApprove={onApprove} onAlwaysAllow={vi.fn()}
        onDeny={vi.fn()}
        onDismiss={vi.fn()}
        primary={false}
      />,
    );
    fireEvent.keyDown(window, { key: 'a' });
    expect(onApprove).not.toHaveBeenCalled();
    // Click still works on a secondary panel — the user can answer via
    // the mouse even when keyboard ownership lies elsewhere.
    fireEvent.click(screen.getByRole('button', { name: /approve permission/i }));
    expect(onApprove).toHaveBeenCalledWith('perm-1');
  });

  it('with a primary AND a secondary mounted, A only fires the primary handler', () => {
    // Concurrent-mount regression test: a previous version routed every
    // keystroke to every mounted panel because the listener was bound
    // unconditionally. Verify that only the primary's handler runs when
    // both are alive at the same time, and the secondary stays silent.
    const primaryApprove = vi.fn();
    const secondaryApprove = vi.fn();
    const primaryNode = awaitingNode({
      id: 'tA',
      permissionRequestId: 'perm-A',
    });
    const secondaryNode = awaitingNode({
      id: 'tB',
      permissionRequestId: 'perm-B',
    });
    render(
      <>
        <PermissionPanel
          node={primaryNode}
          anchorX={0}
          anchorY={0}
          onApprove={primaryApprove} onAlwaysAllow={vi.fn()}
          onDeny={vi.fn()}
          onDismiss={vi.fn()}
          primary
        />
        <PermissionPanel
          node={secondaryNode}
          anchorX={400}
          anchorY={200}
          onApprove={secondaryApprove} onAlwaysAllow={vi.fn()}
          onDeny={vi.fn()}
          onDismiss={vi.fn()}
          primary={false}
        />
      </>,
    );
    fireEvent.keyDown(window, { key: 'a' });
    expect(primaryApprove).toHaveBeenCalledWith('perm-A');
    expect(secondaryApprove).not.toHaveBeenCalled();
  });
});

describe('PermissionPanel — CLI-resolved state', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-04-30T12:00:00Z'));
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  function cliResolvedNode(approved: boolean): ExecutionNode {
    return awaitingNode({
      status: approved ? 'completed' : 'failed',
      metadata: {
        permissionTool: 'edit_file',
        permissionRequestedAt: Date.now(),
        resolvedBy: 'cli',
        resolvedApproved: approved,
      },
    });
  }

  it('shows "Approved via CLI" and self-dismisses after the reveal window', () => {
    const onDismiss = vi.fn();
    render(
      <PermissionPanel
        node={cliResolvedNode(true)}
        anchorX={0}
        anchorY={0}
        onApprove={vi.fn()} onAlwaysAllow={vi.fn()}
        onDeny={vi.fn()}
        onDismiss={onDismiss}
      />,
    );
    expect(screen.getByText(/Approved via CLI/i)).toBeTruthy();
    // Approve button is replaced by the indicator — assert absence.
    expect(screen.queryByRole('button', { name: /approve permission/i })).toBeNull();

    // Reveal timer is 1.5 s; nothing should fire before that.
    act(() => {
      vi.advanceTimersByTime(1400);
    });
    expect(onDismiss).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(onDismiss).toHaveBeenCalledWith('perm-1');
  });

  it('shows "Denied via CLI" for the failed-tool path', () => {
    render(
      <PermissionPanel
        node={cliResolvedNode(false)}
        anchorX={0}
        anchorY={0}
        onApprove={vi.fn()} onAlwaysAllow={vi.fn()}
        onDeny={vi.fn()}
        onDismiss={vi.fn()}
      />,
    );
    expect(screen.getByText(/Denied via CLI/i)).toBeTruthy();
  });
});
