package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.SkillConfig;
import dev.aceclaw.core.agent.SkillMetrics;
import dev.aceclaw.core.agent.SkillOutcome;
import dev.aceclaw.core.agent.SkillOutcomeTracker;
import dev.aceclaw.core.agent.SkillRegistry;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(SkillRefinementEngine.class);
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

    public RefinementOutcome onOutcomeRecorded(Path projectPath, String skillName, SkillOutcomeTracker tracker)
            throws IOException, LlmException {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(skillName, "skillName");
        Objects.requireNonNull(tracker, "tracker");

        var skill = SkillRegistry.load(projectPath).get(skillName).orElse(null);
        var metrics = tracker.getMetrics(skillName).orElse(null);
        if (skill == null || metrics == null) {
            return RefinementOutcome.none();
        }

        var state = loadState(skill.directory());
        var rollback = evaluateRollback(projectPath, skill, tracker, metrics, state);
        if (rollback != null && rollback.action() != RefinementAction.NONE) {
            return rollback;
        }

        if (state.deprecated() || skill.disableModelInvocation()) {
            return RefinementOutcome.none();
        }

        var recentOutcomes = tracker.outcomes(skillName);
        return switch (analyze(metrics, recentOutcomes)) {
            case RefinementDecision.NoActionNeeded ignored -> RefinementOutcome.none();
            case RefinementDecision.DisableRecommended disable ->
                    deprecate(projectPath, skill, tracker, state, disable.reason());
            case RefinementDecision.RefinementRecommended refine ->
                    refine(projectPath, skill, tracker, recentOutcomes, metrics, state, refine.reason());
        };
    }

    RefinementDecision analyze(SkillMetrics metrics, List<SkillOutcome> recentOutcomes) {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(recentOutcomes, "recentOutcomes");

        int consecutiveFailures = trailingConsecutiveFailures(recentOutcomes);
        if (consecutiveFailures >= CONSECUTIVE_FAILURE_DISABLE_THRESHOLD) {
            return new RefinementDecision.DisableRecommended(
                    "Detected " + consecutiveFailures + " consecutive failures.");
        }

        int recentInvocationCount = recentInvocationCount(recentOutcomes);
        if (recentInvocationCount >= RECENT_INVOCATION_WINDOW
                && metrics.successRate() < SUCCESS_RATE_REFINEMENT_THRESHOLD) {
            return new RefinementDecision.RefinementRecommended(
                    "Success rate dropped to " + Math.round(metrics.successRate() * 100.0)
                            + "% over the recent invocation window.");
        }
        if (metrics.invocationCount() > 0 && metrics.correctionRate() > CORRECTION_RATE_REFINEMENT_THRESHOLD) {
            return new RefinementDecision.RefinementRecommended(
                    "User correction rate reached " + Math.round(metrics.correctionRate() * 100.0) + "%.");
        }

        return new RefinementDecision.NoActionNeeded();
    }

    public void rollback(Path projectPath, String skillName, SkillOutcomeTracker tracker) throws IOException {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(skillName, "skillName");
        Objects.requireNonNull(tracker, "tracker");

        var skill = SkillRegistry.load(projectPath).get(skillName).orElseThrow();
        rollbackToPreviousVersion(projectPath, skill, tracker, loadState(skill.directory()),
                "Manual rollback requested.");
    }

    private RefinementOutcome evaluateRollback(Path projectPath,
                                               SkillConfig skill,
                                               SkillOutcomeTracker tracker,
                                               SkillMetrics metrics,
                                               RefinementState state) throws IOException {
        if (state.rollbackBaselineSuccessRate() == null || state.currentVersion() <= 0) {
            return RefinementOutcome.none();
        }

        if (metrics.invocationCount() >= ROLLBACK_SAMPLE_INVOCATIONS
                && metrics.successRate() + 1e-9 < state.rollbackBaselineSuccessRate()) {
            return rollbackToPreviousVersion(
                    projectPath,
                    skill,
                    tracker,
                    state,
                    "Refined skill underperformed baseline (" + percent(metrics.successRate())
                            + " vs " + percent(state.rollbackBaselineSuccessRate()) + ").");
        }

        if (metrics.invocationCount() >= ROLLBACK_STABILIZATION_INVOCATIONS
                && metrics.successRate() + 1e-9 >= state.rollbackBaselineSuccessRate()) {
            var stabilized = state.withRollbackBaselineSuccessRate(null)
                    .withLastAction("STABILIZED")
                    .withLastReason("Refined version met or exceeded baseline after observation window.");
            persistState(skill.directory(), stabilized);
            return new RefinementOutcome(RefinementAction.NONE, skill.name(), stabilized.lastReason());
        }

        return RefinementOutcome.none();
    }

    private RefinementOutcome refine(Path projectPath,
                                     SkillConfig skill,
                                     SkillOutcomeTracker tracker,
                                     List<SkillOutcome> recentOutcomes,
                                     SkillMetrics metrics,
                                     RefinementState state,
                                     String reason) throws IOException, LlmException {
        var proposal = proposeRefinement(skill, recentOutcomes, reason);
        String originalMarkdown = readSkillMarkdown(skill);
        int nextVersion = state.currentVersion() + 1;
        backupVersion(skill.directory(), nextVersion, originalMarkdown);
        writeSkillMarkdown(skill.directory(), renderSkillMarkdown(skill, proposal.updatedBody(), false, false));

        var updatedState = state.withCurrentVersion(nextVersion)
                .withDeprecated(false)
                .withRollbackBaselineSuccessRate(metrics.successRate())
                .withLastAction("REFINED")
                .withLastReason(proposal.reason())
                .withLastRefinedAt(Instant.now(clock))
                .withLastAppliedDigest(digest(proposal.updatedBody()));
        persistState(skill.directory(), updatedState);
        resetMetrics(projectPath, skill.name(), tracker);
        return new RefinementOutcome(RefinementAction.REFINED, skill.name(), proposal.reason());
    }

    private RefinementOutcome deprecate(Path projectPath,
                                        SkillConfig skill,
                                        SkillOutcomeTracker tracker,
                                        RefinementState state,
                                        String reason) throws IOException {
        String originalMarkdown = readSkillMarkdown(skill);
        int nextVersion = state.currentVersion() + 1;
        backupVersion(skill.directory(), nextVersion, originalMarkdown);
        writeSkillMarkdown(skill.directory(), renderSkillMarkdown(skill, skill.body(), true, true));

        var updatedState = state.withCurrentVersion(nextVersion)
                .withDeprecated(true)
                .withRollbackBaselineSuccessRate(null)
                .withLastAction("DEPRECATED")
                .withLastReason(reason)
                .withLastRefinedAt(Instant.now(clock))
                .withLastAppliedDigest(digest(skill.body()));
        persistState(skill.directory(), updatedState);
        resetMetrics(projectPath, skill.name(), tracker);
        return new RefinementOutcome(RefinementAction.DEPRECATED, skill.name(), reason);
    }

    private RefinementOutcome rollbackToPreviousVersion(Path projectPath,
                                                        SkillConfig skill,
                                                        SkillOutcomeTracker tracker,
                                                        RefinementState state,
                                                        String reason) throws IOException {
        if (state.currentVersion() <= 0) {
            return RefinementOutcome.none();
        }
        Path previous = skill.directory().resolve(VERSIONS_DIR).resolve("v" + state.currentVersion() + ".md");
        if (!Files.isRegularFile(previous)) {
            return RefinementOutcome.none();
        }

        String restored = Files.readString(previous);
        writeSkillMarkdown(skill.directory(), restored);
        var updatedState = state.withCurrentVersion(Math.max(0, state.currentVersion() - 1))
                .withDeprecated(false)
                .withRollbackBaselineSuccessRate(null)
                .withLastAction("ROLLED_BACK")
                .withLastReason(reason)
                .withLastAppliedDigest(digest(restored));
        persistState(skill.directory(), updatedState);
        resetMetrics(projectPath, skill.name(), tracker);
        return new RefinementOutcome(RefinementAction.ROLLED_BACK, skill.name(), reason);
    }

    private SkillRefinement proposeRefinement(SkillConfig skill,
                                              List<SkillOutcome> recentOutcomes,
                                              String reason) throws LlmException {
        String prompt = buildRefinementPrompt(skill, recentOutcomes, reason);
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

    private String buildRefinementPrompt(SkillConfig skill, List<SkillOutcome> recentOutcomes, String reason) {
        var sb = new StringBuilder();
        sb.append("Skill name: ").append(skill.name()).append("\n");
        sb.append("Description: ").append(skill.description()).append("\n");
        sb.append("Refinement trigger: ").append(reason).append("\n\n");
        sb.append("Current skill body:\n---\n").append(skill.body().trim()).append("\n---\n\n");
        sb.append("Recent outcomes:\n");
        int index = 1;
        for (var outcome : recentOutcomes.subList(Math.max(0, recentOutcomes.size() - 12), recentOutcomes.size())) {
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
        sb.append("description: \"").append(escape(skill.description())).append("\"\n");
        if (skill.argumentHint() != null && !skill.argumentHint().isBlank()) {
            sb.append("argument-hint: \"").append(escape(skill.argumentHint())).append("\"\n");
        }
        sb.append("context: ").append(skill.context().name().toLowerCase(Locale.ROOT)).append("\n");
        if (skill.model() != null && !skill.model().isBlank()) {
            sb.append("model: \"").append(escape(skill.model())).append("\"\n");
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
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
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

    private static int trailingConsecutiveFailures(List<SkillOutcome> outcomes) {
        int count = 0;
        for (int i = outcomes.size() - 1; i >= 0; i--) {
            var outcome = outcomes.get(i);
            if (outcome instanceof SkillOutcome.Failure) {
                count++;
                continue;
            }
            if (outcome instanceof SkillOutcome.UserCorrected) {
                continue;
            }
            break;
        }
        return count;
    }

    private static int recentInvocationCount(List<SkillOutcome> outcomes) {
        int count = 0;
        for (int i = outcomes.size() - 1; i >= 0 && count < RECENT_INVOCATION_WINDOW; i--) {
            if (outcomes.get(i) instanceof SkillOutcome.Success || outcomes.get(i) instanceof SkillOutcome.Failure) {
                count++;
            }
        }
        return count;
    }

    private static String extractJson(String text) {
        var matcher = CODE_FENCE.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return text.trim();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
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
            return HexFormat.of().formatHex(md.digest((text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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

    private record SkillRefinement(String reason, String updatedBody) {}

    public enum RefinementAction {
        NONE,
        REFINED,
        DEPRECATED,
        ROLLED_BACK
    }

    public record RefinementOutcome(RefinementAction action, String skillName, String reason) {
        static RefinementOutcome none() {
            return new RefinementOutcome(RefinementAction.NONE, "", "");
        }
    }

    record RefinementState(
            int currentVersion,
            boolean deprecated,
            Double rollbackBaselineSuccessRate,
            Instant lastRefinedAt,
            String lastAction,
            String lastReason,
            String lastAppliedDigest
    ) {
        static final RefinementState EMPTY = new RefinementState(0, false, null, null, null, null, null);

        RefinementState {
            currentVersion = Math.max(0, currentVersion);
        }

        RefinementState withCurrentVersion(int value) {
            return new RefinementState(value, deprecated, rollbackBaselineSuccessRate, lastRefinedAt, lastAction, lastReason, lastAppliedDigest);
        }

        RefinementState withDeprecated(boolean value) {
            return new RefinementState(currentVersion, value, rollbackBaselineSuccessRate, lastRefinedAt, lastAction, lastReason, lastAppliedDigest);
        }

        RefinementState withRollbackBaselineSuccessRate(Double value) {
            return new RefinementState(currentVersion, deprecated, value, lastRefinedAt, lastAction, lastReason, lastAppliedDigest);
        }

        RefinementState withLastRefinedAt(Instant value) {
            return new RefinementState(currentVersion, deprecated, rollbackBaselineSuccessRate, value, lastAction, lastReason, lastAppliedDigest);
        }

        RefinementState withLastAction(String value) {
            return new RefinementState(currentVersion, deprecated, rollbackBaselineSuccessRate, lastRefinedAt, value, lastReason, lastAppliedDigest);
        }

        RefinementState withLastReason(String value) {
            return new RefinementState(currentVersion, deprecated, rollbackBaselineSuccessRate, lastRefinedAt, lastAction, value, lastAppliedDigest);
        }

        RefinementState withLastAppliedDigest(String value) {
            return new RefinementState(currentVersion, deprecated, rollbackBaselineSuccessRate, lastRefinedAt, lastAction, lastReason, value);
        }

        RefinementState normalize() {
            return new RefinementState(currentVersion, deprecated, rollbackBaselineSuccessRate, lastRefinedAt, lastAction, lastReason, lastAppliedDigest);
        }
    }
}
