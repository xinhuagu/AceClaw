/**
 * Pure-helper tests for the navigation math shared by ExecutionTree
 * and NavControls. Pinning these means wheel-zoom and button-zoom can
 * never drift apart silently — both go through these exact functions.
 */

import { describe, expect, it } from 'vitest';
import {
  ZOOM_MAX,
  ZOOM_MIN,
  centerOnNode,
  clampScale,
  fitToWindow,
  zoomBy,
} from '../src/components/viewport';

describe('clampScale', () => {
  it('passes through values inside the range', () => {
    expect(clampScale(0.5)).toBe(0.5);
    expect(clampScale(1)).toBe(1);
    expect(clampScale(2.5)).toBe(2.5);
  });
  it('clamps below ZOOM_MIN and above ZOOM_MAX', () => {
    expect(clampScale(0.05)).toBe(ZOOM_MIN);
    expect(clampScale(100)).toBe(ZOOM_MAX);
  });
});

describe('fitToWindow', () => {
  it('returns null for zero-size layout or container', () => {
    expect(fitToWindow(0, 100, 800, 600)).toBeNull();
    expect(fitToWindow(100, 0, 800, 600)).toBeNull();
    expect(fitToWindow(100, 100, 0, 600)).toBeNull();
    expect(fitToWindow(100, 100, 800, 0)).toBeNull();
  });

  it('caps scale at 1 even when the layout is much smaller than the container', () => {
    // 100×100 layout in a 800×600 container could fit at 5x, but we
    // never blow up tiny trees — caps at 100% so a single-node session
    // doesn't render at huge size.
    const v = fitToWindow(100, 100, 800, 600);
    expect(v?.scale).toBe(1);
    // Centred: container minus layout, halved.
    expect(v?.x).toBe(350);
    expect(v?.y).toBe(250);
  });

  it('scales down to fit a layout larger than the container, with the 90% margin', () => {
    // 1000×1000 layout, 800×600 container → fit by the height-limiting
    // axis: 600 / 1000 * 0.9 = 0.54.
    const v = fitToWindow(1000, 1000, 800, 600);
    expect(v?.scale).toBeCloseTo(0.54, 5);
    // Both coordinates are container midpoints minus half the scaled
    // layout dimension.
    expect(v?.x).toBeCloseTo((800 - 1000 * 0.54) / 2, 5);
    expect(v?.y).toBeCloseTo((600 - 1000 * 0.54) / 2, 5);
  });

  it('clamps scale to ZOOM_MIN when the layout is enormous', () => {
    // Tiny container, huge layout → would compute a near-zero scale,
    // but ZOOM_MIN floors it.
    const v = fitToWindow(100_000, 100_000, 100, 100);
    expect(v?.scale).toBe(ZOOM_MIN);
  });
});

describe('centerOnNode', () => {
  it('puts the node at the container midpoint for the given scale', () => {
    const v = centerOnNode(500, 500, 1, 800, 600);
    // After applying transform: screenX = v.x + node.x * scale.
    //   v.x + 500*1 = 800/2 → v.x = -100.
    //   v.y + 500*1 = 600/2 → v.y = -200.
    expect(v).toEqual({ scale: 1, x: -100, y: -200 });
  });

  it('honours the current scale (scale 0.5 halves the node-to-center distance)', () => {
    const v = centerOnNode(1000, 1000, 0.5, 800, 600);
    // v.x + 1000*0.5 = 400 → v.x = -100.
    // v.y + 1000*0.5 = 300 → v.y = -200.
    expect(v).toEqual({ scale: 0.5, x: -100, y: -200 });
  });
});

describe('zoomBy', () => {
  it('zooms in around container centre and translates so centre stays put', () => {
    // Starting at scale 1, viewport at origin, container 800×600.
    // Centre is (400, 300). Zoom in 2x → new scale 2.
    // Centre invariance: (cx - x) / scale should be the same before and after.
    const next = zoomBy({ x: 0, y: 0, scale: 1 }, 2, 800, 600);
    expect(next.scale).toBe(2);
    // Before: (400 - 0)/1 = 400.  After: (400 - x)/2 should = 400, so x = -400.
    expect(next.x).toBe(-400);
    expect(next.y).toBe(-300);
  });

  it('returns the same viewport when zoom would clamp (no change)', () => {
    const at_max: { x: number; y: number; scale: number } = { x: 10, y: 20, scale: ZOOM_MAX };
    const next = zoomBy(at_max, 2, 800, 600);
    // clampScale(ZOOM_MAX * 2) === ZOOM_MAX, no zoom happens, identity returned.
    expect(next).toBe(at_max);
  });

  it('zooming in then out by the inverse factor returns to (almost) original', () => {
    const start = { x: 50, y: 75, scale: 1 };
    const zoomed = zoomBy(start, 1.25, 800, 600);
    const back = zoomBy(zoomed, 1 / 1.25, 800, 600);
    expect(back.scale).toBeCloseTo(start.scale, 10);
    expect(back.x).toBeCloseTo(start.x, 10);
    expect(back.y).toBeCloseTo(start.y, 10);
  });
});

