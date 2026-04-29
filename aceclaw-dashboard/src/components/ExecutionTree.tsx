/**
 * ExecutionTree — top-level SVG container for the Tier 1 dashboard tree.
 *
 * Owns three pieces of view-only state on top of the reducer's
 * {@link ExecutionTree} data:
 *   1. zoom (scalar, clamped to [0.2, 3])
 *   2. pan (x,y translate, mouse-drag controlled)
 *   3. auto-scroll target — a transient pan offset that smoothly centres
 *      the active leaf when {@code activeNodeId} changes. The user's
 *      manual pan wins until they release the drag.
 *
 * Layout itself comes from {@link useTreeLayout} (#436's dagre integration);
 * this component is a thin renderer + interaction layer.
 */

import { AnimatePresence } from 'framer-motion';
import {
  type PointerEvent as ReactPointerEvent,
  type WheelEvent as ReactWheelEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { ExecutionTree as ExecutionTreeState } from '../types/tree';
import { useTreeLayout } from '../hooks/useTreeLayout';
import { GrowingEdge } from './GrowingEdge';
import { GrowingNode } from './GrowingNode';

interface ExecutionTreeProps {
  tree: ExecutionTreeState;
}

const ZOOM_MIN = 0.2;
const ZOOM_MAX = 3;
const ZOOM_STEP = 0.0015;

interface ViewportTransform {
  x: number;
  y: number;
  scale: number;
}

const INITIAL_TRANSFORM: ViewportTransform = { x: 0, y: 0, scale: 1 };

export function ExecutionTree({ tree }: ExecutionTreeProps) {
  const layout = useTreeLayout(tree);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [viewport, setViewport] = useState<ViewportTransform>(INITIAL_TRANSFORM);
  const dragRef = useRef<{ startX: number; startY: number; baseX: number; baseY: number } | null>(
    null,
  );
  // Initial auto-fit: pick a scale that shows the whole tree on the first
  // render that has content, then NEVER refit afterward. Later events
  // pan to the active node at the chosen scale (see auto-scroll below)
  // so the camera follows the action without zooming out to fit a
  // growing tree — the user complaint here was "after response, don't
  // jump back to show everything; stop where you are". A constant
  // scale combined with active-node-following gives that.
  //
  // Reset on sessionId change so picking a new session re-fits to its
  // dimensions; lastFitRef would otherwise leak the previous session's
  // signature.
  const lastFitRef = useRef<string | null>(null);
  useEffect(() => {
    lastFitRef.current = null;
  }, [tree.sessionId]);
  useEffect(() => {
    if (dragRef.current) return;
    if (lastFitRef.current !== null) return;
    if (layout.width <= 0 || layout.height <= 0) return;
    const el = containerRef.current;
    if (!el) return;
    const { width: cw, height: ch } = el.getBoundingClientRect();
    if (cw <= 0 || ch <= 0) {
      // Container hasn't been measured yet (hidden tab, deferred layout, …).
      // Leave lastFitRef null so the next render retries once measurable.
      return;
    }
    const margin = 0.9;
    const fitScale = Math.min((cw / layout.width) * margin, (ch / layout.height) * margin, 1);
    const scale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, fitScale));
    setViewport({
      scale,
      x: (cw - layout.width * scale) / 2,
      y: (ch - layout.height * scale) / 2,
    });
    lastFitRef.current = 'fitted';
  }, [layout.width, layout.height]);

  // Auto-scroll: pan to the active node whenever the focus moves OR a
  // new node arrives (layout.nodes identity changes on every reducer
  // step, so this fires on every event the running session emits). The
  // scale stays where the initial fit left it — no rescale per event.
  // Once the turn completes and activeNodeId stops changing, the camera
  // sits on the last active node and stays there: that's the "stop
  // after response" behaviour.
  useEffect(() => {
    if (dragRef.current) return;
    if (!tree.activeNodeId) return;
    const target = layout.nodes.find((n) => n.id === tree.activeNodeId);
    if (!target) return;
    const el = containerRef.current;
    if (!el) return;
    const { width: cw, height: ch } = el.getBoundingClientRect();
    setViewport((prev) => ({
      scale: prev.scale,
      x: cw / 2 - target.x * prev.scale,
      y: ch / 2 - target.y * prev.scale,
    }));
  }, [tree.activeNodeId, layout.nodes]);

  const handleWheel = (e: ReactWheelEvent<HTMLDivElement>): void => {
    // Stop the page from scrolling while the operator is zooming the tree.
    // React's onWheel listener is non-passive, so preventDefault is honoured.
    e.preventDefault();
    const delta = -e.deltaY * ZOOM_STEP;
    setViewport((prev) => {
      const next = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, prev.scale + delta));
      // Zoom anchored on cursor position so the point under the mouse stays
      // put — standard 2D-canvas idiom.
      const rect = containerRef.current?.getBoundingClientRect();
      if (!rect) return { ...prev, scale: next };
      const cx = e.clientX - rect.left;
      const cy = e.clientY - rect.top;
      const ratio = next / prev.scale;
      return {
        scale: next,
        x: cx - (cx - prev.x) * ratio,
        y: cy - (cy - prev.y) * ratio,
      };
    });
  };

  const handlePointerDown = (e: ReactPointerEvent<HTMLDivElement>): void => {
    if (e.button !== 0) return;
    dragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      baseX: viewport.x,
      baseY: viewport.y,
    };
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  const handlePointerMove = (e: ReactPointerEvent<HTMLDivElement>): void => {
    const drag = dragRef.current;
    if (!drag) return;
    setViewport((prev) => ({
      scale: prev.scale,
      x: drag.baseX + (e.clientX - drag.startX),
      y: drag.baseY + (e.clientY - drag.startY),
    }));
  };

  const handlePointerUp = (e: ReactPointerEvent<HTMLDivElement>): void => {
    if (dragRef.current) {
      e.currentTarget.releasePointerCapture(e.pointerId);
      dragRef.current = null;
    }
  };

  // Stable transform string — useMemo because it's used inside <g>.
  const transform = useMemo(
    () => `translate(${viewport.x}, ${viewport.y}) scale(${viewport.scale})`,
    [viewport.x, viewport.y, viewport.scale],
  );

  if (layout.nodes.length === 0) {
    return (
      <div
        ref={containerRef}
        className="flex h-full w-full items-center justify-center text-sm text-zinc-500"
      >
        Waiting for events…
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      onWheel={handleWheel}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
      className="relative h-full w-full cursor-grab overflow-hidden bg-zinc-950 active:cursor-grabbing"
    >
      <svg className="h-full w-full" role="img" aria-label="execution tree">
        {/*
          Arrowhead marker for sequence/flow edges. Shape: a thin triangle
          oriented along the path tangent (orient="auto") so it points
          where the edge points, regardless of straight-line vs bezier.
          Sized in user-space units so it scales with the SVG group's
          transform. Fill matches GrowingEdge's SEQUENCE_STROKE so the
          arrowhead and line look like one piece.
        */}
        <defs>
          <marker
            id="seq-arrow"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="7"
            markerHeight="7"
            orient="auto-start-reverse"
            markerUnits="userSpaceOnUse"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" fill="#52525b" opacity={0.7} />
          </marker>
        </defs>
        <g transform={transform}>
          <AnimatePresence>
            {layout.edges.map((e) => (
              <GrowingEdge key={e.id} edge={e} />
            ))}
          </AnimatePresence>
          <AnimatePresence>
            {layout.nodes.map((n) => (
              <GrowingNode key={n.id} node={n} />
            ))}
          </AnimatePresence>
        </g>
      </svg>
    </div>
  );
}
