package dev.aceclaw.core.planner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentinel tests for the planner system prompt. The prompt content
 * itself is a behaviour-tuning surface — full correctness can only
 * be verified against a real LLM — but a few key calibration phrases
 * are worth pinning so a future reverting edit fires a loud build-
 * time signal instead of silently reintroducing over-decomposition.
 *
 * <p>The history that motivates these pins:
 *   - The original prompt said "Use 2-15 steps. Fewer for simpler
 *     tasks, more for complex ones." That was too vague — Claude
 *     gravitated toward the upper-middle (8-12 steps) by default,
 *     which over-decomposed simple work and ballooned plan-mode
 *     token cost. Real complaint, real cost.
 *   - Tightened to default 4-6 with explicit anti-over-decomposition
 *     language. These tests pin those two anchors.
 */
final class PlanningPromptBuilderTest {

    @Test
    void systemPrompt_anchorsDefaultStepCountAt4to6() {
        // Without an explicit anchor the LLM falls back to its own
        // "round-medium-list" bias (≈8-12 items). The "4-6" anchor is
        // load-bearing for the over-planning fix.
        assertThat(PlanningPromptBuilder.SYSTEM_PROMPT)
                .contains("Default to 4-6 steps");
    }

    @Test
    void systemPrompt_callsOutAntiOverDecomposition() {
        // The keyword "over-decompose" is the explicit instruction
        // against splitting "read X / use X" into separate steps.
        // Without it the model tends to expand a 3-step task into 8
        // by adding bookkeeping steps (save, report, summarise).
        assertThat(PlanningPromptBuilder.SYSTEM_PROMPT)
                .contains("over-decompose");
    }

    @Test
    void systemPrompt_clarifiesOneStepEqualsOneReActIteration() {
        // The original wording said "achievable in a single agent
        // turn (one ReAct loop iteration)" — too terse. The model
        // read "one ReAct iteration" as "one tool call". Now spells
        // out that a single iteration includes reading, reasoning,
        // multiple tool calls, and summarising — all within ONE step.
        assertThat(PlanningPromptBuilder.SYSTEM_PROMPT)
                .contains("ONE step");
    }
}
