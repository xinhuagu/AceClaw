package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Extracts a reusable skill draft from a completed session's conversation history.
 *
 * <p>Orchestrates the packing flow:
 * <ol>
 *   <li>Load session messages (live session or persisted history)</li>
 *   <li>Apply optional turn range filter</li>
 *   <li>LLM call to extract the successful execution path</li>
 *   <li>Parse structured output into a skill draft</li>
 *   <li>Generate SKILL.md with frontmatter</li>
 *   <li>Write to {@code .aceclaw/skills-drafts/{slug}/SKILL.md}</li>
 *   <li>Append audit trail</li>
 * </ol>
 */
public final class SessionSkillPacker {

    private static final Logger log = LoggerFactory.getLogger(SessionSkillPacker.class);

    private static final int MAX_SKILL_NAME = 48;
    private static final int MAX_SUFFIX_ATTEMPTS = 500;
    private static final int DEFAULT_MAX_TURNS = 8;
    private static final String DRAFTS_DIR = ".aceclaw/skills-drafts";
    private static final String AUDIT_DIR = ".aceclaw/metrics/continuous-learning";
    private static final String AUDIT_FILE = "session-skill-pack-audit.jsonl";

    /** Estimated chars per token (same as ContextEstimator). */
    private static final double CHARS_PER_TOKEN = 4.0;

    /** Tokens reserved for the extraction output + thinking budget. */
    private static final int OUTPUT_TOKEN_RESERVE = 8_192 + 4_096;

    /** Tokens reserved for the system prompt + overhead. */
    private static final int SYSTEM_OVERHEAD_TOKENS = 500;

    /**
     * Default maximum conversation chars when context window is unknown.
     * ~100K tokens worth of conversation — safe for 128K+ context models.
     */
    static final int DEFAULT_MAX_CONVERSATION_CHARS = 400_000;

    private final SessionHistoryStore historyStore;
    private final SessionManager sessionManager;
    private final LlmClient llmClient;
    private final String model;
    private final ObjectMapper objectMapper;
    private final int maxConversationChars;

    /**
     * Creates a packer with an explicit conversation character budget.
     *
     * @param maxConversationChars maximum chars allowed for the user prompt
     *                             (conversation text + extraction instructions)
     */
    public SessionSkillPacker(SessionHistoryStore historyStore,
                              SessionManager sessionManager,
                              LlmClient llmClient,
                              String model,
                              ObjectMapper objectMapper,
                              int maxConversationChars) {
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.model = Objects.requireNonNull(model, "model");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.maxConversationChars = maxConversationChars > 0 ? maxConversationChars : DEFAULT_MAX_CONVERSATION_CHARS;
    }

    /**
     * Creates a packer using the default conversation budget ({@value #DEFAULT_MAX_CONVERSATION_CHARS} chars).
     */
    public SessionSkillPacker(SessionHistoryStore historyStore,
                              SessionManager sessionManager,
                              LlmClient llmClient,
                              String model,
                              ObjectMapper objectMapper) {
        this(historyStore, sessionManager, llmClient, model, objectMapper, DEFAULT_MAX_CONVERSATION_CHARS);
    }

    /**
     * Derives the maximum conversation character budget from the context window.
     *
     * <p>Formula: (contextWindowTokens - outputReserve - systemOverhead) * charsPerToken.
     * Falls back to {@link #DEFAULT_MAX_CONVERSATION_CHARS} if computation yields
     * an unreasonably small value.
     */
    static int deriveMaxConversationChars(int contextWindowTokens) {
        if (contextWindowTokens <= 0) {
            return DEFAULT_MAX_CONVERSATION_CHARS;
        }
        int availableTokens = contextWindowTokens - OUTPUT_TOKEN_RESERVE - SYSTEM_OVERHEAD_TOKENS;
        int derived = (int) (availableTokens * CHARS_PER_TOKEN);
        // Floor at 20K chars (enough for a small conversation)
        return Math.max(20_000, derived);
    }

    /**
     * Extracts a skill draft from the given session.
     *
     * @param sessionId  the session to extract from
     * @param skillName  optional skill name override (null for auto-derived)
     * @param turnStart  optional start index (inclusive, 0-based) into messages
     * @param turnEnd    optional end index (exclusive) into messages
     * @param projectRoot the project working directory for output paths
     * @return the pack result with skill name, path, and step count
     * @throws IOException  if I/O fails
     * @throws LlmException if the LLM call fails
     */
    public PackResult pack(String sessionId, String skillName,
                           Integer turnStart, Integer turnEnd,
                           Path projectRoot) throws IOException, LlmException {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(projectRoot, "projectRoot");

        // 1. Load messages - try live session first, fall back to persisted history
        List<AgentSession.ConversationMessage> messages = loadMessages(sessionId);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("No messages found for session: " + sessionId);
        }

        log.info("Packing session {} ({} messages) into skill draft", sessionId, messages.size());

