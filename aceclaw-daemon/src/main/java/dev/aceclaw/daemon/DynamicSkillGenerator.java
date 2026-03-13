package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.SkillConfig;
import dev.aceclaw.core.agent.SkillRegistry;
import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.memory.Insight;
import dev.aceclaw.memory.PatternType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Generates session-scoped runtime skills from repeated tool sequences.
 *
 * <p>Runtime skills are visible only to the originating session and are persisted
 * as drafts on session end. The first implementation is intentionally conservative:
 * it only generates FORK-mode skills from repeated tool sequences and skips any
 * sequence that includes {@code bash}.
 */
public final class DynamicSkillGenerator {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillGenerator.class);

    static final int MAX_RUNTIME_SKILLS_PER_SESSION = 3;
    static final int MIN_SUCCESSFUL_USES_FOR_DRAFT = 2;
    static final int MAX_RUNTIME_FAILURES = 2;
    static final int MAX_RUNTIME_CORRECTIONS = 1;
    static final Duration RUNTIME_IDLE_TTL = Duration.ofMinutes(20);
    private static final int DEFAULT_MAX_TURNS = 6;
    private static final String DRAFTS_DIR = ".aceclaw/skills-drafts";
    private static final Pattern CODE_FENCE = Pattern.compile(
            "```(?:json)?\\s*\\n?(\\{.*?})\\s*```",
            Pattern.DOTALL);
    private static final Pattern BASH_MENTION = Pattern.compile("(?i)\\bbash\\b");
    private static final Set<String> DISALLOWED_RUNTIME_TOOLS = Set.of("bash", "skill", "task", "task_output");

    private final LlmClient llmClient;
    private final Function<String, String> modelResolver;
    private final SkillRegistry skillRegistry;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, SessionRuntimeState> sessionStates = new ConcurrentHashMap<>();
    private final Set<String> closedSessions = ConcurrentHashMap.newKeySet();
    private LearningExplanationRecorder learningExplanationRecorder;
    private LearningValidationRecorder learningValidationRecorder;

    public DynamicSkillGenerator(LlmClient llmClient,
                                 Function<String, String> modelResolver,
                                 SkillRegistry skillRegistry) {
        this(llmClient, modelResolver, skillRegistry, Clock.systemUTC());
    }

    DynamicSkillGenerator(LlmClient llmClient,
                          Function<String, String> modelResolver,
                          SkillRegistry skillRegistry,
                          Clock clock) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.modelResolver = Objects.requireNonNull(modelResolver, "modelResolver");
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void setLearningExplanationRecorder(LearningExplanationRecorder learningExplanationRecorder) {
        this.learningExplanationRecorder = learningExplanationRecorder;
    }

    public void setLearningValidationRecorder(LearningValidationRecorder learningValidationRecorder) {
        this.learningValidationRecorder = learningValidationRecorder;
    }

    public java.util.Optional<SkillConfig> maybeGenerate(String sessionId,
                                                         Path projectPath,
                                                         Turn turn,
                                                         List<AgentSession.ConversationMessage> sessionHistory,
                                                         List<Insight> insights,
                                                         Set<String> sessionToolNames) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(sessionHistory, "sessionHistory");
        Objects.requireNonNull(insights, "insights");
        Objects.requireNonNull(sessionToolNames, "sessionToolNames");

        if (closedSessions.contains(sessionId)) {
            return java.util.Optional.empty();
        }

        pruneExpired(sessionId, projectPath);

        var toolSequence = extractToolSequence(turn.newMessages());
        if (toolSequence.size() < 3 || !hasRepeatedSequenceInsight(insights, toolSequence)) {
            return java.util.Optional.empty();
        }

        var allowedTools = filterAllowedTools(toolSequence, sessionToolNames);
        if (allowedTools.isEmpty()) {
            return java.util.Optional.empty();
        }

        String sequenceSignature = signatureOf(toolSequence);
        var state = sessionStates.computeIfAbsent(sessionId, ignored -> new SessionRuntimeState());
        synchronized (state) {
            if (closedSessions.contains(sessionId) || state.closing) {
                return java.util.Optional.empty();
            }
            pruneExpiredLocked(sessionId, projectPath, state, clock.instant());
            if (state.suppressedSignatures.contains(sequenceSignature)) {
                return java.util.Optional.empty();
            }
            var conflictingSkill = conflictingDurableSkill(allowedTools);
            if (conflictingSkill.isPresent()) {
                state.suppressedSignatures.add(sequenceSignature);
                if (learningExplanationRecorder != null) {
                    learningExplanationRecorder.recordRuntimeSkillConflict(
                            projectPath,
                            sessionId,
                            conflictingSkill.get().name(),
                            sequenceSignature,
                            allowedTools);
                }
                if (learningValidationRecorder != null) {
                    learningValidationRecorder.recordRuntimeSkillConflict(
                            projectPath,
                            sessionId,
                            conflictingSkill.get().name(),
                            sequenceSignature,
                            allowedTools);
                }
                return java.util.Optional.empty();
            }
            if (state.skillsByName.size() >= MAX_RUNTIME_SKILLS_PER_SESSION) {
                return java.util.Optional.empty();
            }
            if (state.signatures.contains(sequenceSignature)) {
                return java.util.Optional.empty();
            }

            final RuntimeSkillDraft draft;
            try {
                draft = generateDraft(sessionId, projectPath, allowedTools, sessionHistory);
            } catch (IllegalArgumentException e) {
                log.debug("Skipping runtime skill generation for session {}: {}", sessionId, e.getMessage());
                return java.util.Optional.empty();
            }
            String skillName = resolveRuntimeName(sessionId, draft.name(), state);
            var config = new SkillConfig(
                    skillName,
                    draft.description(),
                    draft.argumentHint(),
                    SkillConfig.ExecutionContext.FORK,
                    null,
                    allowedTools,
                    DEFAULT_MAX_TURNS,
                    true,
                    false,
                    draft.body(),
                    runtimeDirectory(projectPath, skillName));

            if (!skillRegistry.registerRuntime(sessionId, config)) {
                return java.util.Optional.empty();
            }
            state.signatures.add(sequenceSignature);
            Instant now = clock.instant();
            state.skillsByName.put(skillName,
                    RuntimeSkillRecord.created(config, now, List.copyOf(allowedTools), sequenceSignature));
            if (learningExplanationRecorder != null) {
                learningExplanationRecorder.recordRuntimeSkill(
                        projectPath,
                        sessionId,
                        skillName,
                        sequenceSignature,
                        allowedTools);
            }
            if (learningValidationRecorder != null) {
                learningValidationRecorder.recordRuntimeSkillObserved(
                        projectPath,
                        sessionId,
                        skillName,
                        sequenceSignature,
                        allowedTools);
            }
            log.info("Registered runtime skill '{}' for session {}", skillName, sessionId);
            return java.util.Optional.of(config);
        }
    }

    public void onOutcome(String sessionId, Path projectPath, String skillName, dev.aceclaw.core.agent.SkillOutcome outcome) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(skillName, "skillName");
        Objects.requireNonNull(outcome, "outcome");

        var state = sessionStates.get(sessionId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            pruneExpiredLocked(sessionId, projectPath, state, clock.instant());
            var current = state.skillsByName.get(skillName);
            if (current == null) {
                return;
            }
            var updated = current.recordOutcome(outcome, clock.instant(), RUNTIME_IDLE_TTL);
            state.skillsByName.put(skillName, updated);
            if (updated.shouldSuppress()) {
                suppressRuntimeSkill(sessionId, projectPath, state, updated, updated.suppressionReason());
            }
        }
    }

    public void pruneExpired(String sessionId, Path projectPath) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(projectPath, "projectPath");
        var state = sessionStates.get(sessionId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            pruneExpiredLocked(sessionId, projectPath, state, clock.instant());
        }
    }

    public int persistDrafts(String sessionId, Path projectPath) throws IOException {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(projectPath, "projectPath");

        closedSessions.add(sessionId);
        var state = sessionStates.get(sessionId);
        if (state == null) {
            skillRegistry.clearRuntime(sessionId);
            return 0;
        }

        int persisted = 0;
        boolean success = false;
        synchronized (state) {
            if (state.closing) {
                return 0;
            }
            state.closing = true;
            pruneExpiredLocked(sessionId, projectPath, state, clock.instant());
            Path draftsRoot = projectPath.resolve(DRAFTS_DIR);
            try {
                Files.createDirectories(draftsRoot);
                for (var record : state.skillsByName.values()) {
                    if (!record.eligibleForDraft()) {
                        if (learningExplanationRecorder != null) {
                            learningExplanationRecorder.recordRuntimeSkillNotPromoted(
                                    projectPath,
                                    sessionId,
                                    record.skill().name(),
                                    record.promotionReason(),
                                    record.successCount(),
                                    record.failureCount(),
                                    record.correctionCount());
                        }
                        if (learningValidationRecorder != null) {
                            learningValidationRecorder.recordRuntimeSkillNotPromoted(
                                    projectPath,
                                    sessionId,
                                    record.skill().name(),
                                    record.promotionReason(),
                                    record.successCount(),
                                    record.failureCount(),
                                    record.correctionCount());
                        }
                        continue;
                    }
                    String resolvedName = resolveDraftName(draftsRoot, record.skill().name(), sessionId);
                    Path skillDir = draftsRoot.resolve(resolvedName);
                    Files.createDirectories(skillDir);
                    Path skillFile = skillDir.resolve("SKILL.md");
                    if (!Files.exists(skillFile)) {
                        Path temp = skillDir.resolve("SKILL.md.tmp");
                        Files.writeString(temp, renderDraftMarkdown(resolvedName, record, sessionId));
                        Files.move(temp, skillFile,
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        if (learningExplanationRecorder != null) {
                            learningExplanationRecorder.recordRuntimeSkillDraftPersisted(
                                    projectPath,
                                    sessionId,
                                    resolvedName,
                                    projectPath.relativize(skillFile).toString().replace('\\', '/'));
                        }
                        if (learningValidationRecorder != null) {
                            learningValidationRecorder.recordRuntimeSkillAwaitingDurableValidation(
                                    projectPath,
                                    sessionId,
                                    resolvedName,
                                    projectPath.relativize(skillFile).toString().replace('\\', '/'));
                        }
                        persisted++;
                    }
                }
                success = true;
            } finally {
                if (!success) {
                    state.closing = false;
                    closedSessions.remove(sessionId);
                }
            }
        }

        sessionStates.remove(sessionId, state);
        skillRegistry.clearRuntime(sessionId);
        return persisted;
    }

    static List<String> extractToolSequence(List<Message> messages) {
        var toolSequence = new ArrayList<String>();
        for (var msg : messages) {
            if (msg instanceof Message.AssistantMessage assistant) {
                for (var block : assistant.content()) {
                    if (block instanceof ContentBlock.ToolUse toolUse) {
                        toolSequence.add(toolUse.name());
                    }
                }
            }
        }
        return List.copyOf(toolSequence);
    }

    private static boolean hasRepeatedSequenceInsight(List<Insight> insights, List<String> toolSequence) {
        var signature = signatureOf(toolSequence);
        return insights.stream()
                .filter(Insight.PatternInsight.class::isInstance)
                .map(Insight.PatternInsight.class::cast)
                .anyMatch(insight -> insight.patternType() == PatternType.REPEATED_TOOL_SEQUENCE
                        && insight.frequency() >= 3
                        && signature.equals(extractInsightSequenceSignature(insight.description())));
    }

    private RuntimeSkillDraft generateDraft(String sessionId,
                                            Path projectPath,
                                            List<String> allowedTools,
                                            List<AgentSession.ConversationMessage> sessionHistory) {
        try {
            return sanitizeDraft(proposeDraft(sessionId, projectPath, allowedTools, sessionHistory));
        } catch (Exception e) {
            log.warn("Dynamic runtime skill generation fell back to template: {}", e.getMessage());
            return sanitizeDraft(fallbackDraft(allowedTools, sessionHistory));
        }
    }

    private RuntimeSkillDraft proposeDraft(String sessionId,
                                           Path projectPath,
                                           List<String> allowedTools,
                                           List<AgentSession.ConversationMessage> sessionHistory) throws LlmException {
        String prompt = buildPrompt(projectPath, allowedTools, sessionHistory);
        var request = LlmRequest.builder()
                .model(modelResolver.apply(sessionId))
                .systemPrompt("""
                        You create concise runtime skills for an autonomous coding agent.
                        Return ONLY valid JSON with fields:
                        {
                          "name": "short-kebab-or-short-title",
                          "description": "one sentence",
                          "argument_hint": "<optional args or empty>",
                          "body": "markdown instructions for a FORK-mode skill"
                        }
                        Rules:
                        - Keep the skill narrow and reusable.
                        - Do not mention bash.
                        - Assume only the listed tools are allowed.
                        - Focus on the repeated workflow, not the whole task.
                        """)
                .addMessage(Message.user(prompt))
                .maxTokens(2048)
                .thinkingBudget(1024)
                .temperature(0.2)
                .build();
        var response = llmClient.sendMessage(request);
        String text = response.text();
        if (text == null || text.isBlank()) {
            throw new LlmException("Empty runtime skill response");
        }
        return parseDraft(text);
    }

    private String buildPrompt(Path projectPath,
                               List<String> allowedTools,
                               List<AgentSession.ConversationMessage> sessionHistory) {
        String recentPrompts = recentUserPrompts(sessionHistory, 3);
        return """
                Create a reusable runtime skill for a repeated workflow inside this project.

                Project path: %s
                Repeated tool sequence: %s
                Allowed tools: %s

                Recent user prompts:
                %s
                """.formatted(
                projectPath,
                String.join(" -> ", allowedTools),
                String.join(", ", allowedTools),
                recentPrompts.isBlank() ? "- none" : recentPrompts);
    }

    private RuntimeSkillDraft parseDraft(String text) throws LlmException {
        try {
            JsonNode node = objectMapper.readTree(extractJson(text));
            String name = trimToNull(node.path("name").asText(""));
            String description = trimToNull(node.path("description").asText(""));
            String argumentHint = trimToNull(node.path("argument_hint").asText(""));
            String body = trimToNull(node.path("body").asText(""));
            if (description == null || body == null) {
                throw new LlmException("Runtime skill response missing description/body");
            }
            return sanitizeDraft(new RuntimeSkillDraft(
                    name != null ? name : "runtime-workflow",
                    description,
                    argumentHint,
                    body));
        } catch (IOException e) {
            throw new LlmException("Failed to parse runtime skill response: " + e.getMessage());
        }
    }

    private static RuntimeSkillDraft sanitizeDraft(RuntimeSkillDraft draft) {
        Objects.requireNonNull(draft, "draft");
        var description = trimToNull(draft.description());
        var body = trimToNull(draft.body());
        if (description == null || body == null) {
            throw new IllegalArgumentException("Runtime skill draft missing description/body");
        }
        if (BASH_MENTION.matcher(body).find()) {
            throw new IllegalArgumentException("Runtime skill draft contains disallowed bash content");
        }
        var name = trimToNull(draft.name());
        return new RuntimeSkillDraft(
                name != null ? name : "runtime-workflow",
                description,
                trimToNull(draft.argumentHint()),
                body);
    }

    private static String extractJson(String text) {
        var matcher = CODE_FENCE.matcher(text);
        return matcher.find() ? matcher.group(1) : text.trim();
    }

    private static RuntimeSkillDraft fallbackDraft(List<String> allowedTools,
                                                   List<AgentSession.ConversationMessage> sessionHistory) {
        String name = "runtime-" + toSlug(String.join("-", allowedTools));
        String lastPrompt = latestUserPrompt(sessionHistory);
        String description = "Reusable workflow for " + String.join(" -> ", allowedTools);
        StringBuilder body = new StringBuilder();
        body.append("# Runtime Workflow\n\n");
        if (!lastPrompt.isBlank()) {
            body.append("Goal context: ").append(lastPrompt).append("\n\n");
        }
        body.append("Execute this workflow using the allowed tools only.\n\n");
        body.append("## Steps\n");
        for (int i = 0; i < allowedTools.size(); i++) {
            body.append(i + 1).append(". Use `").append(allowedTools.get(i)).append("` when appropriate.\n");
        }
        body.append("\nReturn a concise summary of what you found or changed.");
        return new RuntimeSkillDraft(name, description, null, body.toString());
    }

    private static List<String> filterAllowedTools(List<String> toolSequence, Set<String> sessionToolNames) {
        var allowed = new LinkedHashSet<String>();
        for (var tool : toolSequence) {
            if (tool == null || tool.isBlank()) {
                continue;
            }
            if (!sessionToolNames.contains(tool)) {
                return List.of();
            }
            if (DISALLOWED_RUNTIME_TOOLS.contains(tool)) {
                return List.of();
            }
            allowed.add(tool);
        }
        return List.copyOf(allowed);
    }

    private java.util.Optional<SkillConfig> conflictingDurableSkill(List<String> allowedTools) {
        return skillRegistry.all().stream()
                .filter(skill -> skill.context() == SkillConfig.ExecutionContext.FORK)
                .filter(skill -> !skill.disableModelInvocation() || skill.userInvocable())
                .filter(skill -> skill.allowedTools().equals(allowedTools))
                .findFirst();
    }

    private void pruneExpiredLocked(String sessionId,
                                    Path projectPath,
                                    SessionRuntimeState state,
                                    Instant now) {
        var iterator = state.skillsByName.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var record = entry.getValue();
            if (!record.isExpired(now)) {
                continue;
            }
            iterator.remove();
            state.signatures.remove(record.signature());
            skillRegistry.removeRuntime(sessionId, entry.getKey());
            if (learningExplanationRecorder != null) {
                learningExplanationRecorder.recordRuntimeSkillExpired(
                        projectPath,
                        sessionId,
                        entry.getKey(),
                        "inactive for more than " + RUNTIME_IDLE_TTL.toMinutes() + " minutes");
            }
            if (learningValidationRecorder != null) {
                learningValidationRecorder.recordRuntimeSkillExpired(
                        projectPath,
                        sessionId,
                        entry.getKey(),
                        "Inactive runtime skill was removed before durable promotion");
            }
        }
    }

    private void suppressRuntimeSkill(String sessionId,
                                      Path projectPath,
                                      SessionRuntimeState state,
                                      RuntimeSkillRecord record,
                                      String reason) {
        state.skillsByName.remove(record.skill().name());
        state.signatures.remove(record.signature());
        state.suppressedSignatures.add(record.signature());
        skillRegistry.removeRuntime(sessionId, record.skill().name());
        if (learningExplanationRecorder != null) {
            learningExplanationRecorder.recordRuntimeSkillSuppressed(
                    projectPath,
                    sessionId,
                    record.skill().name(),
                    reason,
                    record.successCount(),
                    record.failureCount(),
                    record.correctionCount());
        }
        if (learningValidationRecorder != null) {
            learningValidationRecorder.recordRuntimeSkillSuppressed(
                    projectPath,
                    sessionId,
                    record.skill().name(),
                    reason,
                    record.successCount(),
                    record.failureCount(),
                    record.correctionCount());
        }
    }

    private String resolveRuntimeName(String sessionId, String rawName, SessionRuntimeState state) {
        String base = toSlug(rawName);
        if (base.isBlank()) {
            base = "runtime-workflow";
        }
        String current = base;
        int suffix = 2;
        while (state.skillsByName.containsKey(current)
                || skillRegistry.get(sessionId, current).isPresent()) {
            current = base + "-" + suffix++;
        }
        return current;
    }

    private static String resolveDraftName(Path draftsRoot, String baseName, String sessionId) throws IOException {
        String current = baseName;
        int suffix = 2;
        while (true) {
            Path skillFile = draftsRoot.resolve(current).resolve("SKILL.md");
            if (!Files.exists(skillFile)) {
                return current;
            }
            String content = Files.readString(skillFile);
            if (content.contains("source-session-id: \"" + sessionId + "\"")
                    || content.contains("source-session-id: " + sessionId)) {
                return current;
            }
            current = baseName + "-" + suffix++;
        }
    }

    private static String renderDraftMarkdown(String skillName, RuntimeSkillRecord record, String sessionId) {
        String allowedTools = record.allowedTools().isEmpty()
                ? "[]"
                : "[" + record.allowedTools().stream().map(DynamicSkillGenerator::quoted).reduce((a, b) -> a + ", " + b).orElse("") + "]";
        return """
                ---
                name: %s
                description: %s
                argument-hint: %s
                context: %s
                allowed-tools: %s
                max-turns: %d
                disable-model-invocation: true
                source-session-id: %s
                runtime-generated: true
                source-tool-sequence: %s
                ---

                %s
                """.formatted(
                quoted(skillName),
                quoted(record.skill().description()),
                quoted(record.skill().argumentHint()),
                quoted(record.skill().context().name()),
                allowedTools,
                record.skill().maxTurns(),
                quoted(sessionId),
                quoted(String.join(" -> ", record.allowedTools())),
                record.skill().body()
        );
    }

    private static String recentUserPrompts(List<AgentSession.ConversationMessage> sessionHistory, int limit) {
        var prompts = new ArrayList<String>();
        for (int i = sessionHistory.size() - 1; i >= 0 && prompts.size() < limit; i--) {
            var message = sessionHistory.get(i);
            if (message instanceof AgentSession.ConversationMessage.User user) {
                String text = trimToNull(user.content());
                if (text != null) {
                    prompts.add(0, "- " + text);
                }
            }
        }
        return String.join("\n", prompts);
    }

    private static String latestUserPrompt(List<AgentSession.ConversationMessage> sessionHistory) {
        for (int i = sessionHistory.size() - 1; i >= 0; i--) {
            var message = sessionHistory.get(i);
            if (message instanceof AgentSession.ConversationMessage.User user) {
                return user.content() == null ? "" : user.content();
            }
        }
        return "";
    }

    private static String signatureOf(List<String> allowedTools) {
        return String.join("->", allowedTools);
    }

    private static String extractInsightSequenceSignature(String description) {
        if (description == null) {
            return "";
        }
        int start = description.indexOf('[');
        int end = description.indexOf(']');
        if (start < 0 || end <= start) {
            return "";
        }
        return description.substring(start + 1, end).replace(" ", "");
    }

    private static Path runtimeDirectory(Path projectPath, String skillName) {
        return projectPath.resolve(".aceclaw").resolve("runtime-skills").resolve(skillName);
    }

    private static String toSlug(String input) {
        if (input == null || input.isBlank()) {
            return "runtime-workflow";
        }
        String raw = input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return raw.isBlank() ? "runtime-workflow" : raw;
    }

    private static String quoted(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class SessionRuntimeState {
        private final LinkedHashMap<String, RuntimeSkillRecord> skillsByName = new LinkedHashMap<>();
        private final LinkedHashSet<String> signatures = new LinkedHashSet<>();
        private final LinkedHashSet<String> suppressedSignatures = new LinkedHashSet<>();
        private boolean closing;
    }

    private record RuntimeSkillRecord(
            SkillConfig skill,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt,
            List<String> allowedTools,
            String signature,
            int useCount,
            int successCount,
            int failureCount,
            int correctionCount
    ) {
        private RuntimeSkillRecord {
            Objects.requireNonNull(skill, "skill");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(lastUsedAt, "lastUsedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            allowedTools = allowedTools != null ? List.copyOf(allowedTools) : List.of();
            signature = Objects.requireNonNull(signature, "signature");
        }

        private static RuntimeSkillRecord created(SkillConfig skill,
                                                  Instant now,
                                                  List<String> allowedTools,
                                                  String signature) {
            return new RuntimeSkillRecord(skill, now, now, now.plus(RUNTIME_IDLE_TTL), allowedTools, signature, 0, 0, 0, 0);
        }

        private RuntimeSkillRecord recordOutcome(dev.aceclaw.core.agent.SkillOutcome outcome,
                                                 Instant now,
                                                 Duration ttl) {
            int nextUses = useCount + 1;
            int nextSuccess = successCount;
            int nextFailure = failureCount;
            int nextCorrection = correctionCount;
            if (outcome instanceof dev.aceclaw.core.agent.SkillOutcome.Success) {
                nextSuccess++;
            } else if (outcome instanceof dev.aceclaw.core.agent.SkillOutcome.Failure) {
                nextFailure++;
            } else if (outcome instanceof dev.aceclaw.core.agent.SkillOutcome.UserCorrected) {
                nextCorrection++;
            }
            return new RuntimeSkillRecord(
                    skill,
                    createdAt,
                    now,
                    now.plus(ttl),
                    allowedTools,
                    signature,
                    nextUses,
                    nextSuccess,
                    nextFailure,
                    nextCorrection);
        }

        private boolean eligibleForDraft() {
            return successCount >= MIN_SUCCESSFUL_USES_FOR_DRAFT
                    && failureCount == 0
                    && correctionCount == 0;
        }

        private boolean shouldSuppress() {
            return failureCount >= MAX_RUNTIME_FAILURES || correctionCount >= MAX_RUNTIME_CORRECTIONS;
        }

        private boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }

        private String suppressionReason() {
            if (correctionCount >= MAX_RUNTIME_CORRECTIONS) {
                return "user corrected the workflow";
            }
            if (failureCount >= MAX_RUNTIME_FAILURES) {
                return "runtime skill failed " + failureCount + " times";
            }
            return "runtime governance suppressed the skill";
        }

        private String promotionReason() {
            if (successCount < MIN_SUCCESSFUL_USES_FOR_DRAFT) {
                return "needs at least " + MIN_SUCCESSFUL_USES_FOR_DRAFT + " successful uses before draft persistence";
            }
            if (failureCount > 0) {
                return "observed failures make the runtime skill ineligible for durable promotion";
            }
            if (correctionCount > 0) {
                return "user corrections make the runtime skill ineligible for durable promotion";
            }
            return "runtime skill did not meet durable promotion criteria";
        }
    }

    record RuntimeSkillDraft(
            String name,
            String description,
            String argumentHint,
            String body
    ) {}
}
