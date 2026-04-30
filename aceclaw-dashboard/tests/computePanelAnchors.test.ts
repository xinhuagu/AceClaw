import { describe, expect, it } from 'vitest';
import {
  computePanelAnchors,
  type ViewportTransform,
} from '../src/components/ExecutionTree';
import type { LayoutNode } from '../src/types/tree';

/**
 * Pins the panel-collision-avoidance logic for parallel pending
 * permissions (issue #437, codex re-review B1). Dagre's LR layout puts
 * sibling parallel tools ~94 px apart vertically (NODE_HEIGHT 44 +
 * nodesep 50), but a panel renders at ~170 px tall — two siblings
 * asking for permission would overlap by ~75 px without staggering.
 * The helper sorts awaiting nodes by Y and cascades lower panels
 * downward so each keeps PANEL_STACK_MIN_HEIGHT of vertical real estate.
 */

const PANEL_STACK_MIN_HEIGHT = 170;
const PANEL_STACK_GAP = 12;

function node(id: string, x: number, y: number): LayoutNode {
  return {
    id,
    type: 'tool',
    status: 'paused',
    label: id,
    children: [],
    awaitingInput: true,
    permissionRequestId: `perm-${id}`,
    x,
    y,
    width: 180,
    height: 44,
  };
}

const identityViewport: ViewportTransform = { x: 0, y: 0, scale: 1 };

describe('computePanelAnchors', () => {
  it('passes through a single panel anchor unchanged', () => {
    const result = computePanelAnchors([node('t1', 200, 100)], identityViewport);
    expect(result).toHaveLength(1);
    // X = node.x + width/2 + 12 (PANEL_HORIZONTAL_OFFSET) = 200 + 90 + 12.
    expect(result[0]!.x).toBe(302);
    // Y = node.y at scale 1 = 100.
    expect(result[0]!.y).toBe(100);
  });

  it('cascades a second panel below the first when their Y would overlap', () => {
    // Two parallel tools ~94 px apart — the realistic dagre LR layout
    // for sibling parallel tool calls. The bug this guards against:
    // both panels at Y=100 / Y=194 would overlap by ~76 px because each
    // panel needs ~170 px of vertical room.
    const result = computePanelAnchors(
      [node('t1', 200, 100), node('t2', 200, 194)],
      identityViewport,
    );
    expect(result).toHaveLength(2);
    expect(result[0]!.y).toBe(100);
    // The second panel must be at least PANEL_STACK_MIN_HEIGHT + GAP
    // below the first to clear it.
    expect(result[1]!.y).toBeGreaterThanOrEqual(
      result[0]!.y + PANEL_STACK_MIN_HEIGHT + PANEL_STACK_GAP,
    );
  });

  it('preserves natural Y when panels are far enough apart already', () => {
    // Two panels 250 px apart vertically — already wider than
    // PANEL_STACK_MIN_HEIGHT (170). No cascading needed.
    const result = computePanelAnchors(
      [node('t1', 200, 100), node('t2', 200, 350)],
      identityViewport,
    );
    expect(result[0]!.y).toBe(100);
    expect(result[1]!.y).toBe(350);
  });

  it('sorts by Y so a tool higher in the layout always gets the natural slot', () => {
    // Pass nodes in arbitrary order; the helper should sort them by Y
    // before deciding stacking. Tests that the stack starts from the
    // top, not from the array's first element.
    const result = computePanelAnchors(
      [node('low', 200, 300), node('high', 200, 100)],
      identityViewport,
    );
    expect(result[0]!.node.id).toBe('high');
    expect(result[0]!.y).toBe(100);
    expect(result[1]!.node.id).toBe('low');
  });

  it('cascades three colliding panels into a clean vertical stack', () => {
    const result = computePanelAnchors(
      [
        node('t1', 200, 100),
        node('t2', 200, 150),
        node('t3', 200, 200),
      ],
      identityViewport,
    );
    expect(result).toHaveLength(3);
    // Each subsequent panel must clear the previous one.
    for (let i = 1; i < result.length; i += 1) {
      expect(result[i]!.y).toBeGreaterThanOrEqual(
        result[i - 1]!.y + PANEL_STACK_MIN_HEIGHT + PANEL_STACK_GAP,
      );
    }
  });

  it('applies viewport pan/zoom to anchor coordinates', () => {
    const viewport: ViewportTransform = { x: 50, y: 30, scale: 2 };
    const result = computePanelAnchors([node('t1', 200, 100)], viewport);
    // X = pan.x + (node.x + width/2) * scale + offset = 50 + (200+90)*2 + 12 = 642.
    expect(result[0]!.x).toBe(642);
    // Y = pan.y + node.y * scale = 30 + 100*2 = 230.
    expect(result[0]!.y).toBe(230);
  });
});
