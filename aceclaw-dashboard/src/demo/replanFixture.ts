/**
 * Hand-crafted ExecutionTree showing the full plan/replan visualization
 * (#458). Used by the dashboard's `?demo=replan` mode so the visual
 * change is verifiable without waiting for a real LLM plan + replan
 * cycle to fire (the planner threshold is 5 and replan only happens
 * when the model decides to revise mid-execution — not reproducible on
 * demand).
 *
 * The two replan markers deliberately illustrate the **two motivations**
 * an operator should learn to recognize:
 *
 *   1. **Reactive replan** — a step actually FAILED (red), the planner
 *      had to revise. Replan #1 follows a failed extract step.
 *   2. **Proactive replan** — every step before it just gets cancelled
 *      (no red), because the model itself decided to pivot before any
 *      step errored. Replan #2 follows a working JUnit test that the
 *      model decided to swap for AssertJ anyway.
 *
 * Tree shape:
 *
 *   session
 *     └── turn 1
 *           └── plan (with replan ×2 chip)
 *                 ├── completed step  (s1 — analyze)
 *                 ├── FAILED step     (s2 — extract v1, red, with error chip)
 *                 ├── cancelled step  (s3 — tests v1 — never ran)
 *                 ├── replan #1       — REACTIVE (extract failed)
 *                 ├── completed step  (s2' — extract v2)
 *                 ├── cancelled step  (s3' — JUnit tests, was running fine)
 *                 ├── replan #2       — PROACTIVE (model switched framework)
 *                 └── running step    (s3'' — AssertJ tests)
 */

import type { ExecutionTree } from '../types/tree';

export const REPLAN_DEMO_SESSION_ID = 'demo-replan-session';

export function buildReplanFixture(): ExecutionTree {
  return {
    sessionId: REPLAN_DEMO_SESSION_ID,
    rootNodes: [
      {
        id: 'demo-session',
        type: 'session',
        status: 'running',
        label: 'session demo',
        children: [
          {
            id: 'demo-turn-1',
            type: 'turn',
            status: 'running',
            label: 'turn 1',
            children: [
              {
                id: 'demo-plan',
                type: 'plan',
                status: 'running',
                label: 'plan: refactor auth + add tests',
                metadata: {
                  // Plan has been replanned twice → "replan ×2" chip should
                  // render on the plan node itself per GrowingNode.tsx.
                  replanAttempt: 2,
                  replanRationale: 'switched test framework',
                  newStepCount: 1,
                },
                children: [
                  {
                    id: 'demo-step-s1',
                    type: 'step',
                    status: 'completed',
                    label: 'analyze AuthService',
                    duration: 1240,
                    children: [],
                  },
                  {
                    id: 'demo-step-s2-v1',
                    type: 'step',
                    status: 'failed',
                    label: 'extract validator (v1)',
                    error: 'NoSuchMethodError: missing import on line 42',
                    duration: 320,
                    children: [],
                  },
                  {
                    id: 'demo-step-s3-v1',
                    type: 'step',
                    status: 'cancelled',
                    label: 'add unit tests (v1)',
                    children: [],
                  },
                  {
                    id: 'demo-plan::replan-1',
                    type: 'replan',
                    status: 'completed',
                    label: 'Replan #1',
                    metadata: {
                      rationale:
                        'extract failed (missing import). Switching to a top-down extraction that resolves dependencies first.',
                      replanAttempt: 1,
                      cancelledStepCount: 1,
                      newStepCount: 2,
                    },
                    children: [],
                  },
                  {
                    id: 'demo-step-s2-v2',
                    type: 'step',
                    status: 'completed',
                    label: 'extract validator (v2)',
                    duration: 980,
                    children: [],
                  },
                  {
                    id: 'demo-step-s3-v2',
                    type: 'step',
                    status: 'cancelled',
                    label: 'add JUnit tests (v2)',
                    children: [],
                  },
                  {
                    id: 'demo-plan::replan-2',
                    type: 'replan',
                    status: 'completed',
                    label: 'Replan #2',
                    metadata: {
                      // Proactive: nothing failed, the model just decided
                      // mid-execution that AssertJ would be a better fit.
                      // The cancelled JUnit step above carries no error.
                      rationale:
                        'JUnit was working but AssertJ has better matchers for the new validator API — switching for code quality',
                      replanAttempt: 2,
                      cancelledStepCount: 1,
                      newStepCount: 1,
                    },
                    children: [],
                  },
                  {
                    id: 'demo-step-s3-v3',
                    type: 'step',
                    status: 'running',
                    label: 'add AssertJ tests (v3)',
                    children: [],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
    activeNodeId: 'demo-step-s3-v3',
    lastEventId: 0,
    nextSyntheticId: 1,
    currentThinkingId: null,
    thinkingSealed: false,
    currentTextId: null,
    textIsOpen: false,
    pendingPermissionsByToolUseId: {},
    stats: {
      totalTools: 0,
      completedTools: 0,
      failedTools: 0,
      totalTurns: 1,
      inputTokens: 0,
      outputTokens: 0,
    },
  };
}
