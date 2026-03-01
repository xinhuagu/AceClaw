package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reads the streaming response for a single agent task on a virtual thread.
 *
 * <p>Replaces the inline {@code while (!done)} loop that was previously in
 * {@code TerminalRepl.processInput()}. Dispatches events to an {@link OutputSink}
 * and routes permission requests through a {@link PermissionBridge}.
 */
public final class TaskStreamReader implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TaskStreamReader.class);

    private final TaskHandle handle;
    private final DaemonConnection connection;
    private final String sessionId;
    private final String fullPrompt;
    private final PermissionBridge permissionBridge;
    private final Consumer<TaskHandle> onComplete;

    public TaskStreamReader(TaskHandle handle, DaemonConnection connection,
                            String sessionId, String fullPrompt,
                            PermissionBridge permissionBridge,
                            Consumer<TaskHandle> onComplete) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.fullPrompt = Objects.requireNonNull(fullPrompt, "fullPrompt");
        this.permissionBridge = Objects.requireNonNull(permissionBridge, "permissionBridge");
        this.onComplete = onComplete;
    }

    @Override
    public void run() {
        try {
            sendPromptRequest();
            readStreamLoop();
        } catch (IOException e) {
            if (!handle.cancelled().get()) {
                log.error("I/O error in task {}: {}", handle.taskId(), e.getMessage(), e);
                handle.setState(TaskHandle.TaskState.FAILED);
                handle.outputSink().onStreamError("Connection error: " + e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error in task {}: {}", handle.taskId(), e.getMessage(), e);
            handle.setState(TaskHandle.TaskState.FAILED);
            handle.outputSink().onStreamError("Unexpected error: " + e.getMessage());
        } finally {
            if (handle.isRunning()) {
                handle.setState(TaskHandle.TaskState.FAILED);
            }
            if (onComplete != null) {
                onComplete.accept(handle);
            }
        }
    }

    private void sendPromptRequest() throws IOException {
        long id = connection.nextRequestId();
        ObjectNode request = connection.objectMapper().createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", "agent.prompt");
        ObjectNode params = connection.objectMapper().createObjectNode();
        params.put("sessionId", sessionId);
        params.put("prompt", fullPrompt);
        request.set("params", params);
        request.put("id", id);
        connection.writeLine(connection.objectMapper().writeValueAsString(request));
    }

    private void readStreamLoop() throws IOException {
        boolean done = false;
        while (!done) {
            String responseLine = connection.readLine();
            if (responseLine == null) {
                handle.outputSink().onConnectionClosed();
                handle.setState(TaskHandle.TaskState.FAILED);
                return;
            }

            JsonNode message = connection.objectMapper().readTree(responseLine);

            if (message.has("id") && !message.get("id").isNull()) {
                // Final JSON-RPC response
                done = true;
                handleFinalResponse(message);
            } else if (message.has("method")) {
                handleNotification(message);
            } else {
                log.debug("Task {}: ignoring unrecognized message", handle.taskId());
            }
        }
    }

    private void handleFinalResponse(JsonNode message) {
        boolean hasError = message.has("error") && !message.get("error").isNull();
        handle.setResult(message);
        handle.markActivity(hasError ? "failed" : "completed");

        if (handle.cancelled().get()) {
            handle.setState(TaskHandle.TaskState.CANCELLED);
        } else if (hasError) {
            handle.setState(TaskHandle.TaskState.FAILED);
        } else {
            handle.setState(TaskHandle.TaskState.COMPLETED);
        }

        handle.outputSink().onTurnComplete(message, hasError);
    }

    private void handleNotification(JsonNode message) {
        String method = message.get("method").asText();
        JsonNode params = message.get("params");
        // Read sink once per notification for consistency within a single event
        var sink = handle.outputSink();

        switch (method) {
            case "stream.thinking" -> {
                if (params != null && params.has("delta")) {
                    handle.markActivity("thinking");
                    sink.onThinkingDelta(params.get("delta").asText());
                }
            }
            case "stream.text" -> {
                if (params != null && params.has("delta")) {
                    handle.markActivity("responding");
                    sink.onTextDelta(params.get("delta").asText());
                }
            }
            case "stream.tool_use" -> {
                if (params != null) {
                    String toolId = params.path("id").asText("");
                    String toolName = params.path("name").asText("unknown");
                    String summary = params.path("summary").asText("");
                    handle.appendToolEvent(toolName, "start", false, 0, summary);
                    handle.markActivity("tool:" + toolName);
                    sink.onToolUse(toolId, toolName, summary);
                }
            }
            case "stream.tool_completed" -> {
                if (params != null) {
                    String toolId = params.path("id").asText("");
                    String toolName = params.path("name").asText("unknown");
                    long durationMs = params.path("durationMs").asLong(0);
                    boolean isError = params.path("isError").asBoolean(false);
                    String error = params.path("error").asText("");
                    handle.appendToolEvent(toolName, "done", isError, durationMs, isError ? error : "");
                    handle.markActivity("tool done");
                    sink.onToolCompleted(toolId, toolName, durationMs, isError, error);
                }
            }
            case "permission.request" -> handlePermissionRequest(params);
            case "stream.error" -> {
                if (params != null && params.has("error")) {
                    handle.appendToolEvent("stream", "error", true, 0, params.get("error").asText());
                    handle.markActivity("stream error");
                    sink.onStreamError(params.get("error").asText());
                }
            }
            case "stream.heartbeat" -> {
                String phase = params != null ? params.path("phase").asText("active") : "active";
                handle.markActivity("heartbeat:" + phase);
            }
            case "stream.cancelled" -> {
                handle.markActivity("cancelled");
                sink.onStreamCancelled();
            }
            case "stream.subagent.start" -> {
                String agentType = params != null ? params.path("agentType").asText("sub-agent") : "sub-agent";
                handle.markActivity("sub-agent:" + agentType);
                sink.onSubAgentStart(params);
            }
            case "stream.subagent.end" -> {
                handle.markActivity("sub-agent done");
                sink.onSubAgentEnd(params);
            }
            case "stream.plan_created" -> {
                handle.markActivity("plan created");
                sink.onPlanCreated(params);
            }
            case "stream.plan_step_started" -> {
                String stepName = params != null ? params.path("stepName").asText("plan step") : "plan step";
                handle.markActivity("plan:" + stepName);
                sink.onPlanStepStarted(params);
            }
            case "stream.plan_step_completed" -> {
                handle.markActivity("plan step done");
                sink.onPlanStepCompleted(params);
            }
            case "stream.plan_completed" -> {
                handle.markActivity("plan done");
                sink.onPlanCompleted(params);
            }
            case "stream.compaction" -> {
                handle.markActivity("compacting");
                sink.onCompaction(params);
            }
            default -> log.debug("Task {}: ignoring notification: {}", handle.taskId(), method);
        }
    }

    private void handlePermissionRequest(JsonNode params) {
        if (params == null) return;

        String tool = params.path("tool").asText("unknown");
        String description = params.path("description").asText("");
        String requestId = params.path("requestId").asText("");

        if (requestId.isEmpty()) {
            log.warn("Task {}: permission.request missing requestId, skipping", handle.taskId());
            return;
        }

        var request = new PermissionBridge.PermissionRequest(
                handle.taskId(), tool, description, requestId);
        handle.markWaitingPermission(description);

        try {
            var answer = permissionBridge.requestPermission(request);

            // Send permission response back to daemon on this task's connection
            ObjectNode responseParams = connection.objectMapper().createObjectNode();
            responseParams.put("requestId", requestId);
            responseParams.put("approved", answer.approved());
            responseParams.put("remember", answer.remember());
            connection.sendNotification("permission.response", responseParams);
            handle.clearWaitingPermission();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Task {}: permission request interrupted", handle.taskId());
            handle.markActivity("permission interrupted");
        } catch (IOException e) {
            log.error("Task {}: failed to send permission response: {}",
                    handle.taskId(), e.getMessage());
            handle.markActivity("permission response failed");
        }
    }
}
