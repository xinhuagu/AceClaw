/**
 * Tests for the breadcrumb's segmentLabel function — the only piece of
 * Breadcrumb that's worth testing without a DOM. The component itself
 * is straightforward map+button rendering; the labelling rules
 * (session truncation, type-only labels, step-index extraction,
 * replan-attempt extraction) are where typos and rule drift would
 * silently produce confusing breadcrumbs.
 */

import { describe, expect, it } from 'vitest';
import { segmentLabel } from '../src/components/Breadcrumb';
import type { ExecutionNode } from '../src/types/tree';

function n(
  type: ExecutionNode['type'],
  overrides: Partial<ExecutionNode> = {},
): ExecutionNode {
  return {
    id: 'x',
    type,
    status: 'running',
    label: 'x',
    children: [],
    ...overrides,
  };
}

describe('segmentLabel', () => {
  it('truncates session ids to the first 8 chars', () => {
    const s = n('session', { id: '1234567890abcdef' });
    expect(segmentLabel(s)).toBe('session 12345678');
  });

  it('shows type-only labels for turn/request/thinking/text', () => {
    expect(segmentLabel(n('turn'))).toBe('turn');
    expect(segmentLabel(n('request'))).toBe('request');
    expect(segmentLabel(n('thinking'))).toBe('thinking');
    expect(segmentLabel(n('text'))).toBe('text');
  });

  it('shows "plan" for plan nodes regardless of label', () => {
    expect(segmentLabel(n('plan', { label: 'plan: refactor everything' }))).toBe('plan');
  });

  it('shows "step N" using metadata.stepIndex when present', () => {
    expect(segmentLabel(n('step', { metadata: { stepIndex: 3 } }))).toBe('step 3');
  });

  it('falls back to bare "step" when stepIndex is missing or wrong type', () => {
    expect(segmentLabel(n('step'))).toBe('step');
    expect(segmentLabel(n('step', { metadata: { stepIndex: 'not-a-number' } }))).toBe('step');
  });

  it('shows "tool: <label>" for tool nodes (label carries the tool name)', () => {
    expect(segmentLabel(n('tool', { label: 'read_file' }))).toBe('tool: read_file');
  });

  it('shows "subagent: <label>" for sub-agents', () => {
    expect(segmentLabel(n('subagent', { label: 'commit-skill' }))).toBe('subagent: commit-skill');
  });

  it('shows "replan #N" using metadata.replanAttempt', () => {
    expect(segmentLabel(n('replan', { metadata: { replanAttempt: 2 } }))).toBe('replan #2');
  });

  it('falls back to bare "replan" when replanAttempt is missing', () => {
    expect(segmentLabel(n('replan'))).toBe('replan');
  });
});
