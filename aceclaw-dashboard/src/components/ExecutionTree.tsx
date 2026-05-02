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
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { ExecutionTree as ExecutionTreeState, LayoutNode } from '../types/tree';
import { useTreeLayout } from '../hooks/useTreeLayout';
import { Breadcrumb } from './Breadcrumb';
import { GrowingEdge } from './GrowingEdge';
import { GrowingNode } from './GrowingNode';
import { NavControls } from './NavControls';
import { PermissionPanel } from './PermissionPanel';
import {
  type ViewportTransform,
  ZOOM_MAX,
  ZOOM_MIN,
  centerOnNode,
  clampScale,
  fitToWindow,
  pathToActive,
} from './viewport';
// Re-exported for tests that import it from this module historically;
// the canonical definition lives in ./viewport now.
export type { ViewportTransform } from './viewport';

interface ExecutionTreeProps {
  tree: ExecutionTreeState;
  /**
   * Approve/AlwaysAllow/Deny/Dismiss handlers for the inline
   * {@link PermissionPanel} (issue #437). Optional so test scaffolding
   * and the empty state can still render the tree without wiring
   * permission flows. App.tsx wires these to the WS hook's
   * {@code sendCommand}, {@code resolvePermission}, and
   * {@code dismissPermission}.
   */
  onApprovePermission?: (requestId: string) => void;
  onAlwaysAllowPermission?: (requestId: string) => void;
  onDenyPermission?: (requestId: string) => void;
  onDismissPermission?: (requestId: string) => void;
}

// ZOOM_MIN / ZOOM_MAX / ViewportTransform / fitToWindow / centerOnNode
// live in ./viewport so NavControls and Breadcrumb can share them.
// Keeps wheel-zoom and button-zoom from drifting apart as the math evolves.
const ZOOM_STEP = 0.0015;
/** Multiplicative factor applied per zoom-button press / Cmd-+/- press. */
const BUTTON_ZOOM_FACTOR = 1.25;

/**
 * Vertical real estate every {@link PermissionPanel} reserves when
 * stacked. The actual rendered height varies (subject line wraps,
 * resolved-state collapses the buttons), but ~170 px is a safe lower
 * bound for the awaiting-state panel's full content. Used by
 * {@link computePanelAnchors} to cascade colliding panels.
 */
const PANEL_STACK_MIN_HEIGHT = 170;
/** Gap between cascaded panels. */
const PANEL_STACK_GAP = 12;
/**
 * Horizontal offset from the node's right edge to the panel's left
 * edge. Wide enough that the panel's connector notch clears the node
 * border without overlapping it.
 */
const PANEL_HORIZONTAL_OFFSET = 12;

/** Container-space anchor for one panel. */
export interface PanelAnchor {
  node: LayoutNode;
  x: number;
  y: number;
}

/**
 * Computes screen-space anchors for the given awaiting nodes, cascading
 * panels downward when their natural Y would cause vertical overlap.
 *
 * Dagre's LR layout puts parallel sibling tools ~94 px apart vertically
 * (NODE_HEIGHT 44 + nodesep 50), but a panel renders at ~170 px tall —
 * two siblings asking for permission would otherwise overlap by ~75 px.
 * Sort by desired Y and floor each subsequent panel at the previous
 * panel's bottom + GAP. The X coordinate stays at the node's right edge
 * so the connector notch on a shifted panel still points roughly toward
 * its source node.
 *
 * Pure function — exported for direct unit testing.
 */
export function computePanelAnchors(
  awaitingNodes: LayoutNode[],
  viewport: ViewportTransform,
): PanelAnchor[] {
  const sorted = [...awaitingNodes].sort((a, b) => a.y - b.y);
  let lastBottom = -Infinity;
  return sorted.map((node) => {
    const x =
      viewport.x +
      (node.x + node.width / 2) * viewport.scale +
      PANEL_HORIZONTAL_OFFSET;
    const desiredY = viewport.y + node.y * viewport.scale;
    const y = Math.max(desiredY, lastBottom + PANEL_STACK_GAP);
    lastBottom = y + PANEL_STACK_MIN_HEIGHT;
    return { node, x, y };
  });
}

const INITIAL_TRANSFORM: ViewportTransform = { x: 0, y: 0, scale: 1 };

