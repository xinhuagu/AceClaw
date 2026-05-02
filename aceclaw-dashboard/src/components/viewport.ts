/**
 * Pure viewport-math helpers shared by ExecutionTree, NavControls,
 * and Breadcrumb. Extracted so the navigation logic — fit-to-window,
 * jump-to-node, button-zoom — can be unit-tested without rendering
 * the SVG tree, and so the three consumers compute identical results
 * for the same inputs (no drift between "what the wheel does" vs
 * "what the + button does").
 */

import type { ExecutionNode, LayoutNode } from '../types/tree';

export interface ViewportTransform {
  /** Translate X applied to the SVG group (px in screen space). */
  x: number;
  /** Translate Y applied to the SVG group (px in screen space). */
  y: number;
  /** Uniform scale (1 = 100%). */
  scale: number;
}

/** Scale bounds, kept here so wheel-zoom and button-zoom can't disagree. */
export const ZOOM_MIN = 0.2;
export const ZOOM_MAX = 3;

/** Margin ratio used by fitToWindow — 0.9 leaves a 5% gutter on each side. */
const FIT_MARGIN = 0.9;

/** Clamps a candidate scale to {@link ZOOM_MIN}..{@link ZOOM_MAX}. */
export function clampScale(scale: number): number {
  return Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, scale));
}

/**
 * Computes the viewport that fits the entire tree into the container,
 * centred. Used by initial render and the Fit button. Caps scale at
 * 1 so a tiny tree doesn't get blown up to fill the window.
 *
 * Returns null when either the layout or the container has zero
 * dimension — caller should leave the previous viewport unchanged.
 */
export function fitToWindow(
  layoutWidth: number,
  layoutHeight: number,
  containerWidth: number,
  containerHeight: number,
): ViewportTransform | null {
  if (layoutWidth <= 0 || layoutHeight <= 0) return null;
  if (containerWidth <= 0 || containerHeight <= 0) return null;
  const fitScale = Math.min(
    (containerWidth / layoutWidth) * FIT_MARGIN,
    (containerHeight / layoutHeight) * FIT_MARGIN,
    1,
  );
  const scale = clampScale(fitScale);
  return {
    scale,
    x: (containerWidth - layoutWidth * scale) / 2,
    y: (containerHeight - layoutHeight * scale) / 2,
  };
}

/**
 * Computes the viewport that centres a node in the container at the
 * current scale. Used by:
 *  - existing comfort-zone auto-pan
 *  - the new Active button (jump to current execution)
 *  - breadcrumb segment clicks
 */
export function centerOnNode(
  nodeX: number,
  nodeY: number,
  scale: number,
  containerWidth: number,
  containerHeight: number,
): ViewportTransform {
  return {
    scale,
    x: containerWidth / 2 - nodeX * scale,
    y: containerHeight / 2 - nodeY * scale,
  };
}

/**
 * Zooms by {@code factor} (e.g. 1.25 to zoom in 25%, 0.8 to zoom out)
 * around the container's centre. Used by the +/- buttons and the
 * keyboard shortcuts. The cursor-anchored variant lives inline in
 * ExecutionTree's wheel handler — it needs the cursor coordinates,
 * which buttons don't have.
 */
export function zoomBy(
  current: ViewportTransform,
  factor: number,
  containerWidth: number,
  containerHeight: number,
): ViewportTransform {
  const nextScale = clampScale(current.scale * factor);
  if (nextScale === current.scale) return current;
  // Anchor on container centre so a button-press zoom doesn't fly the
  // viewport off in some direction the user didn't ask about.
  const cx = containerWidth / 2;
  const cy = containerHeight / 2;
  const ratio = nextScale / current.scale;
  return {
    scale: nextScale,
    x: cx - (cx - current.x) * ratio,
    y: cy - (cy - current.y) * ratio,
  };
}

/**
 * Returns the path of nodes from a root to the node with the given id,
 * inclusive at both ends. Used by the breadcrumb so each segment can
 * link back to a specific scope (session > turn > plan > step > …).
 *
 * Returns null when the id isn't found anywhere in the forest.
 */
export function pathToActive(
  rootNodes: ExecutionNode[],
  activeId: string | null,
): ExecutionNode[] | null {
  if (!activeId) return null;
  for (const root of rootNodes) {
    const path = findPath([root], activeId);
    if (path) return path;
  }
  return null;
}

function findPath(nodes: ExecutionNode[], id: string): ExecutionNode[] | null {
  for (const n of nodes) {
    if (n.id === id) return [n];
    const sub = findPath(n.children, id);
    if (sub) return [n, ...sub];
  }
  return null;
}

/**
 * Convenience: looks up a layout-node by id from a {@link LayoutNode}
 * list. Used by Active button + breadcrumb to translate a logical node
 * id into the (x, y) coordinates {@link centerOnNode} needs.
 */
export function findLayoutNode(
  layoutNodes: LayoutNode[],
  id: string,
): LayoutNode | null {
  return layoutNodes.find((n) => n.id === id) ?? null;
}
