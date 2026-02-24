package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.memory.CandidateState;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.LearningCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Generates draft skills from promoted learning candidates.
 *
 * <p>Output location:
 * <pre>.aceclaw/skills-drafts/&lt;skill-name&gt;/SKILL.md</pre>
 * with model auto-invocation explicitly disabled.
 */
public final class SkillDraftGenerator {

    private static final Logger log = LoggerFactory.getLogger(SkillDraftGenerator.class);

    private static final int MAX_SKILL_NAME = 48;
    private static final int DEFAULT_MAX_TURNS = 8;

    private static final String DRAFTS_DIR = ".aceclaw/skills-drafts";
    private static final String AUDIT_DIR = ".aceclaw/metrics/continuous-learning";
    private static final String AUDIT_FILE = "skill-draft-audit.jsonl";

    private final Clock clock;
    private final ObjectMapper mapper;

    public SkillDraftGenerator() {
        this(Clock.systemUTC());
    }

    SkillDraftGenerator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public GenerationSummary generateFromPromoted(CandidateStore candidateStore, Path projectRoot) throws IOException {
        Objects.requireNonNull(candidateStore, "candidateStore");
        Objects.requireNonNull(projectRoot, "projectRoot");

        Path draftsRoot = projectRoot.resolve(DRAFTS_DIR);
        Path auditPath = projectRoot.resolve(AUDIT_DIR).resolve(AUDIT_FILE);
        Files.createDirectories(draftsRoot);
        Files.createDirectories(auditPath.getParent());

        var promoted = candidateStore.byState(CandidateState.PROMOTED).stream()
                .sorted(Comparator.comparing(LearningCandidate::firstSeenAt)
                        .thenComparing(LearningCandidate::id))
                .toList();

        int created = 0;
        int updated = 0;
        var draftPaths = new ArrayList<String>();

        for (var candidate : promoted) {
            String baseName = baseSkillName(candidate);
            String resolvedName = resolveSkillName(draftsRoot, baseName, candidate.id());
            Path skillDir = draftsRoot.resolve(resolvedName);
            Path skillFile = skillDir.resolve("SKILL.md");
            boolean existed = Files.exists(skillFile);

            Files.createDirectories(skillDir);
            Files.writeString(skillFile, renderSkillMarkdown(resolvedName, candidate));

            if (existed) {
                updated++;
            } else {
                created++;
            }
            draftPaths.add(projectRoot.relativize(skillFile).toString());
            appendAudit(auditPath, candidate, resolvedName, projectRoot.relativize(skillFile), existed ? "updated" : "created");
        }

        return new GenerationSummary(promoted.size(), created, updated, List.copyOf(draftPaths), auditPath);
    }

    private void appendAudit(Path auditPath,
                             LearningCandidate candidate,
                             String skillName,
                             Path relativeSkillPath,
                             String action) {
        try {
            var node = mapper.createObjectNode();
            node.put("timestamp", Instant.now(clock).toString());
            node.put("action", action);
            node.put("candidateId", candidate.id());
            node.put("candidateKind", candidate.kind().name());
            node.put("skillName", skillName);
            node.put("path", relativeSkillPath.toString().replace('\\', '/'));
            Files.writeString(
                    auditPath,
                    mapper.writeValueAsString(node) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            log.warn("Failed to append skill draft audit entry: {}", e.getMessage());
        }
    }

    private static String resolveSkillName(Path draftsRoot, String baseName, String candidateId) throws IOException {
        String current = baseName;
        int suffix = 2;
        while (true) {
            Path skillFile = draftsRoot.resolve(current).resolve("SKILL.md");
            if (!Files.exists(skillFile)) {
                return current;
            }
            String content = Files.readString(skillFile);
            if (content.contains("source-candidate-id: \"" + candidateId + "\"")
                    || content.contains("source-candidate-id: " + candidateId)) {
                return current;
            }
            current = truncateName(baseName + "-" + suffix, MAX_SKILL_NAME);
            suffix++;
        }
    }

    private static String renderSkillMarkdown(String skillName, LearningCandidate candidate) {
        var tools = deriveAllowedTools(candidate.toolTag());
        String allowedTools = tools.isEmpty()
                ? "[]"
                : "[" + String.join(", ", tools.stream().map(SkillDraftGenerator::quoted).toList()) + "]";

        String description = truncate(candidate.content(), 120);
        String context = "INLINE";
        String body = """
                # Draft Skill

                This draft was generated from a promoted learning candidate.

                ## Strategy
                - %s

                ## Source
                - candidate-id: `%s`
                - category: `%s`
                - kind: `%s`
                - tool-tag: `%s`
                """.formatted(
                candidate.content(),
                candidate.id(),
                candidate.category().name(),
                candidate.kind().name(),
                candidate.toolTag() == null ? "general" : candidate.toolTag()
        );

        return """
                ---
                name: %s
                description: %s
                context: %s
                allowed-tools: %s
                max-turns: %d
                disable-model-invocation: true
                source-candidate-id: %s
                ---

                %s
                """.formatted(
                quoted(skillName),
                quoted(description),
                quoted(context),
                allowedTools,
                DEFAULT_MAX_TURNS,
                quoted(candidate.id()),
                body
        );
    }

    private static List<String> deriveAllowedTools(String toolTag) {
        if (toolTag == null || toolTag.isBlank() || "general".equalsIgnoreCase(toolTag)) {
            return List.of();
        }
        return List.of(toolTag);
    }

    private static String baseSkillName(LearningCandidate candidate) {
        String raw = candidate.content() == null ? "" : candidate.content().toLowerCase(Locale.ROOT);
        raw = raw.replaceAll("[^a-z0-9]+", "-");
        raw = raw.replaceAll("^-+|-+$", "");
        if (raw.isBlank()) {
            raw = "generated-skill";
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
        return "\"" + (s == null ? "" : s.replace("\"", "\\\"")) + "\"";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }

    public record GenerationSummary(
            int processedPromotedCandidates,
            int createdDrafts,
            int updatedDrafts,
            List<String> draftPaths,
            Path auditFile
    ) {}
}