export function ExecutionTree({
  tree,
  onApprovePermission,
  onAlwaysAllowPermission,
  onDenyPermission,
  onDismissPermission,
}: ExecutionTreeProps) {
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
    const next = fitToWindow(layout.width, layout.height, cw, ch);
    if (!next) return;
    setViewport(next);
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
    setViewport((prev) => {
      // "Comfort zone" — when the active node is already inside this
      // inner rect, do nothing. This keeps the camera still while a
      // turn produces nodes that fit on screen, and only pans when
      // activity reaches the viewport edge. Without this, every new
      // event (thinking → tool → text → next iteration) triggered
      // a re-center, and the user's just-clicked node visibly slid
      // out from under their cursor.
      const COMFORT_MARGIN_X = 80;
      const COMFORT_MARGIN_Y = 40;
      const screenX = prev.x + target.x * prev.scale;
      const screenY = prev.y + target.y * prev.scale;
      const halfW = (target.width / 2) * prev.scale;
      const halfH = (target.height / 2) * prev.scale;
      const insideX =
        screenX - halfW >= COMFORT_MARGIN_X &&
        screenX + halfW <= cw - COMFORT_MARGIN_X;
      const insideY =
        screenY - halfH >= COMFORT_MARGIN_Y &&
        screenY + halfH <= ch - COMFORT_MARGIN_Y;
      if (insideX && insideY) return prev;
      // Out of comfort zone on at least one axis — re-center the
      // active node. Centring (rather than nudging) means a single
      // smooth pan handles bursts of distant activity without
      // chasing every event.
      return centerOnNode(target.x, target.y, prev.scale, cw, ch);
    });
  }, [tree.activeNodeId, layout.nodes]);

  // Wheel zoom should stay snappy: each wheel tick is a discrete user
  // gesture, not an event we want to spring-animate. Track recent wheel
  // activity and suppress the auto-scroll transition during it the
  // same way pointer drag does.
  const wheelIdleTimer = useRef<number | null>(null);
  const [isWheeling, setIsWheeling] = useState<boolean>(false);

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
    // Mark the user as actively wheeling for ~150 ms so the
    // transition-on-viewport-change effect doesn't try to spring-
    // animate consecutive zoom ticks (would feel laggy). After the
    // wheel goes idle we switch back to smooth-transition mode.
    setIsWheeling(true);
    if (wheelIdleTimer.current !== null) {
      window.clearTimeout(wheelIdleTimer.current);
    }
    wheelIdleTimer.current = window.setTimeout(() => {
      setIsWheeling(false);
      wheelIdleTimer.current = null;
    }, 150);
  };

  // Tracks whether the user is currently dragging the canvas. When
  // true, the SVG/panel CSS transitions are disabled so the canvas
  // stays glued to the cursor; when false, viewport changes (driven
  // by auto-scroll-to-active-node) animate smoothly so a freshly-
  // arrived node off the right edge slides into view rather than the
  // camera teleporting to it.
  const [isDragging, setIsDragging] = useState<boolean>(false);

  const handlePointerDown = (e: ReactPointerEvent<HTMLDivElement>): void => {
    if (e.button !== 0) return;
    dragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      baseX: viewport.x,
      baseY: viewport.y,
    };
    setIsDragging(true);
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
      setIsDragging(false);
    }
  };

  // Stable transform — used as style.transform (NOT the SVG attribute)
  // so CSS transitions on it actually animate. style.transform on SVG
  // wants CSS units (px) and the standard CSS transform syntax;
  // the SVG `transform` attribute is unitless and isn't reliably
  // transitionable across browsers (Safari especially).
  const transformStyle = useMemo(
    () => `translate(${viewport.x}px, ${viewport.y}px) scale(${viewport.scale})`,
    [viewport.x, viewport.y, viewport.scale],
  );

  // -- Navigation actions wired to NavControls + Breadcrumb + keyboard --
  // All four go through the shared viewport helpers so wheel-zoom and
  // button-zoom + jump-to-active and breadcrumb-click compute identical
  // viewports for the same inputs.
  const containerRect = useCallback((): { cw: number; ch: number } | null => {
    const el = containerRef.current;
    if (!el) return null;
    const { width, height } = el.getBoundingClientRect();
    if (width <= 0 || height <= 0) return null;
    return { cw: width, ch: height };
  }, []);

  const handleZoom = useCallback((factor: number) => {
    setViewport((prev) => {
      const r = containerRect();
      if (!r) return prev;
      const nextScale = clampScale(prev.scale * factor);
      if (nextScale === prev.scale) return prev;
      const cx = r.cw / 2;
      const cy = r.ch / 2;
      const ratio = nextScale / prev.scale;
      return {
        scale: nextScale,
        x: cx - (cx - prev.x) * ratio,
        y: cy - (cy - prev.y) * ratio,
      };
    });
  }, [containerRect]);

  const handleFit = useCallback(() => {
    const r = containerRect();
    if (!r) return;
    const next = fitToWindow(layout.width, layout.height, r.cw, r.ch);
    if (next) setViewport(next);
  }, [containerRect, layout.width, layout.height]);

  const handleJumpToActive = useCallback(() => {
    if (!tree.activeNodeId) return;
    const target = layout.nodes.find((n) => n.id === tree.activeNodeId);
    if (!target) return;
    const r = containerRect();
    if (!r) return;
    setViewport((prev) => centerOnNode(target.x, target.y, prev.scale, r.cw, r.ch));
  }, [tree.activeNodeId, layout.nodes, containerRect]);

  /** Breadcrumb segment click → centre on the segment's node at current scale. */
  const handleBreadcrumbNavigate = useCallback((nodeId: string) => {
    const target = layout.nodes.find((n) => n.id === nodeId);
    if (!target) return;
    const r = containerRect();
    if (!r) return;
    setViewport((prev) => centerOnNode(target.x, target.y, prev.scale, r.cw, r.ch));
  }, [layout.nodes, containerRect]);

  // Keyboard shortcuts: Cmd/Ctrl + +/-/0 for zoom, F for fit, A for active.
  // Modifier guards mirror PermissionPanel's so we don't hijack typing
  // in any hypothetical text input that lands inside the tree pane.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA') return;
      if (target?.isContentEditable) return;
      const cmdLike = e.metaKey || e.ctrlKey;
      // Cmd/Ctrl + +  → zoom in. The browser maps the literal "+" key
      // to event.key === "+" only with shift on most layouts; "=" is
      // the unshifted variant on US-ANSI. Accept both for ergonomics.
      if (cmdLike && (e.key === '+' || e.key === '=')) {
        e.preventDefault();
        handleZoom(BUTTON_ZOOM_FACTOR);
        return;
      }
      if (cmdLike && e.key === '-') {
        e.preventDefault();
        handleZoom(1 / BUTTON_ZOOM_FACTOR);
        return;
      }
      if (cmdLike && e.key === '0') {
        e.preventDefault();
        handleFit();
        return;
      }
      // Plain F / A — single-letter shortcuts because typing text is
      // gated above. Still skip when ANY modifier is held so we don't
      // collide with Cmd-F (browser find).
      if (!cmdLike && !e.altKey && !e.shiftKey) {
        if (e.key === 'f' || e.key === 'F') {
          e.preventDefault();
          handleFit();
          return;
        }
        if (e.key === 'a' || e.key === 'A') {
          e.preventDefault();
          handleJumpToActive();
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [handleZoom, handleFit, handleJumpToActive]);

  // Path from root to active node — drives the Breadcrumb. Memo so it
  // doesn't recompute on every viewport change, only when the tree
  // structure or the active node moves.
  const breadcrumbPath = useMemo(
    () => pathToActive(tree.rootNodes, tree.activeNodeId),
    [tree.rootNodes, tree.activeNodeId],
  );
  // The canvas transition is enabled only when no user interaction
  // (drag/wheel) is in flight — so a pan or zoom feels direct, but a
  // newly-arrived node sliding into focus is animated.
  const transformTransition =
    isDragging || isWheeling
      ? 'none'
      : 'transform 0.4s cubic-bezier(0.22, 1, 0.36, 1)';

  /**
   * Click-to-open panel state. Earlier versions auto-mounted the panel
   * the moment a {@code permission.request} arrived — but on a daemon
   * with an auto-accept policy the panel would flash for ~1.5 s and
   * vanish before the user could read it. New UX: nodes that need
   * permission show their paused glyph + cursor:pointer in the tree,
   * and the panel only opens when the user clicks the node. Resolves
   * to {@code null} either by user action (Approve/Deny/Esc/×) or
   * because the underlying node lost {@code awaitingInput} (CLI raced
   * to answer first).
   */
  const [openPanelRequestId, setOpenPanelRequestId] = useState<string | null>(
    null,
  );
  const handleAwaitingNodeClick = useCallback((requestId: string) => {
    setOpenPanelRequestId((prev) => (prev === requestId ? null : requestId));
  }, []);
  // Auto-close when the underlying request is no longer pending — e.g.
  // the CLI answered while the panel was open, or the daemon timed out.
  useEffect(() => {
    if (!openPanelRequestId) return;
    const stillPending = layout.nodes.some(
      (n) =>
        n.permissionRequestId === openPanelRequestId &&
        n.awaitingInput === true,
    );
    if (!stillPending) setOpenPanelRequestId(null);
  }, [layout.nodes, openPanelRequestId]);

  // Single open panel — find its node + compute anchor coords.
  const openPanelNode = useMemo<LayoutNode | null>(() => {
    if (!openPanelRequestId) return null;
    return (
      layout.nodes.find(
        (n) => n.permissionRequestId === openPanelRequestId,
      ) ?? null
    );
  }, [layout.nodes, openPanelRequestId]);
  // Reuse the same anchor math the cascade tests pin so this single-
  // panel path can't drift from the (future) multi-panel one. Single-
  // element array → no cascading happens; just the right-edge offset.
  const openPanelAnchor = useMemo(() => {
    if (!openPanelNode) return null;
    const [anchor] = computePanelAnchors([openPanelNode], viewport);
    return anchor ?? null;
  }, [openPanelNode, viewport]);

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
      {/* Breadcrumb (top-left, fixed). Always rendered when there's an
          active path so the user can see where the camera is following
          AND click any segment to jump back to that scope. */}
      {breadcrumbPath && breadcrumbPath.length > 0 ? (
        <div className="pointer-events-none absolute left-3 top-3 z-30">
          <Breadcrumb path={breadcrumbPath} onNavigate={handleBreadcrumbNavigate} />
        </div>
      ) : null}
      {/* Nav controls (bottom-right, fixed). −, +, Fit, Active + zoom %. */}
      <div className="pointer-events-none absolute bottom-3 right-3 z-30">
        <NavControls
          scale={viewport.scale}
          canJumpToActive={tree.activeNodeId !== null}
          onZoomIn={() => handleZoom(BUTTON_ZOOM_FACTOR)}
          onZoomOut={() => handleZoom(1 / BUTTON_ZOOM_FACTOR)}
          onFit={handleFit}
          onJumpToActive={handleJumpToActive}
        />
      </div>
      {/*
        Permission panel sits ABOVE the SVG canvas as an HTML overlay, so
        buttons and pointer interactions don't fight with foreignObject
        quirks. The container has pointer-events on; we still want the
        background SVG to receive wheel/drag, so the overlay layer is
        pointer-events:none and the panel itself opts back in.
      */}
      <div className="pointer-events-none absolute inset-0 z-20">
        <AnimatePresence>
          {openPanelNode &&
          openPanelAnchor &&
          onApprovePermission &&
          onAlwaysAllowPermission &&
          onDenyPermission &&
          onDismissPermission ? (
            <PermissionPanel
              key={openPanelNode.permissionRequestId ?? openPanelNode.id}
              node={openPanelNode}
              anchorX={openPanelAnchor.x}
              anchorY={openPanelAnchor.y}
              onApprove={(rid) => {
                onApprovePermission(rid);
                setOpenPanelRequestId(null);
              }}
              onAlwaysAllow={(rid) => {
                onAlwaysAllowPermission(rid);
                setOpenPanelRequestId(null);
              }}
              onDeny={(rid) => {
                onDenyPermission(rid);
                setOpenPanelRequestId(null);
              }}
              onDismiss={(_rid) => {
                // User Esc / × close: just close the panel locally.
                // Do NOT call onDismissPermission — that would strip
                // awaitingInput / permissionRequestId from the node,
                // which means the user couldn't reopen the panel and
                // the daemon would keep waiting until its 120 s
                // timeout. The request stays addressable; the user can
                // click the node again to bring the panel back.
                void _rid;
                setOpenPanelRequestId(null);
              }}
              onTimeout={(rid) => {
                // Deadline elapsed — daemon will reject any further
                // response, so clear the node's awaiting flag so the
                // user can't reopen and click on a stale request.
                setOpenPanelRequestId(null);
                onDismissPermission(rid);
              }}
            />
          ) : null}
        </AnimatePresence>
      </div>
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
            markerWidth="8"
            markerHeight="8"
            orient="auto-start-reverse"
            markerUnits="userSpaceOnUse"
          >
            {/*
              Fill matches GrowingEdge's SEQUENCE_STROKE so the arrowhead
              looks like the same piece as the dashed line. Bumped from
              opacity 0.7 → 0.95 because at the previous strength the
              arrow disappeared on the dark canvas.
            */}
            <path d="M 0 0 L 10 5 L 0 10 z" fill="#94a3b8" opacity={0.95} />
          </marker>
        </defs>
        <g
          style={{
            transform: transformStyle,
            transformOrigin: '0 0',
            transition: transformTransition,
          }}
        >
          <AnimatePresence>
            {layout.edges.map((e) => (
              <GrowingEdge key={e.id} edge={e} />
            ))}
          </AnimatePresence>
          <AnimatePresence>
            {layout.nodes.map((n) => (
              <GrowingNode
                key={n.id}
                node={n}
                onAwaitingClick={handleAwaitingNodeClick}
                isOpenPanel={
                  n.permissionRequestId !== undefined &&
                  n.permissionRequestId === openPanelRequestId
                }
              />
            ))}
          </AnimatePresence>
        </g>
      </svg>
    </div>
  );
}
