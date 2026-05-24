package dev.aceclaw.daemon.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.learning.LearningExplanationRecorder;
import dev.aceclaw.learning.skill.SkillDraftEvent;
import dev.aceclaw.learning.skill.SkillDraftEventFeed;
import dev.aceclaw.learning.skill.SkillDraftGenerator;
import dev.aceclaw.learning.validation.AutoReleaseController;
import dev.aceclaw.learning.validation.LearningValidationRecorder;
import dev.aceclaw.learning.validation.ValidationGateEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Fans skill-draft lifecycle events (created / validated / released) to three
 * downstream sinks: the durable learning recorders + the in-memory
 * {@link SkillDraftEventFeed} that the dashboard subscribes to. Also serialises
 * validation and release summaries to JSON for the daemon's RPC responses.
 *
 * <p>Lifted out of {@code AceClawDaemon} (batch A of the daemon decomp). All
 * publish methods are best-effort: a null/empty summary is a silent no-op so
 * callers don't need to guard at the call site. Field-mapping logic lives
 * here in private helpers so the daemon's wireup paths don't have to know
 * about frontmatter parsing or path-to-skill-name conventions.
 */
public final class SkillDraftEventPublisher {

    private final ObjectMapper objectMapper;
    private final LearningExplanationRecorder explanationRecorder;
    private final LearningValidationRecorder validationRecorder;
    private final SkillDraftEventFeed eventFeed;

