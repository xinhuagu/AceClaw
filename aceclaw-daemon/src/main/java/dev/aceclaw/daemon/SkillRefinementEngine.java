package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.SkillConfig;
import dev.aceclaw.core.agent.SkillOutcome;
import dev.aceclaw.core.agent.SkillOutcomeTracker;
import dev.aceclaw.core.agent.SkillRegistry;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Analyzes underperforming skills, proposes refinements, and rolls back regressions.
 */
public final class SkillRefinementEngine {

    private static final Pattern CODE_FENCE = Pattern.compile(
            "```(?:json)?\\s*\\n?(\\{.*?})\\s*```",
            Pattern.DOTALL);
    private static final String SKILL_FILE = "SKILL.md";
    private static final String STATE_FILE = "skill-refinement-state.json";
    private static final String VERSIONS_DIR = "versions";
    private static final int RECENT_INVOCATION_WINDOW = 10;
    private static final int CONSECUTIVE_FAILURE_DISABLE_THRESHOLD = 5;
    private static final int ROLLBACK_SAMPLE_INVOCATIONS = 3;
    private static final int ROLLBACK_STABILIZATION_INVOCATIONS = 5;
    private static final double SUCCESS_RATE_REFINEMENT_THRESHOLD = 0.70;
    private static final double CORRECTION_RATE_REFINEMENT_THRESHOLD = 0.30;

    private final Clock clock;
    private final LlmClient llmClient;
    private final String model;
    private final SkillMetricsStore skillMetricsStore;
    private final ObjectMapper objectMapper;

    public SkillRefinementEngine(LlmClient llmClient, String model, SkillMetricsStore skillMetricsStore) {
        this(Clock.systemUTC(), llmClient, model, skillMetricsStore);
    }

    SkillRefinementEngine(Clock clock, LlmClient llmClient, String model, SkillMetricsStore skillMetricsStore) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.model = Objects.requireNonNull(model, "model");
        this.skillMetricsStore = Objects.requireNonNull(skillMetricsStore, "skillMetricsStore");
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    RefinementDecision analyze(List<SkillOutcome> outcomes) {
        Objects.requireNonNull(outcomes, "outcomes");
        var window = lastInvocationWindow(outcomes);
        if (window.consecutiveFailures() >= CONSECUTIVE_FAILURE_DISABLE_THRESHOLD) {
            return new RefinementDecision.DisableRecommended(
                    "Detected " + window.consecutiveFailures() + " consecutive failures.");
        }
        if (window.invocationCount() >= RECENT_INVOCATION_WINDOW
                && window.successRate() < SUCCESS_RATE_REFINEMENT_THRESHOLD) {
            return new RefinementDecision.RefinementRecommended(
                    "Success rate dropped to " + percent(window.successRate())
                            + " over the recent invocation window.");
        }
        if (window.invocationCount() > 0 && window.correctionRate() > CORRECTION_RATE_REFINEMENT_THRESHOLD) {
            return new RefinementDecision.RefinementRecommended(
                    "User correction rate reached " + percent(window.correctionRate()) + ".");
        }
        return new RefinementDecision.NoActionNeeded();
    }

    PreparedPlan prepare(Path projectPath, String skillName, List<SkillOutcome> outcomes) {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(skillName, "skillName");
        Objects.requireNonNull(outcomes, "outcomes");

        var skill = SkillRegistry.resolve(projectPath, skillName).orElse(null);
        if (skill == null) {
            return PreparedPlan.none();
        }

        var state = loadState(skill.directory());
        var window = lastInvocationWindow(outcomes);

        if (state.rollbackBaselineSuccessRate() != null && state.currentVersion() > 0) {
            if (window.invocationCount() >= ROLLBACK_SAMPLE_INVOCATIONS
                    && window.successRate() + 1e-9 < state.rollbackBaselineSuccessRate()) {
                return new PreparedPlan(skill, state, window, RefinementAction.ROLLED_BACK,
                        "Refined skill underperformed baseline (" + percent(window.successRate())
                                + " vs " + percent(state.rollbackBaselineSuccessRate()) + ").");
            }
            if (window.invocationCount() >= ROLLBACK_STABILIZATION_INVOCATIONS
                    && window.successRate() + 1e-9 >= state.rollbackBaselineSuccessRate()) {
                return new PreparedPlan(skill, state, window, RefinementAction.STABILIZED,
                        "Refined version met or exceeded baseline after observation window.");
            }
        }

        if (state.deprecated() || skill.disableModelInvocation()) {
            return PreparedPlan.none();
        }

        return switch (analyze(outcomes)) {
            case RefinementDecision.NoActionNeeded ignored -> PreparedPlan.none();
            case RefinementDecision.DisableRecommended disable ->
                    new PreparedPlan(skill, state, window, RefinementAction.DEPRECATED, disable.reason());
            case RefinementDecision.RefinementRecommended refine ->
                    new PreparedPlan(skill, state, window, RefinementAction.REFINED, refine.reason());
        };
    }

