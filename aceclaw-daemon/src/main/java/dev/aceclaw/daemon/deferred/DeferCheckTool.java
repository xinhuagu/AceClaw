package dev.aceclaw.daemon.deferred;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Tool that allows the agent to schedule a deferred check-back.
 *
 * <p>The agent can say "check on this build in 60 seconds" by calling this tool.
 * The scheduler will wake the session after the delay and run a follow-up turn.
 */
public final class DeferCheckTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private volatile DeferredActionScheduler scheduler;

    /** Session ID is injected per-request by the handler. */
    private volatile String currentSessionId;

    public DeferCheckTool(DeferredActionScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Sets the scheduler (for deferred wiring after construction).
     */
    public void setScheduler(DeferredActionScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Sets the current session ID (called by the handler before each request).
     */
    public void setCurrentSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }

    @Override
    public String name() {
        return "defer_check";
    }

    @Override
    public String description() {
        return """
                Schedule a delayed check-back for this session.

                Use this when you need to wait for something to complete before checking back:
                - A build or test run that takes time
                - A deployment that needs to propagate
                - A process that needs time to finish

                The system will automatically wake up after the specified delay,
                review the current state, and take appropriate action based on the goal.

                Constraints:
                - Delay: 5-3600 seconds
                - Max 3 pending checks per session, 10 globally
                - Max 5 retries on failure
                - Duplicate goals are deduplicated (returns existing action)
                """;
    }

    @Override
    public JsonNode inputSchema() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        var props = MAPPER.createObjectNode();

        var delaySec = MAPPER.createObjectNode();
        delaySec.put("type", "integer");
        delaySec.put("description", "Seconds to wait before checking back (5-3600)");
        delaySec.put("minimum", DeferredActionScheduler.MIN_DELAY_SECONDS);
        delaySec.put("maximum", DeferredActionScheduler.MAX_DELAY_SECONDS);
        props.set("delaySeconds", delaySec);

        var goal = MAPPER.createObjectNode();
        goal.put("type", "string");
        goal.put("description", "What to check or do when the timer fires. Be specific.");
        props.set("goal", goal);

        var maxRetries = MAPPER.createObjectNode();
        maxRetries.put("type", "integer");
        maxRetries.put("description", "Max retry attempts on failure (default 3, max 5)");
        maxRetries.put("minimum", 0);
        maxRetries.put("maximum", DeferredActionScheduler.MAX_RETRIES);
        props.set("maxRetries", maxRetries);

        schema.set("properties", props);

        var required = MAPPER.createArrayNode();
        required.add("delaySeconds");
        required.add("goal");
        schema.set("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);
        if (input == null || !input.isObject()) {
            return new ToolResult("Invalid input: expected JSON object", true);
        }

        // Parse required parameters
        if (!input.has("delaySeconds")) {
            return new ToolResult("Missing required parameter: delaySeconds", true);
        }
        if (!input.has("goal")) {
            return new ToolResult("Missing required parameter: goal", true);
        }

        int delaySeconds = input.get("delaySeconds").asInt();
        String goal = input.get("goal").asText();

        if (goal == null || goal.isBlank()) {
            return new ToolResult("goal must not be empty", true);
        }

        int maxRetries = input.has("maxRetries") ? input.get("maxRetries").asInt(3) : 3;

        if (scheduler == null) {
            return new ToolResult("Deferred action scheduler is not available", true);
        }

        String sessionId = currentSessionId;
        if (sessionId == null || sessionId.isBlank()) {
            return new ToolResult("No active session (internal error)", true);
        }

        try {
            var action = scheduler.schedule(sessionId, delaySeconds, goal, maxRetries);
            return new ToolResult(
                    "Deferred check scheduled successfully.\n" +
                    "  actionId: " + action.actionId() + "\n" +
                    "  runAt: " + TIME_FMT.format(action.runAt()) + "\n" +
                    "  expiresAt: " + TIME_FMT.format(action.expiresAt()) + "\n" +
                    "  maxRetries: " + action.maxRetries() + "\n" +
                    "The system will automatically check back and execute the goal.",
                    false);
        } catch (IllegalArgumentException e) {
            return new ToolResult("Failed to schedule: " + e.getMessage(), true);
        }
    }
}