    public SkillDraftEventPublisher(
            ObjectMapper objectMapper,
            LearningExplanationRecorder explanationRecorder,
            LearningValidationRecorder validationRecorder,
            SkillDraftEventFeed eventFeed) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.explanationRecorder = Objects.requireNonNull(explanationRecorder, "explanationRecorder");
        this.validationRecorder = Objects.requireNonNull(validationRecorder, "validationRecorder");
        this.eventFeed = Objects.requireNonNull(eventFeed, "eventFeed");
    }

    /**
     * Fans out {@code draft_created} events for each draft path in {@code summary}.
     * Looks up the source candidate id from the draft's frontmatter (best-effort —
     * a malformed file just yields an empty candidateId).
     *
     * @param summary    generation summary; null or empty -> no-op
     * @param workingDir project root used to resolve relative draft paths
     * @param trigger    free-form label written into both the recorder + the feed
     */
    public void publishCreated(
            SkillDraftGenerator.GenerationSummary summary,
            Path workingDir,
            String trigger) {
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(trigger, "trigger");
        if (summary == null || summary.draftPaths().isEmpty()) {
            return;
        }
        for (String draftPath : summary.draftPaths()) {
            Path draftFile = workingDir.resolve(draftPath).normalize();
            // getFileName() returns null when getParent() is a filesystem root
            // ("/" on Unix, "C:\" on Windows). Treat that as "no parent dir to
            // name the skill after" rather than NPE.
            Path parent = draftFile.getParent();
            Path parentName = parent != null ? parent.getFileName() : null;
            String skillName = parentName != null ? parentName.toString() : "";
            String candidateId = "";
            try {
                candidateId = parseDraftFrontmatter(draftFile).getOrDefault("source-candidate-id", "");
            } catch (Exception ignored) {
            }
            explanationRecorder.recordSkillDraft(
                    workingDir,
                    trigger,
                    skillName,
                    draftPath.replace('\\', '/'),
                    candidateId);
            eventFeed.append(new SkillDraftEvent(
                    Instant.now(),
                    "draft_created",
                    trigger,
                    skillName,
                    draftPath.replace('\\', '/'),
                    candidateId,
                    "",
                    "",
                    false,
                    List.of()
            ));
        }
    }

    /**
     * Fans out {@code validation_changed} events for each changed decision in
     * {@code summary}. Skips unchanged decisions so a re-validation that
     * produces no verdict flips doesn't spam the feed.
     */
    public void publishValidationChanged(
            ValidationGateEngine.ValidationSummary summary,
            Path projectRoot,
            String trigger) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(trigger, "trigger");
        if (summary == null || summary.changedDecisions().isEmpty()) {
            return;
        }
        for (var decision : summary.changedDecisions()) {
            validationRecorder.recordDraftValidation(projectRoot, trigger, decision);
            String skillName = skillNameFromDraftPath(decision.draftPath());
            var reasons = decision.reasons().stream()
                    .map(reason -> reason.code() + ": " + reason.message())
                    .toList();
            eventFeed.append(new SkillDraftEvent(
                    decision.evaluatedAt(),
                    "validation_changed",
                    trigger,
                    skillName,
                    decision.draftPath(),
                    "",
                    decision.verdict().name().toLowerCase(),
                    "",
                    false,
                    reasons
            ));
        }
    }

    /**
     * Fans out {@code release_changed} events for each stage transition in
     * {@code summary}. Joins each event back to its release record (by skill
     * name) so the feed entry carries the draftPath + candidateId + paused
     * flag, which the dashboard needs to render the release row.
     */
    public void publishReleaseChanged(
            AutoReleaseController.EvaluationSummary summary,
            Path projectRoot,
            String trigger) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(trigger, "trigger");
        if (summary == null || summary.events().isEmpty()) {
            return;
        }
        for (var event : summary.events()) {
            validationRecorder.recordReleaseValidation(projectRoot, trigger, event);
            var release = summary.releases().stream()
                    .filter(candidate -> candidate.skillName().equals(event.skillName()))
                    .findFirst()
                    .orElse(null);
            eventFeed.append(new SkillDraftEvent(
                    event.timestamp(),
                    "release_changed",
                    trigger,
                    event.skillName(),
                    release == null ? "" : release.draftPath(),
                    release == null ? "" : release.candidateId(),
                    "",
                    event.toStage().name().toLowerCase(),
                    release != null && release.paused(),
                    List.of(event.reasonCode() + ": " + event.reason())
            ));
        }
    }

    /** Serialises a validation summary into the JSON RPC reply shape. */
    public ObjectNode toValidationJson(
            ValidationGateEngine.ValidationSummary summary, Path workingDir) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(workingDir, "workingDir");
        var node = objectMapper.createObjectNode();
        node.put("totalDrafts", summary.totalDrafts());
        node.put("passCount", summary.passCount());
        node.put("holdCount", summary.holdCount());
        node.put("blockCount", summary.blockCount());
        node.put("auditFile", workingDir.relativize(summary.auditFile()).toString().replace('\\', '/'));
        var decisions = objectMapper.createArrayNode();
        for (var decision : summary.decisions()) {
            var dn = objectMapper.createObjectNode();
            dn.put("draftPath", decision.draftPath());
            dn.put("verdict", decision.verdict().name().toLowerCase());
            dn.put("evaluatedAt", decision.evaluatedAt().toString());
            dn.put("trigger", decision.trigger());
            var reasons = objectMapper.createArrayNode();
            for (var reason : decision.reasons()) {
                var rn = objectMapper.createObjectNode();
                rn.put("gate", reason.gate());
                rn.put("code", reason.code());
                rn.put("outcome", reason.outcome().name().toLowerCase());
                rn.put("message", reason.message());
                reasons.add(rn);
            }
            dn.set("reasons", reasons);
            decisions.add(dn);
        }
        node.set("decisions", decisions);
        return node;
    }

    /** Serialises a release-evaluation summary into the JSON RPC reply shape. */
    public ObjectNode toReleaseJson(AutoReleaseController.EvaluationSummary summary) {
        Objects.requireNonNull(summary, "summary");
        var node = objectMapper.createObjectNode();
        var releases = objectMapper.createArrayNode();
        for (var release : summary.releases()) {
            var rn = objectMapper.createObjectNode();
            rn.put("skillName", release.skillName());
            rn.put("draftPath", release.draftPath());
            rn.put("candidateId", release.candidateId());
            rn.put("stage", release.stage().name().toLowerCase());
            rn.put("paused", release.paused());
            rn.put("updatedAt", release.updatedAt().toString());
            rn.put("lastReasonCode", release.lastReasonCode());
            rn.put("lastReason", release.lastReason());
            releases.add(rn);
        }
        var events = objectMapper.createArrayNode();
        for (var event : summary.events()) {
            var en = objectMapper.createObjectNode();
            en.put("timestamp", event.timestamp().toString());
            en.put("trigger", event.trigger());
            en.put("skillName", event.skillName());
            en.put("fromStage", event.fromStage() == null ? "none" : event.fromStage().name().toLowerCase());
            en.put("toStage", event.toStage().name().toLowerCase());
            en.put("reasonCode", event.reasonCode());
            en.put("reason", event.reason());
            events.add(en);
        }
        node.put("totalReleases", summary.releases().size());
        node.put("eventsCount", summary.events().size());
        node.set("releases", releases);
        node.set("events", events);
        return node;
    }

    // -- Pure helpers (package-private for direct unit testing) ---------------

    /**
     * Convention: a draft lives under {@code <skillName>/draft.md}. Returns the
     * parent directory's name as the skill name; empty for paths that don't fit.
     */
    static String skillNameFromDraftPath(String draftPath) {
        Path p = Path.of(draftPath.replace('\\', '/'));
        if (p.getNameCount() < 2) {
            return "";
        }
        return p.getName(p.getNameCount() - 2).toString();
    }

    /**
     * Parses simple YAML-like frontmatter delimited by lines containing exactly
     * {@code ---}. Only flat scalars are supported (no nesting, no lists).
     * Keys are lowercased. Values are stripped of surrounding quotes.
     * Returns empty map if frontmatter is missing or malformed.
     */
    static Map<String, String> parseDraftFrontmatter(Path draftFile) throws IOException {
        Objects.requireNonNull(draftFile, "draftFile");
        String raw = Files.readString(draftFile);
        String[] lines = raw.split("\n");
        int first = -1;
        int second = -1;
        for (int i = 0; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                if (first < 0) {
                    first = i;
                } else {
                    second = i;
                    break;
                }
            }
        }
        var map = new LinkedHashMap<String, String>();
        if (first < 0 || second <= first) {
            return map;
        }
        for (int i = first + 1; i < second; i++) {
            String line = lines[i];
            int idx = line.indexOf(':');
            if (idx <= 0) continue;
            String key = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(idx + 1).trim();
            map.put(key, stripQuotes(value));
        }
        return map;
    }

    /** Strips one matching pair of surrounding single or double quotes. */
    static String stripQuotes(String value) {
        if (value == null || value.length() < 2) return value == null ? "" : value;
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
