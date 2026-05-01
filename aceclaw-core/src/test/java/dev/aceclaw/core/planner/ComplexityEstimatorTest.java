package dev.aceclaw.core.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ComplexityEstimatorTest {

    private final ComplexityEstimator estimator = new ComplexityEstimator();

    @Test
    void simpleQuestion_lowScore() {
        var score = estimator.estimate("What is 2+2?");
        assertFalse(score.shouldPlan());
        // Score is 0 here — no signals fire for a plain question. The
        // threshold (now 3) doesn't matter for this case.
        assertEquals(0, score.score());
    }

    @Test
    void singleFileEdit_lowScore() {
        var score = estimator.estimate("Fix the typo in README.md");
        assertFalse(score.shouldPlan());
    }

    @Test
    void multipleActions_highScore() {
        var score = estimator.estimate(
                "First read the config file, and then update the database schema, "
                + "after that run the tests");
        assertTrue(score.shouldPlan());
        assertTrue(score.signals().contains("multiple_actions"));
    }

    @Test
    void multipleFiles_detected() {
        var score = estimator.estimate(
                "Update App.java and Config.java to add the new feature");
        assertTrue(score.signals().contains("multiple_files"));
    }

    @Test
    void refactoring_singleSignalDoesNotPlan_atDefaultThreshold() {
        // At the default threshold (5) a single +3 signal alone is
        // not enough to invoke the planner — the planner adds a real
        // LLM-call cost, and "refactor X" can mean trivially small
        // work (REFACTORING regex matches "extract" too, etc.).
        // Users who want planning on a borderline single-signal
        // prompt use the /plan slash command (forcePlan) instead.
        var score = estimator.estimate("Refactor the authentication module");
        assertTrue(score.signals().contains("refactoring"));
        assertEquals(3, score.score());
        assertFalse(score.shouldPlan());
    }

    @Test
    void refactoring_plusStrongSecondSignal_plans() {
        // refactoring(3) + testing(2) = 5 hits the default threshold.
        // Pins the rule "two distinct +2/+3 signals → plan" so future
        // threshold tweaks have to update an explicit assertion
        // rather than silently drift the policy.
        var withTesting = estimator.estimate("Refactor the auth module and add tests");
        assertTrue(withTesting.signals().contains("refactoring"));
        assertTrue(withTesting.signals().contains("testing"));
        assertTrue(withTesting.score() >= 5);
        assertTrue(withTesting.shouldPlan());
    }

    @Test
    void researchFirst_detected() {
        var score = estimator.estimate(
                "Investigate how the caching layer works and analyze its performance");
        assertTrue(score.signals().contains("research_first"));
    }

    @Test
    void testingCombined_highScore() {
        var score = estimator.estimate(
                "Refactor the auth module, add tests, and deploy to staging");
        assertTrue(score.shouldPlan());
        assertTrue(score.signals().contains("refactoring"));
        assertTrue(score.signals().contains("testing"));
    }

    @Test
    void emptyInput_zeroScore() {
        var score = estimator.estimate("");
        assertEquals(0, score.score());
        assertFalse(score.shouldPlan());
        assertTrue(score.signals().isEmpty());
    }

    @Test
    void nullInput_zeroScore() {
        var score = estimator.estimate(null);
        assertEquals(0, score.score());
        assertFalse(score.shouldPlan());
    }

    @Test
    void longPrompt_detected() {
        // Build a prompt with > 50 words
        var words = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            words.append("word").append(i).append(" ");
        }
        var score = estimator.estimate(words.toString());
        assertTrue(score.signals().contains("long_prompt"));
    }

    @Test
    void customThreshold_respected() {
        var highThreshold = new ComplexityEstimator(100);
        var score = highThreshold.estimate(
                "Refactor everything, add tests, investigate bugs, and then deploy after that");
        assertFalse(score.shouldPlan());
        assertTrue(score.score() > 0);
    }

    @Test
    void numberedList_multipleActions() {
        var score = estimator.estimate(
                "1. Create a new module\n2. Add the service class\n3. Wire it up in config");
        assertTrue(score.signals().contains("multiple_actions"));
    }
}
