package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.CancellationAware;
import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.HookEvent;
import dev.aceclaw.core.agent.HookExecutor;
import dev.aceclaw.core.agent.HookResult;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.core.agent.Tool.ToolResult;
import dev.aceclaw.core.agent.ToolExecutionContext;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PermissionLevel;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.security.Provenance;
import dev.aceclaw.security.ids.PlanStepId;
import dev.aceclaw.security.ids.PromptId;
import dev.aceclaw.security.ids.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Wraps a {@link Tool} with permission checking, hook execution, and the
 * anti-pattern pre-execution gate.
 *
 * <p>Extracted from {@code StreamingAgentHandler} (originally a static inner
 * class). Package-private — only the handler constructs these. Constructor
 * surface is wide because the wrapper sits at the intersection of permission
 * (manager + structured router), hooks (pre/post), anti-pattern gate (with
 * override + feedback store), runtime metrics, and provenance plumbing —
 * each is an independent concern the handler wires in.
 *
 * <h3>Execution flow</h3>
 *
 * <ol>
 *   <li>PreToolUse hook: may block, may rewrite input.</li>
 *   <li>Permission check via {@link ToolPermissionRouter} (structured for
 *       {@code CapabilityAware} tools, legacy {@code PermissionRequest}
 *       otherwise). Both paths share the single audit pipeline.</li>
 *   <li>Anti-pattern gate evaluation, with optional per-session override and
 *       a fan-out {@code stream.gate} notification on BLOCK / PENALIZE /
 *       OVERRIDE.</li>
 *   <li>For NeedsUserApproval: emit {@code permission.request}, await
 *       response via {@link CancelAwareStreamContext#registerPermissionRequest}
 *       up to {@link CancelAwareStreamContext#PERMISSION_RESPONSE_TIMEOUT_MS},
 *       handle approve/deny/remember/timeout/cancel.</li>
 *   <li>Delegate execution + PostToolUse / PostToolUseFailure hooks (fire-and-
 *       forget on a virtual thread).</li>
 *   <li>If the call ended up running through an OVERRIDE of a candidate-
 *       rule BLOCK and the underlying tool succeeded, record a false-
 *       positive in the gate feedback store — and auto-rollback the
 *       candidate when its false-positive rate crosses the configured
 *       threshold.</li>
 * </ol>
 */
final class PermissionAwareTool implements Tool, CancellationAware {

    private static final Logger log = LoggerFactory.getLogger(PermissionAwareTool.class);

    /**
     * Permission level assignments for known tools. MCP tools default to
     * EXECUTE (handled separately in {@link #execute}); anything not in this
     * map falls back to EXECUTE so an unrecognised tool errs on the
     * "prompt before running" side.
     *
     * <p>Moved here from {@code StreamingAgentHandler} alongside the inner-
     * class extraction — this map is consulted only on the per-tool
     * permission lookup, nowhere else in the handler. Local to the wrapper
     * keeps "what permission does each tool default to?" co-located with
     * the wrapper that asks the question.
     */
    private static final Map<String, PermissionLevel> TOOL_PERMISSION_LEVELS = Map.ofEntries(
            Map.entry("read_file", PermissionLevel.READ),
            Map.entry("glob", PermissionLevel.READ),
            Map.entry("grep", PermissionLevel.READ),
            Map.entry("list_directory", PermissionLevel.READ),
            Map.entry("web_fetch", PermissionLevel.READ),
            Map.entry("web_search", PermissionLevel.READ),
            Map.entry("screen_capture", PermissionLevel.READ),
            Map.entry("memory", PermissionLevel.READ),
            Map.entry("task", PermissionLevel.READ),
            Map.entry("task_output", PermissionLevel.READ),
            Map.entry("write_file", PermissionLevel.WRITE),
            Map.entry("edit_file", PermissionLevel.WRITE),
            Map.entry("bash", PermissionLevel.EXECUTE),
            Map.entry("browser", PermissionLevel.EXECUTE),
            Map.entry("applescript", PermissionLevel.EXECUTE),
            Map.entry("cron", PermissionLevel.READ),
            Map.entry("defer_check", PermissionLevel.READ)
    );

    private final Tool delegate;
    private final PermissionManager permissionManager;
    private final CancelAwareStreamContext context;
    private final ObjectMapper objectMapper;
    private final HookExecutor hookExecutor;
    private final String sessionId;
    private final String cwd;
    private final AntiPatternPreExecutionGate antiPatternGate;
    private final Supplier<StreamingAgentHandler.AntiPatternGateOverrideStatus> antiPatternOverrideSupplier;
    private final AntiPatternGateFeedbackStore antiPatternGateFeedbackStore;
    private final CandidateStore candidateStore;
    private final RuntimeMetricsExporter metricsExporter;
    // #480 PR 3: Provenance-chain inputs. {@code promptId} is per-request
    // (constant across all tool calls in this prompt); the step-id
    // supplier is read lazily on each tool call so it captures the plan
    // step that's active at the moment of the permission check, not the
    // step that was active when the registry was constructed.
    private final PromptId promptId;
    private final Supplier<String> currentStepIdSupplier;

    PermissionAwareTool(Tool delegate, PermissionManager permissionManager,
                        CancelAwareStreamContext context, ObjectMapper objectMapper,
                        HookExecutor hookExecutor, String sessionId, String cwd,
                        AntiPatternPreExecutionGate antiPatternGate,
                        Supplier<StreamingAgentHandler.AntiPatternGateOverrideStatus> antiPatternOverrideSupplier,
                        AntiPatternGateFeedbackStore antiPatternGateFeedbackStore,
                        CandidateStore candidateStore,
                        RuntimeMetricsExporter metricsExporter,
                        PromptId promptId,
                        Supplier<String> currentStepIdSupplier) {
        this.delegate = delegate;
        this.permissionManager = permissionManager;
        this.context = context;
        this.objectMapper = objectMapper;
        this.hookExecutor = hookExecutor;
        this.sessionId = sessionId;
        this.cwd = cwd;
        this.antiPatternGate = antiPatternGate;
        this.antiPatternOverrideSupplier = antiPatternOverrideSupplier;
        this.antiPatternGateFeedbackStore = antiPatternGateFeedbackStore;
        this.candidateStore = candidateStore;
        this.metricsExporter = metricsExporter;
        this.promptId = Objects.requireNonNull(promptId, "promptId");
        this.currentStepIdSupplier = Objects.requireNonNull(currentStepIdSupplier, "currentStepIdSupplier");
    }

    @Override
    public void setCancellationToken(CancellationToken token) {
        if (delegate instanceof CancellationAware ca) {
            ca.setCancellationToken(token);
        }
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public JsonNode inputSchema() {
        return delegate.inputSchema();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        // --- PreToolUse hooks (before permission check) ---
        String effectiveInputJson = inputJson;
        if (hookExecutor != null) {
            try {
                var toolInput = objectMapper.readTree(inputJson);
                if (toolInput == null || !toolInput.isObject()) {
                    toolInput = objectMapper.createObjectNode();
                }
                var preEvent = new HookEvent.PreToolUse(sessionId, cwd, delegate.name(), toolInput);
                var hookResult = hookExecutor.execute(preEvent);

                switch (hookResult) {
                    case HookResult.Block blocked -> {
                        log.info("Tool {} blocked by PreToolUse hook: {}", delegate.name(), blocked.reason());
                        return new ToolResult("Blocked by hook: " + blocked.reason(), true);
                    }
                    case HookResult.Proceed proceed -> {
                        if (proceed.updatedInput() != null) {
                            effectiveInputJson = objectMapper.writeValueAsString(proceed.updatedInput());
                            log.debug("Tool {} input modified by PreToolUse hook", delegate.name());
                        }
                    }
                    case HookResult.Error err ->
                            log.warn("PreToolUse hook error for {}: {}", delegate.name(), err.message());
                }
            } catch (Exception e) {
                log.warn("PreToolUse hook failed for {}: {}", delegate.name(), e.getMessage());
            }
        }

        // --- Permission check ---
        // Determine the permission level for this tool
        // MCP tools default to EXECUTE since they can have side effects
        var level = delegate.name().startsWith("mcp__")
                ? PermissionLevel.EXECUTE
                : TOOL_PERMISSION_LEVELS.getOrDefault(delegate.name(), PermissionLevel.EXECUTE);

        // Build a human-readable description of what the tool will do
        var toolDescription = buildToolDescription(delegate.name(), effectiveInputJson);
        final String finalInputJson = effectiveInputJson;

        // #480 PR 2: routing the call through ToolPermissionRouter keeps
        // the structured-vs-legacy branching logic in one testable place.
        // CapabilityAware tools take the structured path; everything else
        // hits the legacy PermissionRequest entry point. Both ultimately
        // share PermissionManager's single decision/audit pipeline.
        //
        // #480 PR 3: build the full Provenance here so the structured
        // path receives the actual rootPrompt and (when running inside a
        // plan) the active planStepId. The supplier read lazily picks up
        // whatever step is current at this exact moment — sequencing
        // matters because a single agent.prompt can transition through
        // multiple plan steps.
        var stepIdRaw = currentStepIdSupplier.get();
        var provenance = new Provenance(
                Optional.of(promptId),
                Optional.of(new SessionId(sessionId)),
                stepIdRaw == null
                        ? Optional.<PlanStepId>empty()
                        : Optional.of(new PlanStepId(stepIdRaw)),
                0,                          // subAgentDepth — top-level main loop
                List.of()                   // chain — populated piecemeal in follow-ups
        );
        var decision = ToolPermissionRouter.check(
                delegate, finalInputJson, provenance, toolDescription, level,
                permissionManager, objectMapper);
        var overrideStatus = antiPatternOverrideSupplier != null
                ? antiPatternOverrideSupplier.get()
                : new StreamingAgentHandler.AntiPatternGateOverrideStatus(sessionId, delegate.name(), false, 0L, "");
        var evaluatedGateDecision = antiPatternGate != null
                ? antiPatternGate.evaluate(delegate.name(), finalInputJson, toolDescription)
                : AntiPatternPreExecutionGate.Decision.allow();
        var antiPatternDecision = !overrideStatus.active()
                ? evaluatedGateDecision
                : AntiPatternPreExecutionGate.Decision.allow();

        if (overrideStatus.active()) {
            emitGateNotification("OVERRIDE", overrideStatus, evaluatedGateDecision);
        }

        if (antiPatternDecision.action() == AntiPatternPreExecutionGate.Action.BLOCK) {
            emitGateNotification("BLOCK", overrideStatus, antiPatternDecision);
            recordBlockedRule(antiPatternDecision.ruleId());
            log.info("Anti-pattern gate blocked tool {} (ruleId={})",
                    delegate.name(), antiPatternDecision.ruleId());
            return new ToolResult(
                    "Anti-pattern gate blocked execution: "
                            + "{gate=anti_pattern_preexec, action=BLOCK, ruleId="
                            + antiPatternDecision.ruleId()
                            + ", reason=\"" + antiPatternDecision.reason() + "\""
                            + ", fallback=\"" + antiPatternDecision.fallback() + "\"}",
                    true);
        }
        if (antiPatternDecision.action() == AntiPatternPreExecutionGate.Action.PENALIZE) {
            emitGateNotification("PENALIZE", overrideStatus, antiPatternDecision);
            log.info("Anti-pattern gate penalized tool {} (ruleId={})",
                    delegate.name(), antiPatternDecision.ruleId());
        }

        switch (decision) {
            case PermissionDecision.Approved ignored -> {
                if (metricsExporter != null) metricsExporter.recordPermissionDecision(false);
                var result = executeWithPostHooks(finalInputJson);
                maybeRecordFalsePositiveAndRollback(overrideStatus, evaluatedGateDecision, result);
                return result;
            }

            case PermissionDecision.Denied denied -> {
                if (metricsExporter != null) metricsExporter.recordPermissionDecision(true);
                log.info("Tool {} denied: {}", delegate.name(), denied.reason());
                return new ToolResult("Permission denied: " + denied.reason(), true);
            }

            case PermissionDecision.NeedsUserApproval approval -> {
                // Send permission request to the client and await via per-request future
                var requestId = "perm-" + UUID.randomUUID();
                long timeoutMs = CancelAwareStreamContext.PERMISSION_RESPONSE_TIMEOUT_MS;

                var future = context.registerPermissionRequest(requestId, sessionId);
                if (future.isCancelled()) {
                    return new ToolResult("Permission denied: request cancelled", true);
                }
                try {
                    var params = objectMapper.createObjectNode();
                    params.put("tool", delegate.name());
                    params.put("description", approval.prompt());
                    params.put("requestId", requestId);
                    // Stamp the originating tool_use id so the
                    // dashboard reducer can mark the SPECIFIC tool
                    // node as awaiting (instead of falling back to
                    // activeNodeId, which collapses onto whichever
                    // parallel tool_use was emitted last). Without
                    // this, parallel tool calls each fire their own
                    // permission.request but only one node shows
                    // the "click ✓/✗" chip — the user can only
                    // approve one from the dashboard.
                    var toolUseId = ToolExecutionContext.currentToolUseId();
                    if (toolUseId != null) {
                        params.put("toolUseId", toolUseId);
                    }
                    context.sendNotification("permission.request", params);

                    // Each thread waits on its own future — no cross-delivery possible
                    var responseMsg = future.get(timeoutMs, TimeUnit.MILLISECONDS);

                    // Parse the permission response
                    var responseParams = responseMsg.get("params");
                    if (responseParams == null) {
                        return new ToolResult("Permission denied: invalid response from client", true);
                    }

                    boolean approved = responseParams.has("approved")
                            && responseParams.get("approved").asBoolean(false);
                    boolean remember = responseParams.has("remember")
                            && responseParams.get("remember").asBoolean(false);

                    if (!approved) {
                        if (metricsExporter != null) metricsExporter.recordPermissionDecision(true);
                        log.info("Tool {} denied by user (requestId={})", delegate.name(), requestId);
                        return new ToolResult("Permission denied by user", true);
                    }
                    if (metricsExporter != null) metricsExporter.recordPermissionDecision(false);

                    // If user chose "remember", grant session-level approval.
                    // Per-session scope (issue #456): the allow only applies
                    // to THIS session — clicking Always Allow in workspace A
                    // no longer silently disables the prompt in workspace B.
                    if (remember) {
                        permissionManager.approveForSession(sessionId, delegate.name());
                    }

                    log.info("Tool {} approved by user (requestId={}, remember={})",
                            delegate.name(), requestId, remember);
                    var result = executeWithPostHooks(finalInputJson);
                    maybeRecordFalsePositiveAndRollback(overrideStatus, evaluatedGateDecision, result);
                    return result;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (metricsExporter != null) metricsExporter.recordPermissionDecision(true);
                    log.info("Tool {} permission request interrupted (requestId={})",
                            delegate.name(), requestId);
                    return new ToolResult("Permission denied: request interrupted", true);
                } catch (TimeoutException e) {
                    if (metricsExporter != null) metricsExporter.recordPermissionDecision(true);
                    log.info("Tool {} permission response timed out (requestId={})",
                            delegate.name(), requestId);
                    long timeoutSeconds = TimeUnit.MILLISECONDS.toSeconds(timeoutMs);
                    return new ToolResult(
                            "Permission pending timeout: no response from client within "
                                    + timeoutSeconds + "s", true);
                } catch (CancellationException e) {
                    log.info("Tool {} permission request cancelled (requestId={})",
                            delegate.name(), requestId);
                    return new ToolResult("Permission denied: request cancelled", true);
                } catch (ExecutionException e) {
                    log.error("Failed to receive permission response for tool {}: {}",
                            delegate.name(), e.getMessage());
                    return new ToolResult("Permission check failed: " + e.getMessage(), true);
                } catch (IOException e) {
                    log.error("Failed to communicate permission request for tool {}: {}",
                            delegate.name(), e.getMessage());
                    return new ToolResult("Permission check failed: " + e.getMessage(), true);
                } finally {
                    context.unregisterPermissionRequest(requestId);
                }
            }
        }
    }

    /**
     * Executes the tool and fires PostToolUse or PostToolUseFailure hooks.
     */
    private ToolResult executeWithPostHooks(String inputJson) throws Exception {
        ToolResult result;
        try {
            result = delegate.execute(inputJson);
        } catch (Exception e) {
            // Fire PostToolUseFailure hook (non-blocking, fire-and-forget)
            var msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            firePostHookAsync(inputJson, null, msg);
            throw e;
        }

        // Fire PostToolUse or PostToolUseFailure based on result
        if (result.isError()) {
            var msg = result.output() != null ? result.output() : "Tool error";
            firePostHookAsync(inputJson, null, msg);
        } else {
            firePostHookAsync(inputJson, result.output(), null);
        }

        return result;
    }

    /**
     * Fires PostToolUse or PostToolUseFailure hooks on a virtual thread (fire-and-forget).
     */
    private void firePostHookAsync(String inputJson, String output, String error) {
        if (hookExecutor == null) return;
        Thread.ofVirtual().name("hook-post-" + delegate.name()).start(() -> {
            try {
                var toolInput = objectMapper.readTree(inputJson);
                if (toolInput == null || !toolInput.isObject()) {
                    toolInput = objectMapper.createObjectNode();
                }
                HookEvent event;
                if (error != null) {
                    event = new HookEvent.PostToolUseFailure(
                            sessionId, cwd, delegate.name(), toolInput, error);
                } else {
                    event = new HookEvent.PostToolUse(
                            sessionId, cwd, delegate.name(), toolInput,
                            output != null ? output : "");
                }
                hookExecutor.execute(event);
            } catch (Exception e) {
                log.warn("Post-tool hook failed for {}: {}", delegate.name(), e.getMessage());
            }
        });
    }

    /**
     * Builds a human-readable description of what the tool will do based on its input.
     */
    private String buildToolDescription(String toolName, String inputJson) {
        try {
            var input = objectMapper.readTree(inputJson);
            return switch (toolName) {
                case "bash" -> "Execute: " + (input.has("command")
                        ? input.get("command").asText() : "(unknown command)");
                case "write_file" -> "Write to file: " + (input.has("file_path")
                        ? input.get("file_path").asText() : "(unknown path)");
                case "edit_file" -> "Edit file: " + (input.has("file_path")
                        ? input.get("file_path").asText() : "(unknown path)");
                case "read_file" -> "Read file: " + (input.has("file_path")
                        ? input.get("file_path").asText() : "(unknown path)");
                case "glob" -> "Search files: " + (input.has("pattern")
                        ? input.get("pattern").asText() : "(unknown pattern)");
                case "grep" -> "Search content: " + (input.has("pattern")
                        ? input.get("pattern").asText() : "(unknown pattern)");
                case "list_directory" -> "List directory: " + (input.has("path")
                        ? input.get("path").asText() : "(working directory)");
                case "web_fetch" -> "Fetch URL: " + (input.has("url")
                        ? input.get("url").asText() : "(unknown url)");
                case "web_search" -> "Web search: " + (input.has("query")
                        ? input.get("query").asText() : "(unknown query)");
                case "browser" -> "Browser " + (input.has("action")
                        ? input.get("action").asText() : "(unknown action)") +
                        (input.has("url") ? ": " + input.get("url").asText() : "");
                case "applescript" -> "Execute AppleScript (" +
                        (input.has("script") ? input.get("script").asText().length() + " chars" : "unknown") + ")";
                case "screen_capture" -> "Capture screenshot" +
                        (input.has("region") ? " (region: " + input.get("region").asText() + ")" : "");
                default -> {
                    if (toolName.startsWith("mcp__")) {
                        // MCP tools: show server and tool name
                        var parts = toolName.split("__", 3);
                        var server = parts.length > 1 ? parts[1] : "unknown";
                        var tool = parts.length > 2 ? parts[2] : "unknown";
                        yield "MCP [" + server + "] " + tool;
                    } else {
                        yield "Execute tool: " + toolName;
                    }
                }
            };
        } catch (Exception e) {
            return "Execute tool: " + toolName;
        }
    }

    private void emitGateNotification(String action,
                                      StreamingAgentHandler.AntiPatternGateOverrideStatus overrideStatus,
                                      AntiPatternPreExecutionGate.Decision gateDecision) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("sessionId", sessionId);
            params.put("tool", delegate.name());
            params.put("gate", "anti_pattern_preexec");
            params.put("action", action);
            if (gateDecision != null) {
                if (!gateDecision.ruleId().isBlank()) params.put("ruleId", gateDecision.ruleId());
                if (!gateDecision.reason().isBlank()) params.put("reason", gateDecision.reason());
                if (!gateDecision.fallback().isBlank()) params.put("fallback", gateDecision.fallback());
            }
            if (overrideStatus != null && overrideStatus.active()) {
                params.put("override", true);
                params.put("overrideTtlSeconds", overrideStatus.ttlSecondsRemaining());
                if (!overrideStatus.reason().isBlank()) {
                    params.put("overrideReason", overrideStatus.reason());
                }
            } else {
                params.put("override", false);
            }
            context.sendNotification("stream.gate", params);
        } catch (Exception e) {
            log.debug("Failed to emit stream.gate notification: {}", e.getMessage());
        }
    }

    private void recordBlockedRule(String ruleId) {
        if (antiPatternGateFeedbackStore == null || ruleId == null || ruleId.isBlank()) {
            return;
        }
        antiPatternGateFeedbackStore.recordBlocked(ruleId);
    }

    private void maybeRecordFalsePositiveAndRollback(
            StreamingAgentHandler.AntiPatternGateOverrideStatus overrideStatus,
            AntiPatternPreExecutionGate.Decision evaluatedGateDecision,
            ToolResult toolResult) {
        if (antiPatternGateFeedbackStore == null || toolResult == null || toolResult.isError()) {
            return;
        }
        if (overrideStatus == null || !overrideStatus.active()) {
            return;
        }
        if (evaluatedGateDecision == null || evaluatedGateDecision.action() != AntiPatternPreExecutionGate.Action.BLOCK) {
            return;
        }
        String ruleId = evaluatedGateDecision.ruleId();
        if (ruleId == null || ruleId.isBlank()) {
            return;
        }
        boolean shouldRollback = antiPatternGateFeedbackStore.recordFalsePositive(ruleId);
        if (shouldRollback) {
            autoRollbackCandidateRule(ruleId);
        }
    }

    private void autoRollbackCandidateRule(String ruleId) {
        if (candidateStore == null || !ruleId.startsWith("candidate:")) {
            return;
        }
        String candidateId = ruleId.substring("candidate:".length());
        if (candidateId.isBlank()) {
            return;
        }
        try {
            var rollback = candidateStore.rollbackPromoted(
                    candidateId, "AUTO_ROLLBACK_FALSE_POSITIVE_RATE");
            if (rollback.isPresent()) {
                log.info("Auto-rolled back anti-pattern candidate due to false-positive gate rate: {}",
                        candidateId);
            }
        } catch (Exception e) {
            log.warn("Failed auto rollback for candidate {}: {}", candidateId, e.getMessage());
        }
    }
}
