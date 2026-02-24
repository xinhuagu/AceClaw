package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Autonomous validation gate for generated skill drafts.
 *
 * <p>Gate packs:
 * <ul>
 *   <li>static: frontmatter/schema/tool policy checks</li>
 *   <li>dry-run: basic runtime-readiness checks</li>
 *   <li>replay: replay report metric checks</li>
 *   <li>safety: model invocation and path safety checks</li>
 * </ul>
 */
public final class ValidationGateEngine {

    private static final String DRAFTS_DIR = ".aceclaw/skills-drafts";
    private static final String AUDIT_DIR = ".aceclaw/metrics/continuous-learning";
    private static final String AUDIT_FILE = "skill-draft-validation-audit.jsonl";

    private static final String DEFAULT_REPLAY_REPORT = ".aceclaw/metrics/continuous-learning/replay-latest.json";

    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean strictMode;
    private final boolean replayRequired;
    private final Path replayReportPath;
    private final double maxTokenEstimationErrorRatio;

    public ValidationGateEngine(boolean strictMode,
                                boolean replayRequired,
                                Path replayReportPath,
                                double maxTokenEstimationErrorRatio) {
        this(Clock.systemUTC(), strictMode, replayRequired, replayReportPath, maxTokenEstimationErrorRatio);
    }

