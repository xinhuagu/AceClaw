package dev.aceclaw.core.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskPlanParserTest {

    @Test
    void validJson_parsesCorrectly() {
        var json = """
                {
                  "steps": [
                    {
                      "name": "Research auth",
                      "description": "Read auth files to understand patterns",
                      "requiredTools": ["read_file", "grep"],
                      "fallbackApproach": "Check package.json for auth libs"
                    },
                    {
                      "name": "Implement changes",
                      "description": "Modify the auth module",
                      "requiredTools": ["edit_file"],
                      "fallbackApproach": null
                    }
                  ]
                }
                """;

        var plan = TaskPlanParser.parse(json, "Add OAuth support");

        assertNotNull(plan.planId());
        assertEquals("Add OAuth support", plan.originalGoal());
        assertEquals(2, plan.steps().size());
        assertEquals("Research auth", plan.steps().get(0).name());
        assertEquals(2, plan.steps().get(0).requiredTools().size());
        assertEquals("Check package.json for auth libs", plan.steps().get(0).fallbackApproach());
        assertNull(plan.steps().get(1).fallbackApproach());
        assertInstanceOf(PlanStatus.Draft.class, plan.status());
    }

    @Test
    void jsonInCodeFence_extracted() {
        var text = """
                Here is the plan:
                ```json
                {"steps": [{"name": "Step 1", "description": "Do stuff", "requiredTools": ["bash"]}]}
                ```
                """;

        var plan = TaskPlanParser.parse(text, "test");
        assertEquals(1, plan.steps().size());
        assertEquals("Step 1", plan.steps().get(0).name());
    }

    @Test
    void missingFields_usesDefaults() {
        var json = """
                {"steps": [{"name": "Only name"}]}
                """;

        var plan = TaskPlanParser.parse(json, "test");
        assertEquals(1, plan.steps().size());
        assertEquals("Only name", plan.steps().get(0).name());
        assertEquals("", plan.steps().get(0).description());
        assertTrue(plan.steps().get(0).requiredTools().isEmpty());
        assertNull(plan.steps().get(0).fallbackApproach());
    }

    @Test
    void tooManySteps_truncated() {
        var sb = new StringBuilder("{\"steps\": [");
        for (int i = 0; i < 25; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\": \"Step ").append(i + 1).append("\", \"description\": \"desc\"}");
        }
        sb.append("]}");

        var plan = TaskPlanParser.parse(sb.toString(), "test");
        assertEquals(20, plan.steps().size());
    }

    @Test
    void malformedJson_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskPlanParser.parse("this is not json", "test"));
    }

    @Test
    void emptySteps_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskPlanParser.parse("{\"steps\": []}", "test"));
    }

    @Test
    void emptyInput_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskPlanParser.parse("", "test"));
    }

    @Test
    void nullInput_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskPlanParser.parse(null, "test"));
    }

    @Test
    void jsonWithPreamble_extracted() {
        var text = "Sure! Here's the plan:\n\n{\"steps\": [{\"name\": \"Do it\", \"description\": \"yes\"}]}";
        var plan = TaskPlanParser.parse(text, "test");
        assertEquals(1, plan.steps().size());
    }

    @Test
    void extractJson_codeFence() {
        var text = "```json\n{\"key\": \"value\"}\n```";
        assertEquals("{\"key\": \"value\"}", TaskPlanParser.extractJson(text));
    }

    @Test
    void extractJson_rawBraces() {
        var text = "Some text {\"key\": \"value\"} more text";
        assertEquals("{\"key\": \"value\"}", TaskPlanParser.extractJson(text));
    }

    @Test
    void dependsOn_parsedAndResolved() {
        var json = """
                {
                  "steps": [
                    {"name": "Research", "description": "Find files"},
                    {"name": "Implement", "description": "Write code", "dependsOn": ["Research"]},
                    {"name": "Test", "description": "Run tests", "dependsOn": ["Implement"]}
                  ]
                }
                """;

        var plan = TaskPlanParser.parse(json, "Build feature");

        assertEquals(3, plan.steps().size());
        assertTrue(plan.steps().get(0).dependsOn().isEmpty());

        // "Implement" depends on "Research" — resolved to Research's stepId
        var implementStep = plan.steps().get(1);
        assertEquals(1, implementStep.dependsOn().size());
        assertTrue(implementStep.dependsOn().contains(plan.steps().get(0).stepId()));

        // "Test" depends on "Implement" — resolved to Implement's stepId
        var testStep = plan.steps().get(2);
        assertEquals(1, testStep.dependsOn().size());
        assertTrue(testStep.dependsOn().contains(implementStep.stepId()));
    }

    @Test
    void dependsOn_unknownDepsIgnored() {
        var json = """
                {
                  "steps": [
                    {"name": "Step A", "description": "do A", "dependsOn": ["NonExistent"]},
                    {"name": "Step B", "description": "do B"}
                  ]
                }
                """;

        var plan = TaskPlanParser.parse(json, "test");
        // Unknown dep "NonExistent" should be silently ignored
        assertTrue(plan.steps().get(0).dependsOn().isEmpty());
    }

    @Test
    void dependsOn_cycleDetection_fallsBackToSequential() {
        var json = """
                {
                  "steps": [
                    {"name": "A", "description": "step A", "dependsOn": ["B"]},
                    {"name": "B", "description": "step B", "dependsOn": ["A"]}
                  ]
                }
                """;

        var plan = TaskPlanParser.parse(json, "cycle test");
        // All deps should be cleared because of cycle
        assertTrue(plan.steps().get(0).dependsOn().isEmpty());
        assertTrue(plan.steps().get(1).dependsOn().isEmpty());
    }

    @Test
    void dependsOn_noDeps_noChange() {
        var json = """
                {
                  "steps": [
                    {"name": "Solo", "description": "independent step"}
                  ]
                }
                """;

        var plan = TaskPlanParser.parse(json, "test");
        assertTrue(plan.steps().get(0).dependsOn().isEmpty());
    }

    @Test
    void hasCycle_noCycle() {
        var steps = java.util.List.of(
                new PlannedStep("a", "A", "a", java.util.List.of(), null, java.util.Set.of(), StepStatus.PENDING),
                new PlannedStep("b", "B", "b", java.util.List.of(), null, java.util.Set.of("a"), StepStatus.PENDING)
        );
        assertFalse(TaskPlanParser.hasCycle(steps));
    }

    @Test
    void hasCycle_withCycle() {
        var steps = java.util.List.of(
                new PlannedStep("a", "A", "a", java.util.List.of(), null, java.util.Set.of("b"), StepStatus.PENDING),
                new PlannedStep("b", "B", "b", java.util.List.of(), null, java.util.Set.of("a"), StepStatus.PENDING)
        );
        assertTrue(TaskPlanParser.hasCycle(steps));
    }

    @Test
    void hasCycle_selfCycle() {
        var steps = java.util.List.of(
                new PlannedStep("a", "A", "a", java.util.List.of(), null, java.util.Set.of("a"), StepStatus.PENDING)
        );
        assertTrue(TaskPlanParser.hasCycle(steps));
    }
}
