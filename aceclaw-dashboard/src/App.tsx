import { motion } from 'framer-motion';
import * as dagre from 'dagre';

/**
 * Tier 1 dashboard placeholder (epic #430, issue #434).
 *
 * Imports framer-motion and dagre on purpose — every acceptance-criterion
 * dependency has at least one live use in the bundle so production builds
 * exercise the real code path, not just type checks. Visual content is a
 * spring-animated header + a dagre-laid-out preview of two stub nodes,
 * proving the toolchain end-to-end before #435/#436 land the real reducer
 * and tree component.
 */
export function App() {
  // Smoke-test dagre: lay out two nodes so the build pulls the layout engine
  // into the bundle (acceptance criterion).
  const graph = new dagre.graphlib.Graph();
  graph.setGraph({ rankdir: 'LR', nodesep: 60, ranksep: 80 });
  graph.setDefaultEdgeLabel(() => ({}));
  graph.setNode('root', { width: 160, height: 40, label: 'session' });
  graph.setNode('turn', { width: 160, height: 40, label: 'turn 1' });
  graph.setEdge('root', 'turn');
  dagre.layout(graph);

  const nodes = graph.nodes().map((id) => ({ id, ...graph.node(id) }));
  const edges = graph.edges().map((e) => ({
    from: graph.node(e.v),
    to: graph.node(e.w),
  }));

  return (
    <main className="flex h-full flex-col items-center justify-center gap-6 p-8">
      <motion.h1
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ type: 'spring', stiffness: 300, damping: 24 }}
        className="text-3xl font-semibold tracking-tight"
      >
        AceClaw Dashboard
      </motion.h1>
      <p className="max-w-md text-center text-sm text-zinc-400">
        Tier 1 real-time execution tree — scaffold only. The reducer (#435) and
        tree component (#436) will land on top of this.
      </p>
      <svg
        role="img"
        aria-label="Layout smoke test"
        viewBox={`0 0 ${graph.graph().width ?? 400} ${graph.graph().height ?? 80}`}
        className="w-96 max-w-full text-zinc-700"
      >
        {edges.map((e, i) => (
          <line
            key={`e-${i}`}
            x1={e.from.x}
            y1={e.from.y}
            x2={e.to.x}
            y2={e.to.y}
            stroke="currentColor"
            strokeWidth={1.5}
          />
        ))}
        {nodes.map((n) => (
          <g key={n.id} transform={`translate(${n.x - n.width / 2}, ${n.y - n.height / 2})`}>
            <rect
              width={n.width}
              height={n.height}
              rx={6}
              className="fill-zinc-900 stroke-zinc-600"
              strokeWidth={1}
            />
            <text
              x={n.width / 2}
              y={n.height / 2 + 4}
              textAnchor="middle"
              className="fill-zinc-200 text-xs"
            >
              {n.label as string}
            </text>
          </g>
        ))}
      </svg>
    </main>
  );
}