    ValidationGateEngine(Clock clock,
                         boolean strictMode,
                         boolean replayRequired,
                         Path replayReportPath,
                         double maxTokenEstimationErrorRatio) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.strictMode = strictMode;
        this.replayRequired = replayRequired;
        this.replayReportPath = replayReportPath != null ? replayReportPath : Path.of(DEFAULT_REPLAY_REPORT);
        this.maxTokenEstimationErrorRatio = maxTokenEstimationErrorRatio >= 0 ? maxTokenEstimationErrorRatio : 0.65;
    }

    public ValidationSummary validateAll(Path projectRoot, String trigger) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        String normalizedTrigger = normalizeTrigger(trigger);

        Path draftsRoot = projectRoot.resolve(DRAFTS_DIR);
        if (!Files.isDirectory(draftsRoot)) {
            return new ValidationSummary(
                    0, 0, 0, 0, List.of(), projectRoot.resolve(AUDIT_DIR).resolve(AUDIT_FILE));
        }

        var replay = evaluateReplay(projectRoot);
        List<Path> draftFiles;
        try (var paths = Files.walk(draftsRoot)) {
            draftFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .sorted(Comparator.comparing(path -> projectRoot.relativize(path).toString()))
                    .toList();
        }

        var decisions = new ArrayList<DraftDecision>();
        for (var draftFile : draftFiles) {
            decisions.add(validateSingle(projectRoot, draftFile, normalizedTrigger, replay));
        }
        writeAudit(projectRoot, decisions);
        return summarize(projectRoot, decisions);
    }

    public ValidationSummary validateSingleDraft(Path projectRoot, Path draftPath, String trigger) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(draftPath, "draftPath");
        String normalizedTrigger = normalizeTrigger(trigger);
        var replay = evaluateReplay(projectRoot);
        var decision = validateSingle(projectRoot, draftPath, normalizedTrigger, replay);
        writeAudit(projectRoot, List.of(decision));
        return summarize(projectRoot, List.of(decision));
    }

    private ValidationSummary summarize(Path projectRoot, List<DraftDecision> decisions) {
        int pass = 0;
        int hold = 0;
        int block = 0;
        for (var d : decisions) {
            if (d.verdict() == Verdict.PASS) pass++;
            if (d.verdict() == Verdict.HOLD) hold++;
            if (d.verdict() == Verdict.BLOCK) block++;
        }
        return new ValidationSummary(
                decisions.size(), pass, hold, block, List.copyOf(decisions),
                projectRoot.resolve(AUDIT_DIR).resolve(AUDIT_FILE));
    }

    private DraftDecision validateSingle(Path projectRoot,
                                         Path draftFile,
                                         String trigger,
                                         ReplayGateResult replay) throws IOException {
        var reasons = new ArrayList<ReasonCode>();
        Instant now = Instant.now(clock);

        if (!Files.exists(draftFile)) {
            reasons.add(new ReasonCode("static", "STATIC_DRAFT_MISSING", Verdict.BLOCK,
                    "Draft file does not exist"));
            return buildDecision(projectRoot, draftFile, trigger, now, reasons);
        }

        if (!isUnderDraftsDir(projectRoot, draftFile)) {
            reasons.add(new ReasonCode("safety", "SAFETY_DRAFT_PATH_ESCAPE", Verdict.BLOCK,
                    "Draft path escapes .aceclaw/skills-drafts"));
            return buildDecision(projectRoot, draftFile, trigger, now, reasons);
        }

        String raw = Files.readString(draftFile);
        var parsed = parseFrontmatter(raw);
        if (parsed == null) {
            reasons.add(new ReasonCode("static", "STATIC_FRONTMATTER_MISSING", Verdict.BLOCK,
                    "Missing YAML frontmatter"));
            return buildDecision(projectRoot, draftFile, trigger, now, reasons);
        }

        if (parsed.body().isBlank()) {
            reasons.add(new ReasonCode("dry-run", "DRY_RUN_EMPTY_BODY", Verdict.HOLD,
                    "Draft body is empty"));
        }
        if (parsed.frontmatter().getOrDefault("description", "").isBlank()) {
            reasons.add(new ReasonCode("static", "STATIC_DESCRIPTION_MISSING", Verdict.BLOCK,
                    "Frontmatter description is required"));
        }
        if (!parseBool(parsed.frontmatter().get("disable-model-invocation"), false)) {
            reasons.add(new ReasonCode("safety", "SAFETY_DISABLE_MODEL_INVOCATION_REQUIRED", Verdict.BLOCK,
                    "disable-model-invocation must be true"));
        }
        if (!allowedToolsPolicyOk(parsed.frontmatter().get("allowed-tools"))) {
            reasons.add(new ReasonCode("static", "STATIC_ALLOWED_TOOLS_POLICY_VIOLATION", Verdict.HOLD,
                    "allowed-tools includes unsafe or malformed values"));
        }
        if (parsed.frontmatter().containsKey("model")
                && !parsed.frontmatter().getOrDefault("model", "").isBlank()) {
            reasons.add(new ReasonCode("safety", "SAFETY_MODEL_OVERRIDE_PRESENT", strictMode ? Verdict.BLOCK : Verdict.HOLD,
                    "Draft should not pin model during validation phase"));
        }

        reasons.addAll(replay.asReasons(strictMode, replayRequired));
        return buildDecision(projectRoot, draftFile, trigger, now, reasons);
    }

    private DraftDecision buildDecision(Path projectRoot,
                                        Path draftFile,
                                        String trigger,
                                        Instant now,
                                        List<ReasonCode> reasons) {
        Verdict verdict = Verdict.PASS;
        if (!reasons.isEmpty()) {
            if (reasons.stream().anyMatch(r -> r.outcome() == Verdict.BLOCK)) {
                verdict = Verdict.BLOCK;
            } else {
                verdict = Verdict.HOLD;
            }
        }
        String rel = projectRoot.relativize(draftFile).toString().replace('\\', '/');
        return new DraftDecision(rel, verdict, List.copyOf(reasons), now, trigger);
    }

    private ReplayGateResult evaluateReplay(Path projectRoot) {
        Path report = replayReportPath.isAbsolute() ? replayReportPath : projectRoot.resolve(replayReportPath);
        if (!Files.isRegularFile(report)) {
            return ReplayGateResult.missing("Replay report not found at " + report);
        }
        try {
            JsonNode root = mapper.readTree(report.toFile());
            if (root == null) {
                return ReplayGateResult.invalid("Replay report empty or unreadable");
            }
            var metrics = root.path("metrics");
            if (metrics.isMissingNode() || !metrics.isObject()) {
                return ReplayGateResult.invalid("Replay metrics object missing");
            }
            var required = List.of(
                    "replay_success_rate_delta",
                    "replay_token_delta",
                    "replay_failure_distribution_delta",
                    "token_estimation_error_ratio_max"
            );
            for (String key : required) {
                JsonNode metric = metrics.path(key);
                String status = metric.path("status").asText("");
                JsonNode valueNode = metric.path("value");
                if (!"measured".equalsIgnoreCase(status)) {
                    return ReplayGateResult.invalid("Metric " + key + " status is not measured");
                }
                if (valueNode.isMissingNode() || valueNode.isNull()) {
                    return ReplayGateResult.invalid("Metric " + key + " value is null");
                }
            }

            double tokenErr = metrics.path("token_estimation_error_ratio_max").path("value").asDouble(Double.NaN);
            if (Double.isNaN(tokenErr)) {
                return ReplayGateResult.invalid("Metric token_estimation_error_ratio_max is not numeric");
            }
            if (tokenErr > maxTokenEstimationErrorRatio) {
                return ReplayGateResult.invalid("token_estimation_error_ratio_max exceeds threshold");
            }

            return ReplayGateResult.passed();
        } catch (Exception e) {
            return ReplayGateResult.invalid("Replay report parsing failed: " + e.getMessage());
        }
    }

    private void writeAudit(Path projectRoot, List<DraftDecision> decisions) throws IOException {
        if (decisions.isEmpty()) {
            return;
        }
        Path auditPath = projectRoot.resolve(AUDIT_DIR).resolve(AUDIT_FILE);
        Files.createDirectories(auditPath.getParent());
        for (var d : decisions) {
            var node = mapper.createObjectNode();
            node.put("timestamp", d.evaluatedAt().toString());
            node.put("trigger", d.trigger());
            node.put("draftPath", d.draftPath());
            node.put("verdict", d.verdict().name().toLowerCase());
            var reasonArray = mapper.createArrayNode();
            for (var r : d.reasons()) {
                var rn = mapper.createObjectNode();
                rn.put("gate", r.gate());
                rn.put("code", r.code());
                rn.put("outcome", r.outcome().name().toLowerCase());
                rn.put("message", r.message());
                reasonArray.add(rn);
            }
            node.set("reasons", reasonArray);
            Files.writeString(
                    auditPath,
                    mapper.writeValueAsString(node) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        }
    }

    private static boolean isUnderDraftsDir(Path projectRoot, Path draftFile) {
        Path draftsRoot = projectRoot.resolve(DRAFTS_DIR).normalize().toAbsolutePath();
        Path target = draftFile.normalize().toAbsolutePath();
        return target.startsWith(draftsRoot);
    }

    private static boolean parseBool(String raw, boolean defaultValue) {
        if (raw == null) return defaultValue;
        String v = stripQuotes(raw).trim().toLowerCase();
        if ("true".equals(v) || "yes".equals(v)) return true;
        if ("false".equals(v) || "no".equals(v)) return false;
        return defaultValue;
    }

    private static boolean allowedToolsPolicyOk(String raw) {
        if (raw == null || raw.isBlank()) return true;
        var tools = parseInlineList(raw);
        for (var tool : tools) {
            String t = tool.trim();
            if (t.isBlank()) return false;
            if ("*".equals(t)) return false;
            if (!t.matches("[a-z0-9_\\-]+")) return false;
        }
        return true;
    }

    private static List<String> parseInlineList(String raw) {
        String v = raw.trim();
        if (!v.startsWith("[") || !v.endsWith("]")) {
            return List.of(v);
        }
        String inner = v.substring(1, v.length() - 1).trim();
        if (inner.isEmpty()) return List.of();
        var out = new ArrayList<String>();
        for (var part : inner.split(",")) {
            out.add(stripQuotes(part.trim()));
        }
        return out;
    }

    private static Frontmatter parseFrontmatter(String raw) {
        String[] lines = raw.split("\n");
        int first = -1;
        int second = -1;
        for (int i = 0; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                if (first < 0) first = i;
                else {
                    second = i;
                    break;
                }
            }
        }
        if (first < 0 || second < 0 || second <= first) {
            return null;
        }
        var map = new LinkedHashMap<String, String>();
        for (int i = first + 1; i < second; i++) {
            String line = lines[i];
            int idx = line.indexOf(':');
            if (idx <= 0) continue;
            String key = line.substring(0, idx).trim().toLowerCase();
            String value = line.substring(idx + 1).trim();
            map.put(key, value);
        }
        var body = new StringBuilder();
        for (int i = second + 1; i < lines.length; i++) {
            body.append(lines[i]).append('\n');
        }
        return new Frontmatter(map, body.toString().strip());
    }

    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s == null ? "" : s;
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String normalizeTrigger(String trigger) {
        if (trigger == null || trigger.isBlank()) return "manual";
        return trigger.trim();
    }

    private record Frontmatter(Map<String, String> frontmatter, String body) {}

    private record ReplayGateResult(boolean pass, String message, boolean missing) {
        static ReplayGateResult passed() { return new ReplayGateResult(true, "", false); }
        static ReplayGateResult missing(String msg) { return new ReplayGateResult(false, msg, true); }
        static ReplayGateResult invalid(String msg) { return new ReplayGateResult(false, msg, false); }

        List<ReasonCode> asReasons(boolean strictMode, boolean replayRequired) {
            if (pass) return List.of();
            if (missing && !replayRequired) return List.of();
            String code = missing ? "REPLAY_REPORT_MISSING" : "REPLAY_GATE_FAILED";
            Verdict outcome = strictMode ? Verdict.BLOCK : Verdict.HOLD;
            return List.of(new ReasonCode("replay", code, outcome, message));
        }
    }

    public enum Verdict {
        PASS, HOLD, BLOCK
    }

    public record ReasonCode(
            String gate,
            String code,
            Verdict outcome,
            String message
    ) {}

    public record DraftDecision(
            String draftPath,
            Verdict verdict,
            List<ReasonCode> reasons,
            Instant evaluatedAt,
            String trigger
    ) {
        public DraftDecision {
            reasons = reasons != null ? List.copyOf(reasons) : List.of();
        }
    }

    public record ValidationSummary(
            int totalDrafts,
            int passCount,
            int holdCount,
            int blockCount,
            List<DraftDecision> decisions,
            Path auditFile
    ) {
        public ValidationSummary {
            decisions = decisions != null ? List.copyOf(decisions) : List.of();
        }
    }
}
