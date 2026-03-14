package dev.aceclaw.daemon.deferred;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tool available only to deferred agent turns that lets the agent say
 * "not ready yet, check back in N seconds" instead of wasting resources
 * with {@code sleep} in bash commands.
 *
 * <p>A fresh instance is created per deferred execution. After the agent turn
 * completes, the scheduler checks {@link #pendingRequest()} to decide whether
 * to reschedule the action instead of marking it completed.
 *
 * <p>Only one reschedule per turn — if the agent calls this tool multiple times,
 * the last call wins.
 */
public final class RescheduleDeferredTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimum reschedule delay in seconds. */
    static final int MIN_DELAY_SECONDS = 5;

    /** Maximum reschedule delay in seconds. */
    static final int MAX_DELAY_SECONDS = 3600;

    /**
     * A pending reschedule request stored after tool execution.
     *
     * @param delaySeconds seconds to wait before re-checking
     * @param reason       human-readable reason for rescheduling
     */
    public record RescheduleRequest(int delaySeconds, String reason) {}

    private final AtomicReference<RescheduleRequest> pending = new AtomicReference<>();

    @Override
    public String name() {
        return "reschedule_check";
    }

    @Override
    public String description() {
        return """
                Request to reschedule this deferred check for later.

                Use this when the condition you are checking is not yet met
                (e.g., a code review is not posted yet, a build is still running).
                This is much more efficient than using `sleep` in bash commands,
                which wastes a thread and OS process.

                The scheduler will re-run this deferred action after the specified delay.
                """;
    }

    @Override
    public JsonNode inputSchema() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        var props = MAPPER.createObjectNode();

        var delaySec = MAPPER.createObjectNode();
        delaySec.put("type", "integer");
        delaySec.put("description", "Seconds to wait before re-checking (5-3600)");
        delaySec.put("minimum", MIN_DELAY_SECONDS);
        delaySec.put("maximum", MAX_DELAY_SECONDS);
        props.set("delaySeconds", delaySec);

        var reason = MAPPER.createObjectNode();
        reason.put("type", "string");
        reason.put("description", "Why the condition is not yet met");
        props.set("reason", reason);

        schema.set("properties", props);

        var required = MAPPER.createArrayNode();
        required.add("delaySeconds");
        schema.set("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);
        if (input == null || !input.isObject()) {
            return new ToolResult("Invalid input: expected JSON object", true);
        }

        if (!input.has("delaySeconds")) {
            return new ToolResult("Missing required parameter: delaySeconds", true);
        }

        int delaySeconds = input.get("delaySeconds").asInt(0);
        if (delaySeconds < MIN_DELAY_SECONDS || delaySeconds > MAX_DELAY_SECONDS) {
            return new ToolResult(
                    "delaySeconds must be between " + MIN_DELAY_SECONDS
                            + " and " + MAX_DELAY_SECONDS + ", got: " + delaySeconds,
                    true);
        }

        String reason = input.has("reason") ? input.get("reason").asText("") : "";
        if (reason.isBlank()) {
            reason = "condition not met yet";
        }

        var request = new RescheduleRequest(delaySeconds, reason);
        pending.set(request);

        return new ToolResult(
                "Reschedule requested: will re-check in " + delaySeconds + " seconds.\n"
                        + "Reason: " + reason,
                false);
    }

    /**
     * Returns the pending reschedule request, or {@code null} if the tool was not called
     * (or was called with invalid input).
     */
    public RescheduleRequest pendingRequest() {
        return pending.get();
    }
}
