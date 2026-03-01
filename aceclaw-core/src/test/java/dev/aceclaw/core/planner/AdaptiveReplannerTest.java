package dev.aceclaw.core.planner;

import dev.aceclaw.core.llm.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveReplannerTest {

    /**
     * Simple mock LlmClient for replanner tests that returns canned non-streaming responses.
     */
    static class ReplanMockLlmClient implements LlmClient {
        private String nextResponse;

        void setNextResponse(String json) {
            this.nextResponse = json;
        }

        @Override
        public LlmResponse sendMessage(LlmRequest request) throws LlmException {
            if (nextResponse == null) {
                throw new LlmException("No mock response configured");
            }
            return new LlmResponse("id", "model",
                    List.of(new ContentBlock.Text(nextResponse)),
                    StopReason.END_TURN, new Usage(100, 50));
        }

        @Override
        public StreamSession streamMessage(LlmRequest request) throws LlmException {
            throw new LlmException("Not implemented for replan tests");
        }

        @Override
        public String provider() { return "mock"; }

        @Override
        public String defaultModel() { return "mock-model"; }
    }

    private ReplanTrigger createTrigger(int attempt) {
        var failedStep = new PlannedStep("step-2", "Parse config",
                "Parse the configuration file", List.of("read_file"), null, StepStatus.FAILED);
        var completedSummaries = List.of(
                new CompletedStepSummary("Read file", "Read the config file", true, "File contents read"));
        var remainingSteps = List.of(
                new PlannedStep("step-3", "Write output", "Write processed output",
                        List.of("write_file"), null, StepStatus.PENDING));
        return new ReplanTrigger("Process config files", failedStep, 1,
                "JSON parse error: unexpected token", completedSummaries, remainingSteps, attempt);
    }

    @Test
    void replan_revisedSteps() throws LlmException {
        var client = new ReplanMockLlmClient();
        client.setNextResponse("""
                {
                  "action": "revise",
                  "rationale": "Use YAML parser instead of JSON",
                  "steps": [
                    {
                      "name": "Parse as YAML",
                      "description": "Use YAML parser for the config file",
                      "requiredTools": ["read_file"],
                      "fallbackApproach": "Try manual parsing"
                    },
                    {
                      "name": "Write output",
                      "description": "Write processed output",
                      "requiredTools": ["write_file"]
                    }
                  ]
                }
                """);

        var replanner = new AdaptiveReplanner(client, "mock-model");
        var result = replanner.replan(createTrigger(1));

        assertInstanceOf(ReplanResult.Revised.class, result);
        var revised = (ReplanResult.Revised) result;
        assertEquals(2, revised.revisedSteps().size());
        assertEquals("Parse as YAML", revised.revisedSteps().get(0).name());
        assertEquals("Use YAML parser instead of JSON", revised.rationale());
        assertEquals(StepStatus.PENDING, revised.revisedSteps().get(0).status());
    }

    @Test
    void replan_escalated() throws LlmException {
        var client = new ReplanMockLlmClient();
        client.setNextResponse("""
                {
                  "action": "escalate",
                  "reason": "Config file is corrupted and cannot be read by any parser"
                }
                """);

        var replanner = new AdaptiveReplanner(client, "mock-model");
        var result = replanner.replan(createTrigger(1));

        assertInstanceOf(ReplanResult.Escalated.class, result);
        var escalated = (ReplanResult.Escalated) result;
        assertTrue(escalated.reason().contains("corrupted"));
    }

    @Test
    void replan_maxAttemptsExceeded() throws LlmException {
        var client = new ReplanMockLlmClient();
        // No response needed -- should short-circuit

        var replanner = new AdaptiveReplanner(client, "mock-model");
        var result = replanner.replan(createTrigger(AdaptiveReplanner.MAX_REPLAN_ATTEMPTS + 1));

        assertInstanceOf(ReplanResult.Escalated.class, result);
        var escalated = (ReplanResult.Escalated) result;
        assertTrue(escalated.reason().contains("Max replan attempts"));
    }

    @Test
    void replan_malformedResponse() {
        var client = new ReplanMockLlmClient();
        client.setNextResponse("This is not JSON at all, just random text...");

        var replanner = new AdaptiveReplanner(client, "mock-model");

        assertThrows(LlmException.class, () -> replanner.replan(createTrigger(1)));
    }

    @Test
    void replan_responsInCodeFence() throws LlmException {
        var client = new ReplanMockLlmClient();
        client.setNextResponse("""
                Here is the revised plan:
                ```json
                {
                  "action": "revise",
                  "rationale": "Alternative approach",
                  "steps": [
                    {
                      "name": "Retry with different method",
                      "description": "Try a different approach"
                    }
                  ]
                }
                ```
                """);

        var replanner = new AdaptiveReplanner(client, "mock-model");
        var result = replanner.replan(createTrigger(1));

        assertInstanceOf(ReplanResult.Revised.class, result);
        var revised = (ReplanResult.Revised) result;
        assertEquals(1, revised.revisedSteps().size());
    }

    @Test
    void buildReplanPrompt_includesContext() {
        var client = new ReplanMockLlmClient();
        var replanner = new AdaptiveReplanner(client, "mock-model");
        var trigger = createTrigger(2);

        var prompt = replanner.buildReplanPrompt(trigger);

        assertTrue(prompt.contains("Process config files"), "Should contain original goal");
        assertTrue(prompt.contains("Read file"), "Should contain completed step name");
        assertTrue(prompt.contains("SUCCESS"), "Should contain completed step status");
        assertTrue(prompt.contains("Parse config"), "Should contain failed step name");
        assertTrue(prompt.contains("JSON parse error"), "Should contain failure reason");
        assertTrue(prompt.contains("Write output"), "Should contain remaining step");
        assertTrue(prompt.contains("attempt 2 of 3"), "Should contain attempt number");
    }

    @Test
    void replan_revisedWithEmptySteps() {
        var client = new ReplanMockLlmClient();
        client.setNextResponse("""
                {
                  "action": "revise",
                  "rationale": "No more steps needed",
                  "steps": []
                }
                """);

        var replanner = new AdaptiveReplanner(client, "mock-model");

        // Empty steps array should throw -- a revision with no steps is invalid
        assertThrows(LlmException.class, () -> replanner.replan(createTrigger(1)));
    }

    @Test
    void replan_unknownAction() {
        var client = new ReplanMockLlmClient();
        client.setNextResponse("""
                { "action": "unknown_action", "reason": "test" }
                """);

        var replanner = new AdaptiveReplanner(client, "mock-model");

        assertThrows(LlmException.class, () -> replanner.replan(createTrigger(1)));
    }

    @Test
    void replan_emptyResponse() {
        var client = new ReplanMockLlmClient();
        client.setNextResponse("");

        var replanner = new AdaptiveReplanner(client, "mock-model");

        // Empty response from LLM
        assertThrows(LlmException.class, () -> replanner.replan(createTrigger(1)));
    }
}
