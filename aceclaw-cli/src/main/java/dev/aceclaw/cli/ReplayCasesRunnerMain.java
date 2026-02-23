package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.agent.ContextEstimator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs replay prompts in two modes (learning/candidate injection off vs on)
 * and emits replay case results consumed by generate-replay-report.sh.
 */
public final class ReplayCasesRunnerMain {

    private ReplayCasesRunnerMain() {}

    public static void main(String[] args) throws Exception {
        var cli = CliArgs.parse(args);
        if (cli.help) {
            CliArgs.printUsage();
            return;
        }
        if (cli.input == null) {
            System.err.println("Missing required --input");
            CliArgs.printUsage();
            System.exit(1);
            return;
        }

        var mapper = new ObjectMapper();
        var inputPath = Path.of(cli.input);
        if (!Files.isRegularFile(inputPath)) {
            System.err.println("Replay input file not found: " + inputPath);
            System.exit(1);
            return;
        }
        var root = mapper.readTree(Files.readString(inputPath));
        var cases = parseCases(root, cli.defaultProject, cli.timeoutMs);

        Files.createDirectories(Path.of(cli.output).getParent());

        try (DaemonClient client = DaemonStarter.ensureRunning()) {
            var output = mapper.createObjectNode();
            output.set("metadata", buildMetadata(mapper, inputPath, cli, cases.size()));
            output.set("cases", runAllCases(mapper, client, cases, cli));
            String serialized = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            Files.writeString(Path.of(cli.output), serialized);
            writeManifest(mapper, inputPath, cli, cases.size(), serialized);
            System.out.println("Replay cases written to: " + cli.output);
        }
    }

    private static ArrayNode runAllCases(ObjectMapper mapper,
                                         DaemonClient client,
                                         List<ReplayCase> cases,
                                         CliArgs cli) throws Exception {
        var array = mapper.createArrayNode();

        setCandidateInjection(client, false);
        for (var c : cases) {
            c.off = runOne(mapper, client, c, c.timeoutMs, cli.autoApprovePermissions);
        }

        setCandidateInjection(client, true);
        for (var c : cases) {
            c.on = runOne(mapper, client, c, c.timeoutMs, cli.autoApprovePermissions);
        }

        for (var c : cases) {
            var node = mapper.createObjectNode();
            node.put("id", c.id);
            node.put("prompt", c.prompt);
            node.put("project", c.project.toString());
            node.put("category", c.category);
            node.put("owner", c.owner);
            node.put("risk_level", c.riskLevel);
            var labels = mapper.createArrayNode();
            c.labels.forEach(labels::add);
            node.set("labels", labels);
            node.set("off", c.off.toJson(mapper));
            node.set("on", c.on.toJson(mapper));
            array.add(node);
        }
        return array;
    }

    private static ObjectNode buildMetadata(ObjectMapper mapper, Path inputPath, CliArgs cli, int totalCases)
            throws IOException, InterruptedException {
        var m = mapper.createObjectNode();
        m.put("collected_at", Instant.now().toString());
        m.put("input_path", inputPath.toAbsolutePath().toString());
        m.put("output_path", Path.of(cli.output).toAbsolutePath().toString());
        m.put("total_cases", totalCases);
        m.put("timeout_ms", cli.timeoutMs);
        m.put("auto_approve_permissions", cli.autoApprovePermissions);
        m.put("branch", git("rev-parse", "--abbrev-ref", "HEAD"));
        m.put("commit", git("rev-parse", "--short", "HEAD"));
        return m;
    }

