package dev.aceclaw.daemon.cron;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.daemon.heartbeat.HeartbeatRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Tool for managing scheduled cron jobs.
 *
 * <p>Supports listing jobs, adding/updating jobs, removing jobs, and viewing status.
 */
public final class CronTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JobStore jobStore;
    private final BooleanSupplier schedulerRunning;
    private final Clock clock;

    public CronTool(JobStore jobStore, BooleanSupplier schedulerRunning) {
        this(jobStore, schedulerRunning, Clock.systemDefaultZone());
    }

    CronTool(JobStore jobStore, BooleanSupplier schedulerRunning, Clock clock) {
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore cannot be null");
        this.schedulerRunning = Objects.requireNonNull(schedulerRunning, "schedulerRunning cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @Override
    public String name() {
        return "cron";
    }

    @Override
    public String description() {
        return """
                Manage scheduled recurring tasks (cron jobs) for this workspace.

                Use this when the user asks for automation like:
                - every day / weekly / every N minutes
                - recurring reports, sync, checks, cleanup, reminders

                Actions:
                - list: show all scheduled jobs
                - status: scheduler state and job health (optionally one job by id)
                - add: create or update a job
                - remove: delete a job

                Notes:
                - Heartbeat jobs (id prefix 'hb-') are managed by HEARTBEAT.md sync and cannot be edited here.
                - Expressions use standard 5-field cron format: minute hour dayOfMonth month dayOfWeek.
                """;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("action", stringEnum(
                "Operation to perform",
                "list", "status", "add", "remove"));
        properties.set("id", stringNode(
                "Job ID. Required for add/remove, optional for status."));
        properties.set("name", stringNode(
                "Human-readable job name (optional for add; defaults to id)."));
        properties.set("expression", stringNode(
                "5-field cron expression, e.g. '0 8 * * *' (required for add)."));
        properties.set("prompt", stringNode(
                "Task prompt executed by the agent (required for add)."));
        properties.set("allowedTools", stringArrayNode(
                "Optional allowlist of tool names for this job. Empty means read-only defaults."));
        properties.set("timeoutSeconds", integerNode(
                "Optional timeout per run in seconds. Default 300."));
        properties.set("maxIterations", integerNode(
                "Optional max agent loop iterations per run. Default 15."));
        properties.set("enabled", boolNode(
                "Optional enabled flag for add. Default true."));

        schema.set("properties", properties);
        ArrayNode required = MAPPER.createArrayNode();
        required.add("action");
        schema.set("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        if (inputJson == null || inputJson.isBlank()) {
            return new ToolResult("Empty or invalid JSON input", true);
        }
        JsonNode input = MAPPER.readTree(inputJson);
        if (input == null) {
            return new ToolResult("Empty or invalid JSON input", true);
        }
        if (!input.has("action") || input.get("action").asText().isBlank()) {
            return new ToolResult("Missing required parameter: action", true);
        }

        String action = input.get("action").asText().toLowerCase();
        return switch (action) {
            case "list" -> listJobs();
            case "status" -> status(input);
            case "add" -> addJob(input);
            case "remove" -> removeJob(input);
            default -> new ToolResult(
                    "Unknown action: " + action + ". Valid actions: list, status, add, remove", true);
        };
    }

    private ToolResult listJobs() {
        var allJobs = jobStore.all();
        var jobs = (allJobs != null ? allJobs : List.<CronJob>of()).stream()
                .sorted(Comparator.comparing(CronJob::id))
                .toList();
        if (jobs.isEmpty()) {
            return new ToolResult("No scheduled jobs.", false);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Scheduled jobs (").append(jobs.size()).append(")\n");
        for (CronJob job : jobs) {
            sb.append("- ").append(job.id());
            if (job.id().startsWith(HeartbeatRunner.JOB_ID_PREFIX)) {
                sb.append(" [heartbeat]");
            }
            sb.append(" | expr=").append(job.expression());
            sb.append(" | enabled=").append(job.enabled());
            sb.append(" | next=").append(nextFire(job));
            if (job.lastRunAt() != null) {
                sb.append(" | lastRun=").append(formatInstant(job.lastRunAt()));
            }
            if (job.lastError() != null && !job.lastError().isBlank()) {
                sb.append(" | failures=").append(job.consecutiveFailures());
            }
            sb.append('\n');
        }
        return new ToolResult(sb.toString().stripTrailing(), false);
    }

    private ToolResult status(JsonNode input) {
        if (input.has("id") && !input.get("id").asText().isBlank()) {
            return statusOne(input.get("id").asText().trim());
        }
        var jobs = jobStore.all();
        long enabled = jobs.stream().filter(CronJob::enabled).count();
        long heartbeat = jobs.stream()
                .filter(j -> j.id().startsWith(HeartbeatRunner.JOB_ID_PREFIX))
                .count();
        long unhealthy = jobs.stream()
                .filter(j -> j.consecutiveFailures() > 0)
                .count();

        String out = """
                Cron scheduler: %s
                Total jobs: %d
                Enabled: %d
                Heartbeat-managed: %d
                Jobs with recent failures: %d
                """.formatted(
                schedulerRunning.getAsBoolean() ? "running" : "stopped",
                jobs.size(), enabled, heartbeat, unhealthy).strip();
        return new ToolResult(out, false);
    }

    private ToolResult statusOne(String id) {
        var maybe = jobStore.get(id);
        if (maybe.isEmpty()) {
            return new ToolResult("Job not found: " + id, true);
        }
        CronJob job = maybe.get();
        String out = """
                Job: %s
                Name: %s
                Expression: %s
                Enabled: %s
                Next fire: %s
                Last run: %s
                Last error: %s
                Consecutive failures: %d
                Allowed tools: %s
                Timeout seconds: %d
                Max iterations: %d
                """.formatted(
                job.id(), job.name(), job.expression(), job.enabled(),
                nextFire(job),
                formatNullableInstant(job.lastRunAt()),
                job.lastError() == null || job.lastError().isBlank() ? "(none)" : job.lastError(),
                job.consecutiveFailures(),
                job.allowedTools().isEmpty() ? "(read-only defaults)" : String.join(", ", job.allowedTools()),
                job.timeoutSeconds(), job.maxIterations()).strip();
        return new ToolResult(out, false);
    }

    private ToolResult addJob(JsonNode input) {
        String id = text(input, "id");
        if (id == null) {
            return new ToolResult("Missing required parameter for add: id", true);
        }
        if (id.startsWith(HeartbeatRunner.JOB_ID_PREFIX)) {
            return new ToolResult(
                    "Heartbeat job IDs (prefix '" + HeartbeatRunner.JOB_ID_PREFIX
                            + "') are managed via HEARTBEAT.md and cannot be added directly.", true);
        }
        String expression = text(input, "expression");
        if (expression == null) {
            return new ToolResult("Missing required parameter for add: expression", true);
        }
        String prompt = text(input, "prompt");
        if (prompt == null) {
            return new ToolResult("Missing required parameter for add: prompt", true);
        }
        try {
            CronExpression.parse(expression);
        } catch (IllegalArgumentException e) {
            return new ToolResult("Invalid cron expression '" + expression + "': " + e.getMessage(), true);
        }

        var existing = jobStore.get(id);
        String name = text(input, "name");
        if (name == null || name.isBlank()) {
            name = existing.map(CronJob::name).orElse(id);
        }
        Set<String> allowedTools = parseAllowedTools(input, existing.map(CronJob::allowedTools).orElse(Set.of()));
        int timeout = integer(input, "timeoutSeconds",
                existing.map(CronJob::timeoutSeconds).orElse(CronJob.DEFAULT_TIMEOUT_SECONDS));
        int maxIterations = integer(input, "maxIterations",
                existing.map(CronJob::maxIterations).orElse(CronJob.DEFAULT_MAX_ITERATIONS));
        boolean enabled = input.has("enabled")
                ? input.get("enabled").asBoolean(true)
                : existing.map(CronJob::enabled).orElse(true);
        if (timeout <= 0) {
            return new ToolResult("timeoutSeconds must be > 0", true);
        }
        if (maxIterations <= 0) {
            return new ToolResult("maxIterations must be > 0", true);
        }

        CronJob job = new CronJob(
                id, name, expression, prompt,
                allowedTools,
                timeout,
                maxIterations,
                enabled,
                existing.map(CronJob::retryBackoff).orElse(CronJob.DEFAULT_RETRY_BACKOFF),
                existing.map(CronJob::lastRunAt).orElse(null),
                existing.map(CronJob::lastError).orElse(null),
                existing.map(CronJob::consecutiveFailures).orElse(0));
        jobStore.put(job);
        try {
            jobStore.save();
        } catch (Exception e) {
            return new ToolResult("Failed to save cron job '" + id + "': " + e.getMessage(), true);
        }
        return new ToolResult("Cron job saved: " + id + " (next fire: " + nextFire(job) + ")", false);
    }

    private ToolResult removeJob(JsonNode input) {
        String id = text(input, "id");
        if (id == null) {
            return new ToolResult("Missing required parameter for remove: id", true);
        }
        if (id.startsWith(HeartbeatRunner.JOB_ID_PREFIX)) {
            return new ToolResult(
                    "Heartbeat jobs are managed by HEARTBEAT.md sync and cannot be removed via cron tool.", true);
        }
        boolean removed = jobStore.remove(id);
        if (!removed) {
            return new ToolResult("Job not found: " + id, true);
        }
        try {
            jobStore.save();
        } catch (Exception e) {
            return new ToolResult("Removed in memory but failed to persist removal for '" + id + "': " + e.getMessage(),
                    true);
        }
        return new ToolResult("Cron job removed: " + id, false);
    }

    private String nextFire(CronJob job) {
        try {
            Instant next = CronExpression.parse(job.expression()).nextFireTime(Instant.now(clock));
            return next == null ? "(none within 4 years)" : formatInstant(next);
        } catch (IllegalArgumentException e) {
            return "(invalid expression: " + e.getMessage() + ")";
        }
    }

    private static String text(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String v = node.get(field).asText().trim();
        return v.isBlank() ? null : v;
    }

    private static int integer(JsonNode node, String field, int defaultValue) {
        if (!node.has(field) || node.get(field).isNull()) {
            return defaultValue;
        }
        return node.get(field).asInt(defaultValue);
    }

    private static Set<String> parseAllowedTools(JsonNode input, Set<String> defaultValue) {
        if (!input.has("allowedTools") || input.get("allowedTools").isNull()) {
            return defaultValue;
        }
        JsonNode raw = input.get("allowedTools");
        Set<String> out = new HashSet<>();
        if (raw.isArray()) {
            for (JsonNode item : raw) {
                String tool = item.asText("").trim();
                if (!tool.isBlank()) {
                    out.add(tool);
                }
            }
            return out;
        }
        String text = raw.asText("").trim();
        if (text.isBlank()) {
            return Set.of();
        }
        for (String item : text.split(",")) {
            String tool = item.trim();
            if (!tool.isBlank()) {
                out.add(tool);
            }
        }
        return out;
    }

    private static String formatNullableInstant(Instant at) {
        return at == null ? "(never)" : formatInstant(at);
    }

    private static String formatInstant(Instant at) {
        return TIME_FMT.format(at);
    }

    private static ObjectNode stringNode(String description) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private static ObjectNode integerNode(String description) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }

    private static ObjectNode boolNode(String description) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "boolean");
        node.put("description", description);
        return node;
    }

    private static ObjectNode stringArrayNode(String description) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "array");
        node.put("description", description);
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "string");
        node.set("items", item);
        return node;
    }

    private static ObjectNode stringEnum(String description, String... values) {
        ObjectNode node = stringNode(description);
        ArrayNode enumValues = MAPPER.createArrayNode();
        for (String value : values) {
            enumValues.add(value);
        }
        node.set("enum", enumValues);
        return node;
    }
}
