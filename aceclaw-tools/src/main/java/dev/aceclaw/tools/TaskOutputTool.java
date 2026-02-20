package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.BackgroundTask;
import dev.aceclaw.core.agent.SubAgentResult;
import dev.aceclaw.core.agent.SubAgentRunner;
import dev.aceclaw.core.agent.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Tool to check status and retrieve output from background sub-agent tasks.
 *
 * <p>Used in conjunction with the {@code task} tool when {@code run_in_background=true}
 * is specified. The parent agent can poll or block-wait for background task results.
 */
public final class TaskOutputTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TaskOutputTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_TIMEOUT_MS = 300_000; // 5 minutes

    private final SubAgentRunner runner;

    public TaskOutputTool(SubAgentRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "task_output";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("task_id", SchemaBuilder.string(
                        "The task ID returned by the task tool when run_in_background=true."))
                .optionalProperty("timeout_ms", SchemaBuilder.integer(
                        "Maximum time to wait in milliseconds. 0 = non-blocking poll (default). " +
                        "Max: 300000 (5 minutes)."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("task_id") || input.get("task_id").asText().isBlank()) {
            return new ToolResult("Missing required parameter: task_id", true);
        }

        String taskId = input.get("task_id").asText();
        long timeoutMs = 0;
        if (input.has("timeout_ms") && !input.get("timeout_ms").isNull()) {
            timeoutMs = input.get("timeout_ms").asLong(0);
            if (timeoutMs < 0) timeoutMs = 0;
            if (timeoutMs > MAX_TIMEOUT_MS) timeoutMs = MAX_TIMEOUT_MS;
        }

        var task = runner.getBackgroundTask(taskId);
        if (task == null) {
            return new ToolResult(
                    "Unknown task_id: " + taskId + ". The task may have expired " +
                    "(completed tasks are cleaned up after 30 minutes) or the daemon " +
                    "may have been restarted.", true);
        }

        var status = task.currentStatus();

        if (status == BackgroundTask.Status.RUNNING && timeoutMs > 0) {
            // Block and wait
            try {
                SubAgentResult result = runner.awaitBackgroundTask(taskId, timeoutMs);
                if (result != null) {
                    return formatCompletedResult(taskId, result);
                }
                // Should not reach here if awaitBackgroundTask blocks correctly
                return formatRunningStatus(task);
            } catch (TimeoutException e) {
                // Timeout expired — task still running, not an error
                return formatRunningStatus(task);
            } catch (ExecutionException e) {
                return formatFailedResult(taskId, e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return formatRunningStatus(task);
            }
        }

        // Non-blocking check
        return switch (status) {
            case RUNNING -> formatRunningStatus(task);
            case COMPLETED -> {
                try {
                    SubAgentResult result = task.future().get();
                    yield formatCompletedResult(taskId, result);
                } catch (Exception e) {
                    yield formatFailedResult(taskId, e);
                }
            }
            case FAILED -> {
                try {
                    task.future().get(); // Triggers the exception
                    yield formatFailedResult(taskId, new RuntimeException("Unknown error"));
                } catch (ExecutionException e) {
                    yield formatFailedResult(taskId, e.getCause());
                } catch (Exception e) {
                    yield formatFailedResult(taskId, e);
                }
            }
        };
    }

    private ToolResult formatRunningStatus(BackgroundTask task) {
        long elapsedSec = java.time.Duration.between(task.startedAt(), java.time.Instant.now()).toSeconds();
        return new ToolResult(
                "Status: RUNNING\n" +
                "Task ID: " + task.taskId() + "\n" +
                "Agent: " + task.agentType() + "\n" +
                "Elapsed: " + elapsedSec + "s\n" +
                "The task is still running. Use timeout_ms to wait for completion.",
                false);
    }

    private ToolResult formatCompletedResult(String taskId, SubAgentResult result) {
        return new ToolResult(
                "Status: COMPLETED\n" +
                "Task ID: " + taskId + "\n\n" +
                result.text(),
                false);
    }

    private ToolResult formatFailedResult(String taskId, Throwable cause) {
        String message = cause != null ? cause.getMessage() : "Unknown error";
        return new ToolResult(
                "Status: FAILED\n" +
                "Task ID: " + taskId + "\n" +
                "Error: " + message,
                true);
    }
}