    SkillRefinement proposeRefinement(PreparedPlan plan, List<SkillOutcome> outcomes) throws LlmException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(outcomes, "outcomes");
        if (plan.action() != RefinementAction.REFINED) {
            throw new IllegalArgumentException("Refinement proposal requires a REFINED plan");
        }
        String prompt = buildRefinementPrompt(plan.skill(), outcomes, plan.reason());
        var request = LlmRequest.builder()
                .model(model)
                .addMessage(Message.user(prompt))
                .systemPrompt("""
                        You refine agent skills. Return ONLY valid JSON with:
                        {
                          "reason": "short explanation",
                          "updated_body": "full markdown body for SKILL.md without YAML frontmatter"
                        }
                        Keep the skill purpose the same. Improve only the instructions.
                        """)
                .maxTokens(4096)
                .thinkingBudget(2048)
                .temperature(0.2)
                .build();
        var response = llmClient.sendMessage(request);
        String text = response.text();
        if (text == null || text.isBlank()) {
            throw new LlmException("Empty response from LLM for skill refinement");
        }
        return parseRefinement(text);
    }

    RefinementOutcome apply(Path projectPath,
                            SkillOutcomeTracker tracker,
                            PreparedPlan plan,
                            SkillRefinement proposal) throws IOException {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(plan, "plan");

        return switch (plan.action()) {
            case NONE -> RefinementOutcome.none();
            case REFINED -> applyRefinement(projectPath, tracker, plan, proposal);
            case DEPRECATED -> applyDeprecation(projectPath, tracker, plan);
            case ROLLED_BACK -> rollbackToPreviousVersion(projectPath, tracker, plan);
            case STABILIZED -> stabilize(plan);
        };
    }

    public void rollback(Path projectPath, String skillName, SkillOutcomeTracker tracker) throws IOException {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(skillName, "skillName");
        Objects.requireNonNull(tracker, "tracker");

        var skill = SkillRegistry.resolve(projectPath, skillName).orElseThrow();
        var state = loadState(skill.directory());
        apply(projectPath, tracker, new PreparedPlan(skill, state, WindowStats.EMPTY,
                RefinementAction.ROLLED_BACK, "Manual rollback requested."), null);
    }

    private RefinementOutcome applyRefinement(Path projectPath,
                                              SkillOutcomeTracker tracker,
                                              PreparedPlan plan,
                                              SkillRefinement proposal) throws IOException {
        if (proposal == null) {
            return RefinementOutcome.none();
        }
        String originalMarkdown = readSkillMarkdown(plan.skill());
        int nextVersion = plan.state().latestVersion() + 1;
        backupVersion(plan.skill().directory(), nextVersion, originalMarkdown);
        writeSkillMarkdown(plan.skill().directory(),
                renderSkillMarkdown(plan.skill(), proposal.updatedBody(), false, false));

        var updatedState = plan.state().withCurrentVersion(nextVersion)
                .withLatestVersion(nextVersion)
                .withDeprecated(false)
                .withRollbackBaselineSuccessRate(plan.window().successRate())
                .withLastAction("REFINED")
                .withLastReason(proposal.reason())
                .withLastRefinedAt(Instant.now(clock))
                .withLastAppliedDigest(digest(proposal.updatedBody()));
        persistState(plan.skill().directory(), updatedState);
        resetMetrics(projectPath, plan.skill().name(), tracker);
        return new RefinementOutcome(RefinementAction.REFINED, plan.skill().name(), proposal.reason());
    }

    private RefinementOutcome applyDeprecation(Path projectPath,
                                               SkillOutcomeTracker tracker,
                                               PreparedPlan plan) throws IOException {
        String originalMarkdown = readSkillMarkdown(plan.skill());
        int nextVersion = plan.state().latestVersion() + 1;
        backupVersion(plan.skill().directory(), nextVersion, originalMarkdown);
        writeSkillMarkdown(plan.skill().directory(),
                renderSkillMarkdown(plan.skill(), plan.skill().body(), true, true));

        var updatedState = plan.state().withCurrentVersion(nextVersion)
                .withLatestVersion(nextVersion)
                .withDeprecated(true)
                .withRollbackBaselineSuccessRate(null)
                .withLastAction("DEPRECATED")
                .withLastReason(plan.reason())
                .withLastRefinedAt(Instant.now(clock))
                .withLastAppliedDigest(digest(plan.skill().body()));
        persistState(plan.skill().directory(), updatedState);
        resetMetrics(projectPath, plan.skill().name(), tracker);
        return new RefinementOutcome(RefinementAction.DEPRECATED, plan.skill().name(), plan.reason());
    }

    private RefinementOutcome rollbackToPreviousVersion(Path projectPath,
                                                        SkillOutcomeTracker tracker,
                                                        PreparedPlan plan) throws IOException {
        if (plan.state().currentVersion() <= 0) {
            return RefinementOutcome.none();
        }
        Path previous = plan.skill().directory().resolve(VERSIONS_DIR)
                .resolve("v" + plan.state().currentVersion() + ".md");
        if (!Files.isRegularFile(previous)) {
            return RefinementOutcome.none();
        }

        String restored = Files.readString(previous);
        writeSkillMarkdown(plan.skill().directory(), restored);
        var updatedState = plan.state().withCurrentVersion(Math.max(0, plan.state().currentVersion() - 1))
                .withDeprecated(false)
                .withRollbackBaselineSuccessRate(null)
                .withLastAction("ROLLED_BACK")
                .withLastReason(plan.reason())
                .withLastAppliedDigest(digest(restored));
        persistState(plan.skill().directory(), updatedState);
        resetMetrics(projectPath, plan.skill().name(), tracker);
        return new RefinementOutcome(RefinementAction.ROLLED_BACK, plan.skill().name(), plan.reason());
    }

    private RefinementOutcome stabilize(PreparedPlan plan) throws IOException {
        var updatedState = plan.state().withRollbackBaselineSuccessRate(null)
                .withLastAction("STABILIZED")
                .withLastReason(plan.reason());
        persistState(plan.skill().directory(), updatedState);
        return new RefinementOutcome(RefinementAction.NONE, plan.skill().name(), plan.reason());
    }

    private SkillRefinement parseRefinement(String llmOutput) throws LlmException {
        String jsonText = extractJson(llmOutput);
        try {
            JsonNode root = objectMapper.readTree(jsonText);
            String reason = root.path("reason").asText("").trim();
            String updatedBody = root.path("updated_body").asText("").trim();
            if (updatedBody.isBlank()) {
                throw new LlmException("Skill refinement response missing updated_body");
            }
            if (reason.isBlank()) {
                reason = "Applied LLM-proposed refinement.";
            }
            return new SkillRefinement(reason, updatedBody + "\n");
        } catch (IOException e) {
            throw new LlmException("Failed to parse skill refinement response", e);
        }
    }

    private String buildRefinementPrompt(SkillConfig skill, List<SkillOutcome> outcomes, String reason) {
        var sb = new StringBuilder();
        sb.append("Skill name: ").append(skill.name()).append("\n");
        sb.append("Description: ").append(skill.description()).append("\n");
        sb.append("Refinement trigger: ").append(reason).append("\n\n");
        sb.append("Current skill body:\n---\n").append(skill.body().trim()).append("\n---\n\n");
        sb.append("Recent outcomes:\n");
        int index = 1;
        for (var outcome : outcomes.subList(Math.max(0, outcomes.size() - 12), outcomes.size())) {
            switch (outcome) {
                case SkillOutcome.Success success ->
                        sb.append(index++).append(". success in ").append(success.turnsUsed()).append(" turns\n");
                case SkillOutcome.Failure failure ->
                        sb.append(index++).append(". failure: ").append(trim(failure.reason(), 180)).append("\n");
                case SkillOutcome.UserCorrected corrected ->
                        sb.append(index++).append(". user corrected: ")
                                .append(trim(corrected.correction(), 180)).append("\n");
            }
        }
        sb.append("\nProduce an improved markdown body only. Do not include YAML frontmatter.\n");
        return sb.toString();
    }

    private static String renderSkillMarkdown(SkillConfig skill,
                                              String body,
                                              boolean disableModelInvocation,
                                              boolean deprecated) {
        var sb = new StringBuilder();
        sb.append("---\n");
        appendScalar(sb, "description", skill.description());
        if (skill.argumentHint() != null && !skill.argumentHint().isBlank()) {
            appendScalar(sb, "argument-hint", skill.argumentHint());
        }
        sb.append("context: ").append(skill.context().name().toLowerCase(Locale.ROOT)).append("\n");
        if (skill.model() != null && !skill.model().isBlank()) {
            appendScalar(sb, "model", skill.model());
        }
        if (!skill.allowedTools().isEmpty()) {
            sb.append("allowed-tools: [");
            for (int i = 0; i < skill.allowedTools().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(skill.allowedTools().get(i));
            }
            sb.append("]\n");
        }
        sb.append("max-turns: ").append(skill.maxTurns()).append("\n");
        sb.append("user-invocable: ").append(skill.userInvocable()).append("\n");
        sb.append("disable-model-invocation: ").append(disableModelInvocation).append("\n");
        if (deprecated) {
            sb.append("deprecated: true\n");
        }
        sb.append("---\n\n");
        sb.append(body == null ? "" : body.strip()).append("\n");
        return sb.toString();
    }

    private static void appendScalar(StringBuilder sb, String key, String value) {
        sb.append(key).append(": ").append(serializeScalar(value)).append("\n");
    }

    private static String serializeScalar(String value) {
        if (value == null) {
            return "\"\"";
        }
        String normalized = value.replace("\r", " ").replace("\n", " ").trim();
        if (!normalized.contains("\"")) {
            return "\"" + normalized + "\"";
        }
        if (!normalized.contains("'")) {
            return "'" + normalized + "'";
        }
        return "\"" + normalized.replace("\"", "'") + "\"";
    }

    private static RefinementState loadState(Path skillDir) {
        Path stateFile = skillDir.resolve(STATE_FILE);
        if (!Files.isRegularFile(stateFile)) {
            return RefinementState.EMPTY;
        }
        try {
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            var state = mapper.readValue(stateFile.toFile(), RefinementState.class);
            return state == null ? RefinementState.EMPTY : state.normalize();
        } catch (Exception e) {
            return RefinementState.EMPTY;
        }
    }

    private static void persistState(Path skillDir, RefinementState state) throws IOException {
        Files.createDirectories(skillDir);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Path tmp = skillDir.resolve(STATE_FILE + ".tmp");
        Path stateFile = skillDir.resolve(STATE_FILE);
        Files.writeString(
                tmp,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state.normalize()) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void backupVersion(Path skillDir, int version, String markdown) throws IOException {
        Path versionsDir = skillDir.resolve(VERSIONS_DIR);
        Files.createDirectories(versionsDir);
        Files.writeString(
                versionsDir.resolve("v" + version + ".md"),
                markdown,
                StandardOpenOption.CREATE_NEW);
    }

    private static void writeSkillMarkdown(Path skillDir, String markdown) throws IOException {
        Files.createDirectories(skillDir);
        Path tmp = skillDir.resolve(SKILL_FILE + ".tmp");
        Path skillFile = skillDir.resolve(SKILL_FILE);
        Files.writeString(tmp, markdown, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, skillFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String readSkillMarkdown(SkillConfig skill) throws IOException {
        return Files.readString(skill.directory().resolve(SKILL_FILE));
    }

    private void resetMetrics(Path projectPath, String skillName, SkillOutcomeTracker tracker) throws IOException {
        tracker.reset(skillName);
        skillMetricsStore.reset(projectPath, skillName);
    }

    private static WindowStats lastInvocationWindow(List<SkillOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return WindowStats.EMPTY;
        }
        int invocationCount = 0;
        int startIndex = outcomes.size();
        for (int i = outcomes.size() - 1; i >= 0; i--) {
            var outcome = outcomes.get(i);
            if (outcome instanceof SkillOutcome.Success || outcome instanceof SkillOutcome.Failure) {
                invocationCount++;
                startIndex = i;
                if (invocationCount >= RECENT_INVOCATION_WINDOW) {
                    break;
                }
            }
        }
        if (startIndex >= outcomes.size()) {
            return WindowStats.EMPTY;
        }

        int successCount = 0;
        int correctionCount = 0;
        int consecutiveFailures = 0;
        for (int i = startIndex; i < outcomes.size(); i++) {
            var outcome = outcomes.get(i);
            switch (outcome) {
                case SkillOutcome.Success ignored -> successCount++;
                case SkillOutcome.Failure ignored -> {
                    // counted in backward trailing-failure scan below
                }
                case SkillOutcome.UserCorrected ignored -> correctionCount++;
            }
        }

        for (int i = outcomes.size() - 1; i >= startIndex; i--) {
            if (outcomes.get(i) instanceof SkillOutcome.Failure) {
                consecutiveFailures++;
                continue;
            }
            break;
        }

        return new WindowStats(
                invocationCount,
                successCount,
                correctionCount,
                consecutiveFailures,
                invocationCount == 0 ? 0.0 : (double) successCount / invocationCount,
                invocationCount == 0 ? 0.0 : (double) correctionCount / invocationCount);
    }

    private static String extractJson(String text) {
        var matcher = CODE_FENCE.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return text.trim();
    }

    private static String trim(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 3) + "...";
    }

    private static String digest(String text) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((text == null ? "" : text)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String percent(double value) {
        return Math.round(value * 100.0) + "%";
    }

    public sealed interface RefinementDecision permits RefinementDecision.NoActionNeeded,
            RefinementDecision.RefinementRecommended, RefinementDecision.DisableRecommended {
        record NoActionNeeded() implements RefinementDecision {}
        record RefinementRecommended(String reason) implements RefinementDecision {}
        record DisableRecommended(String reason) implements RefinementDecision {}
    }

    record SkillRefinement(String reason, String updatedBody) {}

    public enum RefinementAction {
        NONE,
        REFINED,
        DEPRECATED,
        ROLLED_BACK,
        STABILIZED
    }

    public record RefinementOutcome(RefinementAction action, String skillName, String reason) {
        static RefinementOutcome none() {
            return new RefinementOutcome(RefinementAction.NONE, "", "");
        }
    }

    record PreparedPlan(
            SkillConfig skill,
            RefinementState state,
            WindowStats window,
            RefinementAction action,
            String reason
    ) {
        static PreparedPlan none() {
            return new PreparedPlan(null, null, WindowStats.EMPTY, RefinementAction.NONE, "");
        }
    }

    record WindowStats(
            int invocationCount,
            int successCount,
            int correctionCount,
            int consecutiveFailures,
            double successRate,
            double correctionRate
    ) {
        static final WindowStats EMPTY = new WindowStats(0, 0, 0, 0, 0.0, 0.0);
    }

    record RefinementState(
            int currentVersion,
            int latestVersion,
            boolean deprecated,
            Double rollbackBaselineSuccessRate,
            Instant lastRefinedAt,
            String lastAction,
            String lastReason,
            String lastAppliedDigest
    ) {
        static final RefinementState EMPTY = new RefinementState(0, 0, false, null, null, null, null, null);

        RefinementState {
            currentVersion = Math.max(0, currentVersion);
            latestVersion = Math.max(0, latestVersion);
        }

        RefinementState withCurrentVersion(int value) {
            return new RefinementState(value, latestVersion, deprecated, rollbackBaselineSuccessRate,
                    lastRefinedAt, lastAction, lastReason, lastAppliedDigest);
        }

        RefinementState withLatestVersion(int value) {
            return new RefinementState(currentVersion, value, deprecated, rollbackBaselineSuccessRate,
                    lastRefinedAt, lastAction, lastReason, lastAppliedDigest);
        }

        RefinementState withDeprecated(boolean value) {
            return new RefinementState(currentVersion, latestVersion, value, rollbackBaselineSuccessRate,
                    lastRefinedAt, lastAction, lastReason, lastAppliedDigest);
        }

        RefinementState withRollbackBaselineSuccessRate(Double value) {
            return new RefinementState(currentVersion, latestVersion, deprecated, value,
                    lastRefinedAt, lastAction, lastReason, lastAppliedDigest);
        }

        RefinementState withLastRefinedAt(Instant value) {
            return new RefinementState(currentVersion, latestVersion, deprecated, rollbackBaselineSuccessRate,
                    value, lastAction, lastReason, lastAppliedDigest);
        }

        RefinementState withLastAction(String value) {
            return new RefinementState(currentVersion, latestVersion, deprecated, rollbackBaselineSuccessRate,
                    lastRefinedAt, value, lastReason, lastAppliedDigest);
        }

        RefinementState withLastReason(String value) {
            return new RefinementState(currentVersion, latestVersion, deprecated, rollbackBaselineSuccessRate,
                    lastRefinedAt, lastAction, value, lastAppliedDigest);
        }

        RefinementState withLastAppliedDigest(String value) {
            return new RefinementState(currentVersion, latestVersion, deprecated, rollbackBaselineSuccessRate,
                    lastRefinedAt, lastAction, lastReason, value);
        }

        RefinementState normalize() {
            return new RefinementState(currentVersion, latestVersion, deprecated, rollbackBaselineSuccessRate,
                    lastRefinedAt, lastAction, lastReason, lastAppliedDigest);
        }
    }
}