        // 2. Apply optional turn range filter
        messages = applyTurnRange(messages, turnStart, turnEnd);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException(
                    "No messages in specified turn range [" + turnStart + ", " + turnEnd + ")");
        }

        // 3. Build extraction prompt (with token budget) and call LLM
        String userPrompt = SessionSkillPackPrompt.buildExtractionPrompt(messages, maxConversationChars);
        var request = LlmRequest.builder()
                .model(model)
                .addMessage(Message.user(userPrompt))
                .systemPrompt(SessionSkillPackPrompt.systemPrompt())
                .maxTokens(8192)
                .thinkingBudget(4096)
                .temperature(0.3)
                .build();

        var response = llmClient.sendMessage(request);
        var text = response.text();
        if (text == null || text.isBlank()) {
            throw new LlmException("Empty response from LLM for skill extraction");
        }

        log.debug("Skill extraction response ({} chars)", text.length());

        // 4. Parse structured output
        SessionSkillPackPrompt.SkillDraft draft;
        try {
            draft = SessionSkillPackPrompt.parseResponse(text, objectMapper);
        } catch (IllegalArgumentException e) {
            throw new LlmException("Failed to parse skill extraction response", e);
        }

        // 5. Resolve skill name
        String resolvedName = skillName != null && !skillName.isBlank()
                ? toSlug(skillName)
                : toSlug(draft.name());

        // 6. Generate SKILL.md and write to disk
        Path draftsRoot = projectRoot.resolve(DRAFTS_DIR);
        String uniqueName = resolveUniqueName(draftsRoot, resolvedName, sessionId);
        Path skillDir = draftsRoot.resolve(uniqueName);
        Path skillFile = skillDir.resolve("SKILL.md");

        Files.createDirectories(skillDir);
        String markdown = renderSkillMarkdown(uniqueName, draft, sessionId, turnStart, turnEnd);

        // Atomic write
        Path tempFile = skillDir.resolve("SKILL.md.tmp");
        Files.writeString(tempFile, markdown);
        Files.move(tempFile, skillFile,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        // 7. Audit trail
        Path auditPath = projectRoot.resolve(AUDIT_DIR).resolve(AUDIT_FILE);
        Files.createDirectories(auditPath.getParent());
        appendAudit(auditPath, sessionId, uniqueName, projectRoot.relativize(skillFile));

        String relativePath = projectRoot.relativize(skillFile).toString().replace('\\', '/');
        log.info("Skill draft packed: name={}, path={}, steps={}",
                uniqueName, relativePath, draft.steps().size());

        return new PackResult(uniqueName, relativePath, draft.steps().size());
    }

    private List<AgentSession.ConversationMessage> loadMessages(String sessionId) {
        // Try live session first
        var liveSession = sessionManager.getSession(sessionId);
        if (liveSession != null && !liveSession.messages().isEmpty()) {
            return liveSession.messages();
        }

        // Fall back to persisted history
        return historyStore.loadSession(sessionId);
    }

    static List<AgentSession.ConversationMessage> applyTurnRange(
            List<AgentSession.ConversationMessage> messages,
            Integer turnStart, Integer turnEnd) {
        int start = turnStart != null ? Math.max(0, turnStart) : 0;
        int end = turnEnd != null ? Math.min(messages.size(), turnEnd) : messages.size();
        if (start >= end || start >= messages.size()) {
            return List.of();
        }
        return messages.subList(start, end);
    }

    private String resolveUniqueName(Path draftsRoot, String baseName, String sessionId) throws IOException {
        // First, scan all existing drafts for one from the same session (idempotency).
        // This ensures repacking the same session reuses the existing draft even if the
        // LLM-derived name differs between runs.
        String existing = findExistingDraftForSession(draftsRoot, sessionId);
        if (existing != null) {
            return existing;
        }

        String current = baseName;
        int suffix = 2;
        int attempts = 0;
        while (attempts < MAX_SUFFIX_ATTEMPTS) {
            Path skillFile = draftsRoot.resolve(current).resolve("SKILL.md");
            if (!Files.exists(skillFile)) {
                return current;
            }
            current = truncateName(baseName + "-" + suffix, MAX_SKILL_NAME);
            suffix++;
            attempts++;
        }
        throw new IOException("Failed to resolve unique skill name for '" + baseName
                + "' after " + MAX_SUFFIX_ATTEMPTS + " attempts");
    }

    /**
     * Scans all existing draft directories for a SKILL.md that was packed from the given session.
     *
     * @return the directory name if found, or null
     */
    private static String findExistingDraftForSession(Path draftsRoot, String sessionId) throws IOException {
        if (!Files.isDirectory(draftsRoot)) {
            return null;
        }
        try (var dirs = Files.list(draftsRoot)) {
            for (var dir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(dir)) continue;
                Path skillFile = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) continue;
                String content = Files.readString(skillFile);
                if (content.contains("source-session-id: \"" + sessionId + "\"")
                        || content.contains("source-session-id: " + sessionId)) {
                    return dir.getFileName().toString();
                }
            }
        }
        return null;
    }

    private static String renderSkillMarkdown(String skillName,
                                               SessionSkillPackPrompt.SkillDraft draft,
                                               String sessionId,
                                               Integer turnStart,
                                               Integer turnEnd) {
        String description = truncate(draft.description(), 120);
        String allowedTools = draft.tools().isEmpty()
                ? "[]"
                : "[" + draft.tools().stream().map(t -> quoted(t)).collect(Collectors.joining(", ")) + "]";

        String turnRange = (turnStart != null || turnEnd != null)
                ? (turnStart != null ? turnStart : "0") + "-" + (turnEnd != null ? turnEnd : "end")
                : "full";

        var sb = new StringBuilder();

        // Frontmatter
        sb.append("---\n");
        sb.append("name: ").append(quoted(skillName)).append("\n");
        sb.append("description: ").append(quoted(description)).append("\n");
        sb.append("context: ").append(quoted("INLINE")).append("\n");
        sb.append("allowed-tools: ").append(allowedTools).append("\n");
        sb.append("max-turns: ").append(DEFAULT_MAX_TURNS).append("\n");
        sb.append("disable-model-invocation: true\n");
        sb.append("source-session-id: ").append(quoted(sessionId)).append("\n");
        sb.append("source-turn-range: ").append(quoted(turnRange)).append("\n");
        sb.append("---\n\n");

        // Body
        sb.append("# ").append(draft.name() != null ? draft.name() : skillName).append("\n\n");
        sb.append(draft.description() != null ? draft.description() : "").append("\n\n");

        // Preconditions
        sb.append("## Preconditions\n\n");
        if (draft.preconditions().isEmpty()) {
            sb.append("- None\n");
        } else {
            for (var pre : draft.preconditions()) {
                sb.append("- ").append(pre).append("\n");
            }
        }
        sb.append("\n");

        // Steps
        sb.append("## Steps\n\n");
        int stepNum = 1;
        for (var step : draft.steps()) {
            sb.append("### Step ").append(stepNum++).append(": ").append(step.description()).append("\n\n");
            if (step.tool() != null && !step.tool().isBlank() && !"null".equals(step.tool())) {
                sb.append("- **Tool**: `").append(step.tool()).append("`\n");
            }
            if (step.parametersHint() != null && !step.parametersHint().isBlank()
                    && !"null".equals(step.parametersHint())) {
                sb.append("- **Parameters**: ").append(step.parametersHint()).append("\n");
            }
            if (step.successCheck() != null && !step.successCheck().isBlank()
                    && !"null".equals(step.successCheck())) {
                sb.append("- **Success check**: ").append(step.successCheck()).append("\n");
            }
            if (step.failureGuidance() != null && !step.failureGuidance().isBlank()
                    && !"null".equals(step.failureGuidance())) {
                sb.append("- **On failure**: ").append(step.failureGuidance()).append("\n");
            }
            sb.append("\n");
        }

        // Success checks
        sb.append("## Success Checks\n\n");
        if (draft.successChecks().isEmpty()) {
            sb.append("- None\n");
        } else {
            for (var check : draft.successChecks()) {
                sb.append("- ").append(check).append("\n");
            }
        }
        sb.append("\n");

        // Source
        sb.append("## Source\n\n");
        sb.append("- session-id: `").append(sessionId).append("`\n");
        sb.append("- turn-range: `").append(turnRange).append("`\n");
        sb.append("- packed-at: `").append(Instant.now()).append("`\n");

        return sb.toString();
    }

    private void appendAudit(Path auditPath, String sessionId,
                             String skillName, Path relativeSkillPath) throws IOException {
        var node = objectMapper.createObjectNode();
        node.put("timestamp", Instant.now().toString());
        node.put("action", "session-pack");
        node.put("sessionId", sessionId);
        node.put("skillName", skillName);
        node.put("path", relativeSkillPath.toString().replace('\\', '/'));
        byte[] line = (objectMapper.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);

        // Use FileChannel + FileLock to prevent concurrent pack calls from interleaving JSONL lines
        try (var channel = FileChannel.open(auditPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            try (var ignored = channel.lock()) {
                channel.write(ByteBuffer.wrap(line));
            }
        }
    }

    static String toSlug(String input) {
        if (input == null || input.isBlank()) {
            return "extracted-skill";
        }
        String raw = input.toLowerCase(Locale.ROOT);
        raw = raw.replaceAll("[^a-z0-9]+", "-");
        raw = raw.replaceAll("^-+|-+$", "");
        if (raw.isBlank()) {
            return "extracted-skill";
        }
        return truncateName(raw, MAX_SKILL_NAME);
    }

    private static String truncateName(String value, int maxLen) {
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen).replaceAll("-+$", "");
    }

    private static String quoted(String s) {
        if (s == null) {
            return "\"\"";
        }
        String escaped = s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }

    /**
     * Result of a session skill pack operation.
     *
     * @param skillName    the resolved skill name (slug)
     * @param relativePath the path to the generated SKILL.md relative to project root
     * @param stepCount    the number of extracted steps
     */
    public record PackResult(String skillName, String relativePath, int stepCount) {}
}