    private static void writeManifest(ObjectMapper mapper, Path inputPath, CliArgs cli, int totalCases,
                                      String outputJson) throws Exception {
        Path manifestPath = Path.of(cli.manifestOutput);
        if (manifestPath.getParent() != null) {
            Files.createDirectories(manifestPath.getParent());
        }

        String sha = sha256Hex(outputJson);
        var m = mapper.createObjectNode();
        m.put("generated_at", Instant.now().toString());
        m.put("input_path", inputPath.toAbsolutePath().toString());
        m.put("cases_path", Path.of(cli.output).toAbsolutePath().toString());
        m.put("cases_sha256", sha);
        m.put("total_cases", totalCases);
        m.put("branch", git("rev-parse", "--abbrev-ref", "HEAD"));
        m.put("commit", git("rev-parse", "--short", "HEAD"));

        Files.writeString(manifestPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m));
        System.out.println("Replay cases manifest written to: " + manifestPath);
    }

    private static String sha256Hex(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
        var sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String git(String... args) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("git");
        for (var a : args) command.add(a);
        var p = new ProcessBuilder(command).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes()).trim();
        int code = p.waitFor();
        return code == 0 ? out : "unknown";
    }

    private static void setCandidateInjection(DaemonClient client, boolean enabled) throws Exception {
        var params = client.objectMapper().createObjectNode();
        params.put("enabled", enabled);
        params.put("persist", false);
        client.sendRequest("candidate.injection.set", params);
    }

    private static ReplayRun runOne(ObjectMapper mapper,
                                    DaemonClient client,
                                    ReplayCase c,
                                    long timeoutMs,
                                    boolean autoApprovePermissions) throws Exception {
        String sessionId = createSession(client, c.project);
        try {
            var start = System.nanoTime();
            var taskConn = client.openTaskConnection();
            var taskManager = new TaskManager();
            var permissionBridge = new PermissionBridge();
            var sink = new NullOutputSink();

            var handle = taskManager.submit(c.prompt, taskConn, sessionId, sink, permissionBridge);

            boolean timedOut = false;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (handle.isRunning()) {
                if (System.currentTimeMillis() >= deadline) {
                    timedOut = true;
                    taskManager.cancel(handle.taskId());
                    break;
                }

                var pending = permissionBridge.pollPending(100, TimeUnit.MILLISECONDS);
                if (pending != null) {
                    if (autoApprovePermissions) {
                        permissionBridge.submitAnswer(pending.requestId(),
                                new PermissionBridge.PermissionAnswer(true, false));
                    } else {
                        permissionBridge.submitAnswer(pending.requestId(),
                                new PermissionBridge.PermissionAnswer(false, false));
                    }
                }
            }

            var streamThread = handle.streamThread();
            if (streamThread != null) {
                streamThread.join(2000);
            }

            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            JsonNode msg = handle.result();
            JsonNode result = msg != null ? msg.path("result") : mapper.createObjectNode();
            boolean hasError = msg != null && msg.has("error") && !msg.get("error").isNull();
            boolean cancelled = result.path("cancelled").asBoolean(false);
            String response = result.path("response").asText("");

            boolean expectationOk = true;
            String normalizedResponse = response.toLowerCase(Locale.ROOT);
            for (var e : c.expectContains) {
                if (!normalizedResponse.contains(e.toLowerCase(Locale.ROOT))) {
                    expectationOk = false;
                    break;
                }
            }

            boolean success = !hasError && !timedOut && !cancelled && expectationOk
                    && handle.state() == TaskHandle.TaskState.COMPLETED;

            int providerTokensRaw = result.path("usage").path("totalTokens").asInt(0);
            int providerOutputTokensRaw = result.path("usage").path("outputTokens").asInt(0);
            Integer providerTokens = null;
            if (providerOutputTokensRaw > 0) {
                providerTokens = providerOutputTokensRaw;
            } else if (providerTokensRaw > 0) {
                providerTokens = providerTokensRaw;
            }
            int estimatedTokens = ContextEstimator.estimateTokens(c.prompt + "\n" + response);
            Double estimationErrorRatio = null;
            if (providerTokens != null && providerTokens > 0) {
                estimationErrorRatio = Math.abs((estimatedTokens - providerTokens) / (double) providerTokens);
            }
            String errorText = "";
            if (hasError) {
                errorText = msg.path("error").path("message").asText("");
            } else if (!success) {
                errorText = response;
            }

            String failureType = success ? null : classifyFailure(timedOut, cancelled, errorText);
            return new ReplayRun(success, providerTokensRaw, latencyMs, failureType, truncate(errorText, 240),
                    estimatedTokens, providerTokens, estimationErrorRatio);
        } finally {
            destroySession(client, sessionId);
        }
    }

    private static String classifyFailure(boolean timedOut, boolean cancelled, String text) {
        if (timedOut || cancelled) {
            return "timeout";
        }
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (s.contains("permission") || s.contains("denied") || s.contains("forbidden")) {
            return "permission";
        }
        if (s.contains("timeout") || s.contains("timed out")) {
            return "timeout";
        }
        if (s.contains("tool")) {
            return "tool-error";
        }
        return "other";
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        return text.substring(0, max) + "...";
    }

    private static String createSession(DaemonClient client, Path project) throws Exception {
        var params = client.objectMapper().createObjectNode();
        params.put("project", project.toAbsolutePath().toString());
        var result = client.sendRequest("session.create", params);
        return result.path("sessionId").asText();
    }

    private static void destroySession(DaemonClient client, String sessionId) {
        try {
            var params = client.objectMapper().createObjectNode();
            params.put("sessionId", sessionId);
            client.sendRequest("session.destroy", params);
        } catch (Exception ignored) {
        }
    }

    private static List<ReplayCase> parseCases(JsonNode root, Path defaultProject, long defaultTimeoutMs) {
        var casesNode = root.path("cases");
        if (!casesNode.isArray() || casesNode.isEmpty()) {
            throw new IllegalArgumentException("Input must include non-empty 'cases' array.");
        }
        var out = new ArrayList<ReplayCase>();
        int idx = 1;
        for (var n : casesNode) {
            String id = n.path("id").asText("case-" + idx++);
            String prompt = n.path("prompt").asText("");
            if (prompt.isBlank()) {
                throw new IllegalArgumentException("Case " + id + " missing prompt");
            }
            Path project = n.hasNonNull("project")
                    ? Path.of(n.path("project").asText())
                    : defaultProject;
            var expect = new ArrayList<String>();
            var expectNode = n.get("expect_contains");
            if (expectNode != null) {
                if (expectNode.isArray()) {
                    for (var e : expectNode) {
                        if (!e.asText("").isBlank()) expect.add(e.asText());
                    }
                } else if (!expectNode.asText("").isBlank()) {
                    expect.add(expectNode.asText());
                }
            }
            String category = n.path("category").asText("");
            String owner = n.path("owner").asText("");
            String riskLevel = n.path("risk_level").asText("");
            long timeoutMs = n.path("timeout_ms").asLong(defaultTimeoutMs);
            var labels = new ArrayList<String>();
            var labelsNode = n.get("labels");
            if (labelsNode != null && labelsNode.isArray()) {
                for (var e : labelsNode) {
                    String label = e.asText("");
                    if (!label.isBlank()) labels.add(label);
                }
            }
            out.add(new ReplayCase(id, prompt, project, List.copyOf(expect),
                    category, owner, riskLevel, timeoutMs, List.copyOf(labels)));
        }
        return out;
    }

    private static final class NullOutputSink implements OutputSink {
        @Override public void onThinkingDelta(String delta) {}
        @Override public void onTextDelta(String delta) {}
        @Override public void onToolUse(String toolName) {}
        @Override public void onStreamError(String error) {}
        @Override public void onStreamCancelled() {}
        @Override public void onTurnComplete(JsonNode result, boolean hasError) {}
        @Override public void onConnectionClosed() {}
    }

    private static final class ReplayCase {
        final String id;
        final String prompt;
        final Path project;
        final List<String> expectContains;
        final String category;
        final String owner;
        final String riskLevel;
        final long timeoutMs;
        final List<String> labels;
        ReplayRun off;
        ReplayRun on;

        ReplayCase(String id, String prompt, Path project, List<String> expectContains,
                   String category, String owner, String riskLevel, long timeoutMs, List<String> labels) {
            this.id = id;
            this.prompt = prompt;
            this.project = project;
            this.expectContains = expectContains;
            this.category = category;
            this.owner = owner;
            this.riskLevel = riskLevel;
            this.timeoutMs = timeoutMs;
            this.labels = labels;
        }
    }

    private record ReplayRun(
            boolean success,
            int tokens,
            long latencyMs,
            String failureType,
            String errorMessage,
            int estimatedTokens,
            Integer providerTokens,
            Double estimationErrorRatio
    ) {
        ObjectNode toJson(ObjectMapper mapper) {
            var n = mapper.createObjectNode();
            n.put("success", success);
            n.put("tokens", tokens);
            n.put("latency_ms", latencyMs);
            n.put("estimated_tokens", estimatedTokens);
            if (providerTokens == null) {
                n.putNull("provider_tokens");
            } else {
                n.put("provider_tokens", providerTokens);
            }
            if (estimationErrorRatio == null) {
                n.putNull("estimation_error_ratio");
            } else {
                n.put("estimation_error_ratio", estimationErrorRatio);
            }
            if (failureType == null) {
                n.putNull("failure_type");
            } else {
                n.put("failure_type", failureType);
            }
            if (errorMessage != null && !errorMessage.isBlank()) {
                n.put("error", errorMessage);
            }
            return n;
        }
    }

    private static final class CliArgs {
        String input;
        String output = ".aceclaw/metrics/continuous-learning/replay-cases.json";
        String manifestOutput = ".aceclaw/metrics/continuous-learning/replay-cases.manifest.json";
        Path defaultProject = Path.of(System.getProperty("user.dir"));
        long timeoutMs = 180_000L;
        boolean autoApprovePermissions = true;
        boolean help;

        static CliArgs parse(String[] args) {
            var c = new CliArgs();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--help", "-h" -> c.help = true;
                    case "--input" -> c.input = next(args, ++i, a);
                    case "--output" -> c.output = next(args, ++i, a);
                    case "--manifest-output" -> c.manifestOutput = next(args, ++i, a);
                    case "--project" -> c.defaultProject = Path.of(next(args, ++i, a));
                    case "--timeout-ms" -> c.timeoutMs = Long.parseLong(next(args, ++i, a));
                    case "--auto-approve-permissions" ->
                            c.autoApprovePermissions = Boolean.parseBoolean(next(args, ++i, a));
                    default -> throw new IllegalArgumentException("Unknown argument: " + a);
                }
            }
            return c;
        }

        private static String next(String[] args, int i, String flag) {
            if (i >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[i];
        }

        static void printUsage() {
            System.out.println("Usage: ReplayCasesRunnerMain --input <cases.json> [options]");
            System.out.println("Options:");
            System.out.println("  --output <path>                    Default: .aceclaw/metrics/continuous-learning/replay-cases.json");
            System.out.println("  --manifest-output <path>           Default: .aceclaw/metrics/continuous-learning/replay-cases.manifest.json");
            System.out.println("  --project <path>                   Default project path when case.project is missing");
            System.out.println("  --timeout-ms <ms>                  Default: 180000");
            System.out.println("  --auto-approve-permissions <bool>  Default: true");
            System.out.println("  --help");
            System.out.println("Input schema:");
            System.out.println("  {\"cases\":[{\"id\":\"case-1\",\"prompt\":\"...\",\"project\":\"/abs/path\",");
            System.out.println("   \"category\":\"core\",\"owner\":\"team\",\"risk_level\":\"medium\",");
            System.out.println("   \"timeout_ms\":180000,\"labels\":[\"smoke\"]}]}");
        }
    }
}
