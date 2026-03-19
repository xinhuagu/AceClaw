package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.LearningCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Automation-first release controller for validated skill drafts.
 *
 * <p>Lifecycle: {@code SHADOW -> CANARY -> ACTIVE}.
 * Supports manual emergency overrides: pause, force-promote, force-rollback.
 */
public final class AutoReleaseController {
    private static final Logger log = LoggerFactory.getLogger(AutoReleaseController.class);

    private static final String DRAFTS_DIR = ".aceclaw/skills-drafts";
    private static final String SKILLS_DIR = ".aceclaw/skills";
    private static final String ROLLBACKS_DIR = ".aceclaw/skills-rollbacks";
    private static final String METRICS_DIR = ".aceclaw/metrics/continuous-learning";
    private static final String STATE_FILE = "skill-release-state.json";
    private static final String AUDIT_FILE = "skill-release-audit.jsonl";

    private final Clock clock;
    private final ObjectMapper mapper;
    private final Config config;
    private final ValidationGateEngine validationGateEngine;
    private final ReentrantLock stateLock = new ReentrantLock();

    public AutoReleaseController(Config config, ValidationGateEngine validationGateEngine) {
        this(Clock.systemUTC(), config, validationGateEngine);
    }

    AutoReleaseController(Clock clock, Config config, ValidationGateEngine validationGateEngine) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
        this.validationGateEngine = Objects.requireNonNull(validationGateEngine, "validationGateEngine");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public EvaluationSummary evaluateAll(Path projectRoot, CandidateStore candidateStore, String trigger) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(candidateStore, "candidateStore");
        stateLock.lock();
        try {
            String normalizedTrigger = normalizeTrigger(trigger);

            var validations = validationGateEngine.validateAll(projectRoot, "release-eval:" + normalizedTrigger);
            var releaseState = loadState(projectRoot);
            var decisionByPath = new LinkedHashMap<String, ValidationGateEngine.DraftDecision>();
            for (var decision : validations.decisions()) {
                decisionByPath.put(decision.draftPath(), decision);
            }

            var transitionEvents = new ArrayList<ReleaseEvent>();
            var releasesByName = indexBySkillName(releaseState.releases());

        for (var entry : decisionByPath.entrySet()) {
            String draftPath = entry.getKey();
            var decision = entry.getValue();
            Path draftFile = projectRoot.resolve(draftPath);
            String skillName = skillNameFromDraftPath(draftPath);
            var frontmatter = parseFrontmatter(draftFile);
            String candidateId = frontmatter.getOrDefault("source-candidate-id", "");
            var release = releasesByName.computeIfAbsent(skillName, _ -> new SkillRelease(
                    skillName, draftPath, candidateId, Stage.SHADOW,
                    false, 0, 0, 0, 0,
                    Instant.now(clock), Instant.now(clock), "INIT", "initialized"));

            if (release.paused()) {
                continue;
            }
            if (decision.verdict() != ValidationGateEngine.Verdict.PASS) {
                if (release.stage() == Stage.ACTIVE || release.stage() == Stage.CANARY) {
                    var rolled = rollbackToShadow(projectRoot, release, "AUTO_ROLLBACK_VALIDATION_FAIL",
                            "validation verdict=" + decision.verdict().name().toLowerCase(Locale.ROOT),
                            normalizedTrigger);
                    releasesByName.put(skillName, rolled.release());
                    transitionEvents.add(rolled.event());
                }
                continue;
            }

            var candidateOpt = candidateStore.byId(candidateId);
            if (candidateOpt.isEmpty()) {
                continue;
            }
            var candidate = candidateOpt.get();
            if (!candidateGatesPass(candidate)) {
                continue;
            }

            boolean promotedToCanaryThisRun = false;
            if (release.stage() == Stage.SHADOW) {
                var canary = transition(projectRoot, release, Stage.CANARY,
                        "AUTO_PROMOTE_CANARY", "validated and candidate-gates-pass", normalizedTrigger);
                releasesByName.put(skillName, canary.release());
                transitionEvents.add(canary.event());
                release = canary.release();
                promotedToCanaryThisRun = true;
            }

            var metrics = collectHealthMetrics(candidate);
            if ((release.stage() == Stage.CANARY || release.stage() == Stage.ACTIVE) && shouldRollback(metrics)) {
                var rolled = rollbackToShadow(projectRoot, release, "AUTO_ROLLBACK_GUARDRAIL_BREACH",
                        guardrailMessage(metrics), normalizedTrigger);
                releasesByName.put(skillName, rolled.release());
                transitionEvents.add(rolled.event());
                continue;
            }

            if (release.stage() == Stage.CANARY && !promotedToCanaryThisRun && canPromoteToActive(metrics, release)) {
                var active = transition(projectRoot, release, Stage.ACTIVE,
                        "AUTO_PROMOTE_ACTIVE", "canary guardrails pass", normalizedTrigger);
                releasesByName.put(skillName, active.release());
                transitionEvents.add(active.event());
            }
        }

            var nextState = new ReleaseState(List.copyOf(releasesByName.values()));
            persistState(projectRoot, nextState);
            appendAudit(projectRoot, transitionEvents);
            return new EvaluationSummary(nextState.releases(), transitionEvents);
        } finally {
            stateLock.unlock();
        }
    }

    public EvaluationSummary pause(Path projectRoot, String skillName, String reason, String trigger) throws IOException {
        stateLock.lock();
        try {
            return mutateManual(projectRoot, skillName, "MANUAL_PAUSE", reason, trigger, release ->
                    release.withPaused(true, Instant.now(clock), "MANUAL_PAUSE", normalizeReason(reason)));
        } finally {
            stateLock.unlock();
        }
    }

    public EvaluationSummary forceRollback(Path projectRoot, String skillName, String reason, String trigger) throws IOException {
        stateLock.lock();
        try {
            Objects.requireNonNull(skillName, "skillName");
            var state = loadState(projectRoot);
            var releases = new ArrayList<SkillRelease>(state.releases());
            for (int i = 0; i < releases.size(); i++) {
                var release = releases.get(i);
                if (!release.skillName().equals(skillName)) continue;
                var rolled = rollbackToShadow(projectRoot, release, "MANUAL_FORCE_ROLLBACK", reason, trigger);
                releases.set(i, rolled.release());
                persistState(projectRoot, new ReleaseState(List.copyOf(releases)));
                appendAudit(projectRoot, List.of(rolled.event()));
                return new EvaluationSummary(List.copyOf(releases), List.of(rolled.event()));
            }
            return new EvaluationSummary(state.releases(), List.of());
        } finally {
            stateLock.unlock();
        }
    }

    public EvaluationSummary forcePromote(Path projectRoot, String skillName, Stage targetStage,
                                          String reason, String trigger) throws IOException {
        stateLock.lock();
        try {
            Objects.requireNonNull(skillName, "skillName");
            Objects.requireNonNull(targetStage, "targetStage");
            if (targetStage == Stage.SHADOW) {
                throw new IllegalArgumentException("forcePromote targetStage must be CANARY or ACTIVE");
            }
            var state = loadState(projectRoot);
            var releases = new ArrayList<SkillRelease>(state.releases());
            for (int i = 0; i < releases.size(); i++) {
                var release = releases.get(i);
                if (!release.skillName().equals(skillName)) continue;
                var transitioned = transition(projectRoot, release.withPaused(false, Instant.now(clock),
                                "MANUAL_UNPAUSE", "manual force promote"), targetStage,
                        "MANUAL_FORCE_PROMOTE", reason, trigger);
                releases.set(i, transitioned.release());
                persistState(projectRoot, new ReleaseState(List.copyOf(releases)));
                appendAudit(projectRoot, List.of(transitioned.event()));
                return new EvaluationSummary(List.copyOf(releases), List.of(transitioned.event()));
            }
            return new EvaluationSummary(state.releases(), List.of());
        } finally {
            stateLock.unlock();
        }
    }

    private EvaluationSummary mutateManual(Path projectRoot, String skillName, String reasonCode, String reason,
                                           String trigger, ReleaseMutator mutator) throws IOException {
        Objects.requireNonNull(skillName, "skillName");
        var state = loadState(projectRoot);
        var releases = new ArrayList<SkillRelease>(state.releases());
        for (int i = 0; i < releases.size(); i++) {
            var release = releases.get(i);
            if (!release.skillName().equals(skillName)) continue;
            var updated = mutator.apply(release);
            releases.set(i, updated);
            var event = new ReleaseEvent(
                    Instant.now(clock), normalizeTrigger(trigger), release.skillName(),
                    release.stage(), updated.stage(), reasonCode, normalizeReason(reason));
            persistState(projectRoot, new ReleaseState(List.copyOf(releases)));
            appendAudit(projectRoot, List.of(event));
            return new EvaluationSummary(List.copyOf(releases), List.of(event));
        }
        return new EvaluationSummary(state.releases(), List.of());
    }

    private TransitionResult transition(Path projectRoot, SkillRelease current, Stage toStage,
                                        String reasonCode, String reason, String trigger) throws IOException {
        publish(projectRoot, current, toStage);
        var updated = current.withStage(toStage, Instant.now(clock), reasonCode, normalizeReason(reason));
        var event = new ReleaseEvent(
                Instant.now(clock), normalizeTrigger(trigger), current.skillName(),
                current.stage(), toStage, reasonCode, normalizeReason(reason));
        return new TransitionResult(updated, event);
    }

    private TransitionResult rollbackToShadow(Path projectRoot, SkillRelease current, String reasonCode,
                                              String reason, String trigger) throws IOException {
        unpublish(projectRoot, current.skillName());
        var updated = current.withStage(Stage.SHADOW, Instant.now(clock), reasonCode, normalizeReason(reason));
        var event = new ReleaseEvent(
                Instant.now(clock), normalizeTrigger(trigger), current.skillName(),
                current.stage(), Stage.SHADOW, reasonCode, normalizeReason(reason));
        return new TransitionResult(updated, event);
    }

    private void publish(Path projectRoot, SkillRelease release, Stage toStage) throws IOException {
        Path draftFile = projectRoot.resolve(release.draftPath());
        if (!Files.isRegularFile(draftFile)) return;
        Path skillDir = projectRoot.resolve(SKILLS_DIR).resolve(release.skillName());
        Files.createDirectories(skillDir);
        String content = Files.readString(draftFile);
        String patched = patchDisableModelInvocation(content, toStage != Stage.ACTIVE);
        Files.writeString(skillDir.resolve("SKILL.md"), patched, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void unpublish(Path projectRoot, String skillName) throws IOException {
        Path skillDir = projectRoot.resolve(SKILLS_DIR).resolve(skillName);
        if (!Files.isDirectory(skillDir)) return;
        Path backupRoot = projectRoot.resolve(ROLLBACKS_DIR);
        Files.createDirectories(backupRoot);
        String suffix = Instant.now(clock).toString().replace(':', '-');
        Path backup = backupRoot.resolve(skillName + "-" + suffix);
        Files.move(skillDir, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String patchDisableModelInvocation(String content, boolean disabled) {
        var lines = content.split("\n", -1);
        boolean replaced = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("disable-model-invocation:")) {
                lines[i] = "disable-model-invocation: " + (disabled ? "true" : "false");
                replaced = true;
                break;
            }
            if (i > 0 && t.equals("---")) break;
        }
        if (!replaced) {
            var sb = new StringBuilder();
            int delimCount = 0;
            for (String line : lines) {
                sb.append(line).append('\n');
                if (line.trim().equals("---")) {
                    delimCount++;
                    if (delimCount == 1) {
                        sb.append("disable-model-invocation: ").append(disabled ? "true" : "false").append('\n');
                    }
                }
            }
            return sb.toString();
        }
        return String.join("\n", lines);
    }

    private boolean candidateGatesPass(LearningCandidate candidate) {
        return candidate.score() >= config.minCandidateScore()
                && candidate.evidenceCount() >= config.minEvidenceCount();
    }

    private boolean canPromoteToActive(HealthMetrics metrics, SkillRelease release) {
        // Minimum dwell time at CANARY stage before promotion
        if (config.canaryDwellHours() > 0) {
            Duration dwellTime = Duration.between(release.updatedAt(), Instant.now(clock));
            if (dwellTime.toHours() < config.canaryDwellHours()) {
                log.debug("Skill {} dwell time {}h < required {}h, deferring promotion",
                        release.skillName(), dwellTime.toHours(), config.canaryDwellHours());
                return false;
            }
        }
        boolean passes = metrics.attempts() >= config.canaryMinAttempts()
                && metrics.failureRate() <= config.canaryMaxFailureRate()
                && metrics.timeoutRate() <= config.canaryMaxTimeoutRate()
                && metrics.permissionRate() <= config.canaryMaxPermissionRate();

        // Approach warning: log when metrics reach 80% of guardrail threshold
        if (!passes) {
            logApproachWarning(release.skillName(), metrics);
        }
        return passes;
    }

    private boolean shouldRollback(HealthMetrics metrics) {
        return metrics.attempts() > 0 && (
                metrics.failureRate() > config.rollbackMaxFailureRate()
                        || metrics.timeoutRate() > config.rollbackMaxTimeoutRate()
                        || metrics.permissionRate() > config.rollbackMaxPermissionRate()
        );
    }

    private void logApproachWarning(String skillName, HealthMetrics metrics) {
        if (config.canaryMaxFailureRate() > 0
                && metrics.failureRate() >= config.canaryMaxFailureRate() * 0.8) {
            log.warn("Skill {} failure rate {} approaching canary threshold {}",
                    skillName, metrics.failureRate(), config.canaryMaxFailureRate());
        }
        if (config.canaryMaxTimeoutRate() > 0
                && metrics.timeoutRate() >= config.canaryMaxTimeoutRate() * 0.8) {
            log.warn("Skill {} timeout rate {} approaching canary threshold {}",
                    skillName, metrics.timeoutRate(), config.canaryMaxTimeoutRate());
        }
    }

    private String guardrailMessage(HealthMetrics metrics) {
        return "failureRate=%.3f, timeoutRate=%.3f, permissionRate=%.3f, attempts=%d"
                .formatted(metrics.failureRate(), metrics.timeoutRate(), metrics.permissionRate(), metrics.attempts());
    }

    private HealthMetrics collectHealthMetrics(LearningCandidate candidate) {
        Instant cutoff = Instant.now(clock).minus(config.healthLookback());
        int success = 0;
        int failure = 0;
        int timeout = 0;
        int permission = 0;
        for (var e : candidate.evidence()) {
            if (e.observedAt().isBefore(cutoff)) continue;
            success += e.successDelta();
            failure += e.failureDelta();
            String note = e.note() == null ? "" : e.note().toLowerCase(Locale.ROOT);
            if (note.contains("timeout")) timeout++;
            if (note.contains("permission")) permission++;
        }
        int attempts = success + failure;
        double failureRate = attempts == 0 ? 0.0 : (double) failure / attempts;
        double timeoutRate = attempts == 0 ? 0.0 : (double) timeout / attempts;
        double permissionRate = attempts == 0 ? 0.0 : (double) permission / attempts;
        return new HealthMetrics(attempts, failureRate, timeoutRate, permissionRate);
    }

    private ReleaseState loadState(Path projectRoot) {
        Path statePath = projectRoot.resolve(METRICS_DIR).resolve(STATE_FILE);
        if (!Files.isRegularFile(statePath)) {
            return new ReleaseState(List.of());
        }
        try {
            var state = mapper.readValue(statePath.toFile(), ReleaseState.class);
            if (state == null || state.releases() == null) return new ReleaseState(List.of());
            return new ReleaseState(state.releases().stream()
                    .sorted(Comparator.comparing(SkillRelease::skillName))
                    .toList());
        } catch (Exception e) {
            log.warn("Failed to load release state from {}: {}", statePath, e.getMessage());
            return new ReleaseState(List.of());
        }
    }

    private void persistState(Path projectRoot, ReleaseState state) throws IOException {
        Path statePath = projectRoot.resolve(METRICS_DIR).resolve(STATE_FILE);
        Files.createDirectories(statePath.getParent());
        Path tmp = statePath.resolveSibling(statePath.getFileName() + ".tmp");
        Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void appendAudit(Path projectRoot, List<ReleaseEvent> events) throws IOException {
        if (events.isEmpty()) return;
        Path audit = projectRoot.resolve(METRICS_DIR).resolve(AUDIT_FILE);
        Files.createDirectories(audit.getParent());
        for (var event : events) {
            Files.writeString(audit, mapper.writeValueAsString(event) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static String skillNameFromDraftPath(String draftPath) {
        Path p = Path.of(draftPath.replace('\\', '/'));
        if (p.getNameCount() < 3) {
            return p.getParent() != null ? p.getParent().getFileName().toString() : "unknown-skill";
        }
        return p.getName(p.getNameCount() - 2).toString();
    }

    private static Map<String, String> parseFrontmatter(Path draftFile) throws IOException {
        String raw = Files.readString(draftFile);
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
        var map = new LinkedHashMap<String, String>();
        if (first < 0 || second <= first) return map;
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

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) return value == null ? "" : value;
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String normalizeTrigger(String trigger) {
        if (trigger == null || trigger.isBlank()) return "manual";
        return trigger.trim();
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return "n/a";
        return reason.trim();
    }

    private static Map<String, SkillRelease> indexBySkillName(List<SkillRelease> releases) {
        var map = new LinkedHashMap<String, SkillRelease>();
        for (var r : releases) map.put(r.skillName(), r);
        return map;
    }

    private interface ReleaseMutator {
        SkillRelease apply(SkillRelease release);
    }

    private record HealthMetrics(int attempts, double failureRate, double timeoutRate, double permissionRate) {}
    private record TransitionResult(SkillRelease release, ReleaseEvent event) {}

    public enum Stage {
        SHADOW, CANARY, ACTIVE
    }

    public record Config(
            double minCandidateScore,
            int minEvidenceCount,
            int canaryMinAttempts,
            double canaryMaxFailureRate,
            double canaryMaxTimeoutRate,
            double canaryMaxPermissionRate,
            double rollbackMaxFailureRate,
            double rollbackMaxTimeoutRate,
            double rollbackMaxPermissionRate,
            Duration healthLookback,
            int canaryDwellHours
    ) {
        public Config {
            healthLookback = healthLookback != null ? healthLookback : Duration.ofDays(7);
            if (canaryDwellHours < 0) canaryDwellHours = 0;
        }

        /** Backward-compatible constructor without canaryDwellHours. */
        public Config(double minCandidateScore, int minEvidenceCount,
                      int canaryMinAttempts, double canaryMaxFailureRate,
                      double canaryMaxTimeoutRate, double canaryMaxPermissionRate,
                      double rollbackMaxFailureRate, double rollbackMaxTimeoutRate,
                      double rollbackMaxPermissionRate, Duration healthLookback) {
            this(minCandidateScore, minEvidenceCount, canaryMinAttempts,
                    canaryMaxFailureRate, canaryMaxTimeoutRate, canaryMaxPermissionRate,
                    rollbackMaxFailureRate, rollbackMaxTimeoutRate, rollbackMaxPermissionRate,
                    healthLookback, 24);
        }

        public static Config defaults() {
            return new Config(0.80, 3, 20, 0.10, 0.20, 0.20, 0.20, 0.20, 0.20, Duration.ofDays(7), 24);
        }
    }

    public record SkillRelease(
            String skillName,
            String draftPath,
            String candidateId,
            Stage stage,
            boolean paused,
            int canaryAttempts,
            int canaryFailures,
            int canaryTimeouts,
            int canaryPermissionBlocks,
            Instant createdAt,
            Instant updatedAt,
            String lastReasonCode,
            String lastReason
    ) {
        public SkillRelease {
            skillName = skillName != null ? skillName : "";
            draftPath = draftPath != null ? draftPath : "";
            candidateId = candidateId != null ? candidateId : "";
            stage = stage != null ? stage : Stage.SHADOW;
            createdAt = createdAt != null ? createdAt : Instant.EPOCH;
            updatedAt = updatedAt != null ? updatedAt : createdAt;
            lastReasonCode = lastReasonCode != null ? lastReasonCode : "INIT";
            lastReason = lastReason != null ? lastReason : "";
        }

        SkillRelease withStage(Stage newStage, Instant at, String reasonCode, String reason) {
            return new SkillRelease(
                    skillName, draftPath, candidateId, newStage, paused,
                    canaryAttempts, canaryFailures, canaryTimeouts, canaryPermissionBlocks,
                    createdAt, at, reasonCode, reason);
        }

        SkillRelease withPaused(boolean nextPaused, Instant at, String reasonCode, String reason) {
            return new SkillRelease(
                    skillName, draftPath, candidateId, stage, nextPaused,
                    canaryAttempts, canaryFailures, canaryTimeouts, canaryPermissionBlocks,
                    createdAt, at, reasonCode, reason);
        }
    }

    public record ReleaseState(List<SkillRelease> releases) {
        public ReleaseState {
            releases = releases != null ? List.copyOf(releases) : List.of();
        }
    }

    public record ReleaseEvent(
            Instant timestamp,
            String trigger,
            String skillName,
            Stage fromStage,
            Stage toStage,
            String reasonCode,
            String reason
    ) {}

    public record EvaluationSummary(
            List<SkillRelease> releases,
            List<ReleaseEvent> events
    ) {
        public EvaluationSummary {
            releases = releases != null ? List.copyOf(releases) : List.of();
            events = events != null ? List.copyOf(events) : List.of();
        }
    }
}
