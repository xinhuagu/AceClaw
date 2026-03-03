package dev.aceclaw.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextEstimatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // -- BudgetCheck tests --

    @Test
    void checkBudget_withinBudget() {
        var budget = ContextEstimator.checkBudget(
                "You are a helpful assistant.",
                List.of(toolDef("read_file", "Reads a file", smallSchema())),
                List.of(Message.user("Hello")),
                200_000, 16_384);

        assertFalse(budget.overBudget());
        assertEquals(0, budget.excessTokens());
        assertTrue(budget.systemPromptTokens() > 0);
        assertTrue(budget.toolDefinitionTokens() > 0);
        assertTrue(budget.messageTokens() > 0);
        assertEquals(
                budget.systemPromptTokens() + budget.toolDefinitionTokens() + budget.messageTokens(),
                budget.totalEstimated());
        assertEquals(200_000 - 16_384, budget.availableTokens());
    }

    @Test
    void checkBudget_overBudget() {
        // Tiny context window that can't fit everything
        var budget = ContextEstimator.checkBudget(
                "A".repeat(1000),
                List.of(toolDef("bash", "B".repeat(1000), smallSchema())),
                List.of(Message.user("C".repeat(1000))),
                100, 50);

        assertTrue(budget.overBudget());
        assertTrue(budget.excessTokens() > 0);
        assertEquals(100 - 50, budget.availableTokens());
    }

    @Test
    void checkBudget_nullInputs() {
        var budget = ContextEstimator.checkBudget(null, null, null, 200_000, 16_384);

        assertFalse(budget.overBudget());
        assertEquals(0, budget.systemPromptTokens());
        assertEquals(0, budget.toolDefinitionTokens());
        assertEquals(0, budget.messageTokens());
        assertEquals(0, budget.totalEstimated());
    }

    @Test
    void checkBudget_emptyInputs() {
        var budget = ContextEstimator.checkBudget("", List.of(), List.of(), 200_000, 16_384);

        assertFalse(budget.overBudget());
        assertEquals(0, budget.totalEstimated());
    }

    @Test
    void excessTokens_returnsZeroWhenUnderBudget() {
        var check = new ContextEstimator.BudgetCheck(100, 200, 300, 600, 1000, false);
        assertEquals(0, check.excessTokens());
    }

    @Test
    void excessTokens_returnsPositiveWhenOverBudget() {
        var check = new ContextEstimator.BudgetCheck(100, 200, 300, 600, 500, true);
        assertEquals(100, check.excessTokens());
    }

    // -- ToolRegistry.toDefinitions(int) tests --

    @Test
    void toDefinitions_withinBudget_noTruncation() {
        var registry = new ToolRegistry();
        registry.register(simpleTool("read_file", "Short desc"));
        registry.register(simpleTool("bash", "Another short desc"));

        var defs = registry.toDefinitions(10_000);

        assertEquals(2, defs.size());
        assertTrue(defs.stream().noneMatch(d -> d.description().contains("[TRUNCATED]")));
    }

    @Test
    void toDefinitions_overBudget_truncatesLongest() {
        var registry = new ToolRegistry();
        registry.register(simpleTool("small", "Short."));
        registry.register(simpleTool("big", "A".repeat(5000)));

        // Budget: allow only 500 chars total for descriptions
        var defs = registry.toDefinitions(500);

        // The "big" tool should have been truncated
        var bigDef = defs.stream().filter(d -> d.name().equals("big")).findFirst().orElseThrow();
        assertTrue(bigDef.description().contains("[TRUNCATED]"));
        assertTrue(bigDef.description().length() < 5000);

        // The "small" tool should remain unchanged
        var smallDef = defs.stream().filter(d -> d.name().equals("small")).findFirst().orElseThrow();
        assertEquals("Short.", smallDef.description());
    }

    @Test
    void toDefinitions_preservesFirstParagraph() {
        String firstPara = "This is the first paragraph explaining the tool.";
        String fullDesc = firstPara + "\n\n" + "D".repeat(5000);

        var registry = new ToolRegistry();
        registry.register(simpleTool("tool", fullDesc));

        var defs = registry.toDefinitions(100);

        var def = defs.getFirst();
        assertTrue(def.description().startsWith(firstPara));
        assertTrue(def.description().contains("[TRUNCATED]"));
    }

    @Test
    void toDefinitions_tinyOverage_neverExpandsDescription() {
        // When excess is smaller than the marker length ("\n[TRUNCATED]" = 12 chars),
        // truncation must not make the description longer than the original.
        var registry = new ToolRegistry();
        registry.register(simpleTool("edge", "A".repeat(210)));

        // Budget is 1 char less than the description — tiny overage
        var defs = registry.toDefinitions(209);
        var edge = defs.stream().filter(d -> d.name().equals("edge")).findFirst().orElseThrow();

        assertTrue(edge.description().length() <= 210,
                "Truncated description must not be longer than original");
    }

    @Test
    void toDefinitions_emptyRegistry() {
        var registry = new ToolRegistry();
        var defs = registry.toDefinitions(100);
        assertTrue(defs.isEmpty());
    }

    @Test
    void checkBudget_negativeAvailable_clampedToZero() {
        // If contextWindow < maxOutputTokens (misconfiguration), available should be 0, not negative
        var budget = ContextEstimator.checkBudget("prompt", List.of(), List.of(), 100, 200);

        assertEquals(0, budget.availableTokens());
        assertTrue(budget.overBudget());
    }

    // -- Helper methods --

    private static ToolDefinition toolDef(String name, String desc, ObjectNode schema) {
        return new ToolDefinition(name, desc, schema);
    }

    private static ObjectNode smallSchema() {
        var node = JSON.createObjectNode();
        node.put("type", "object");
        return node;
    }

    private static Tool simpleTool(String name, String description) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return smallSchema(); }
            @Override public ToolResult execute(String inputJson) { return new ToolResult("ok", false); }
        };
    }
}
