/**
 * Breadcrumb — top-left path display showing the chain from the root
 * (session) down to the active node. Each segment is clickable and
 * centres the camera on that node at the current zoom level.
 *
 * Lives outside the SVG so it can be plain HTML (clickable text,
 * native focus, no foreignObject quirks). Sits in a pointer-events:auto
 * container above the SVG.
 */

import type { ExecutionNode } from '../types/tree';

interface BreadcrumbProps {
  /** Path from a root to the active node, inclusive at both ends. */
  path: ExecutionNode[];
  /** Called with the segment's node id when the user clicks it. */
  onNavigate: (nodeId: string) => void;
}

export function Breadcrumb({ path, onNavigate }: BreadcrumbProps) {
  if (path.length === 0) return null;
  return (
    <nav
      // pointer-events:auto re-enables clicks (parent overlay turned them off
      // so wheel/drag still hits the SVG below).
      className="pointer-events-auto flex max-w-[80vw] flex-wrap items-center gap-1 rounded-lg border border-zinc-800 bg-zinc-900/85 px-2 py-1 font-mono text-[11px] text-zinc-400 shadow backdrop-blur-sm"
      aria-label="Active node path"
    >
      {path.map((node, i) => {
        const isLast = i === path.length - 1;
        return (
          <span key={node.id} className="flex items-center gap-1">
            {i > 0 ? (
              <span className="text-zinc-600" aria-hidden="true">
                ›
              </span>
            ) : null}
            <button
              type="button"
              onClick={() => onNavigate(node.id)}
              title={`Centre on ${segmentLabel(node)}`}
              className={
                'rounded px-1 transition-colors hover:bg-zinc-800 hover:text-zinc-100 ' +
                (isLast ? 'font-semibold text-zinc-200' : 'text-zinc-400')
              }
            >
              {segmentLabel(node)}
            </button>
          </span>
        );
      })}
    </nav>
  );
}

/**
 * Picks the most informative short label for a breadcrumb segment.
 * Most node types render as `{type}: {label}` so the path stays
 * scannable; a few short types skip the colon to save space. Exported
 * for unit tests.
 */
export function segmentLabel(node: ExecutionNode): string {
  switch (node.type) {
    case 'session':
      // Session ids are long uuids — show only the first 8 chars so the
      // breadcrumb stays narrow. Full id is still clickable + tooltip-able.
      return `session ${node.id.slice(0, 8)}`;
    case 'turn':
    case 'request':
    case 'thinking':
    case 'text':
      return node.type;
    case 'plan':
      return 'plan';
    case 'step': {
      const stepIdx = node.metadata?.['stepIndex'];
      return typeof stepIdx === 'number' ? `step ${stepIdx}` : 'step';
    }
    case 'tool':
      return `tool: ${node.label}`;
    case 'subagent':
      return `subagent: ${node.label}`;
    case 'replan': {
      const attempt = node.metadata?.['replanAttempt'];
      return typeof attempt === 'number' ? `replan #${attempt}` : 'replan';
    }
  }
}
