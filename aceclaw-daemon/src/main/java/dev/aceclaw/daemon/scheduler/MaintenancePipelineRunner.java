package dev.aceclaw.daemon.scheduler;

import dev.aceclaw.daemon.skill.SkillDraftEventPublisher;
import dev.aceclaw.learning.LearningExplanation;
import dev.aceclaw.learning.LearningExplanationRecorder;
import dev.aceclaw.learning.maintenance.LearningMaintenanceCandidateBridge;
import dev.aceclaw.learning.maintenance.LearningMaintenanceRun;
import dev.aceclaw.learning.maintenance.LearningMaintenanceRunStore;
import dev.aceclaw.learning.skill.SkillDraftGenerator;
import dev.aceclaw.learning.validation.AutoReleaseController;
import dev.aceclaw.learning.validation.ValidationGateEngine;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.CorrectionRulePromoter;
import dev.aceclaw.memory.CrossSessionPatternMiner;
import dev.aceclaw.memory.DailyJournal;
import dev.aceclaw.daemon.HistoricalIndexRebuilder;
import dev.aceclaw.memory.HistoricalLogIndex;
import dev.aceclaw.memory.MemoryConsolidator;
import dev.aceclaw.memory.TrendDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Per-trigger learning-maintenance pipeline. Runs the full sequence of
 * memory consolidation, correction-rule promotion, historical-index rebuild,
 * cross-session pattern mining, trend detection, and the candidate-bridge
 * promotion path. Lifted out of {@code AceClawDaemon} (batch B of the
 * daemon decomposition) where it lived as a 150-LoC method + 32-LoC helper
 * + 85-LoC inner builder all chained together.
 *
 * <p>All dependencies are captured at construction time via {@link Deps}; the
 * per-trigger inputs (trigger label, workspace hash, working dir) come in via
 * {@link #run}. Every sub-stage is wrapped in its own try/catch so a single
 * stage failure doesn't abort the rest of the pipeline — these tasks all
 * run on the background maintenance scheduler and the only consequence of a
 * failure is one stage's results being missing from the run summary.
 *
 * <p>The pipeline preserves the previous nullability conventions exactly:
 * {@code historicalLogIndex == null} disables both the rebuilder and the
 * cross-session miner branches; {@code candidateStore == null} disables the
 * bridge path; {@code journal == null} disables the human-readable journal
 * line.
 */
public final class MaintenancePipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(MaintenancePipelineRunner.class);

    private final Deps deps;

    public MaintenancePipelineRunner(Deps deps) {
        this.deps = Objects.requireNonNull(deps, "deps");
    }

    /**
     * Runs the full maintenance pipeline for one trigger fire. Safe to call
     * from any thread — internal state is per-run and the only shared
     * mutable resource is {@link Deps#draftPipelineLock} which serialises
     * the bridge → draft path.
     *
     * @param trigger       free-form label (e.g. "scheduled", "session-end")
     * @param workspaceHash workspace identity used to scope historical reads
     * @param workingDir    workspace root for IO + audit attribution
     */
    public void run(String trigger, String workspaceHash, Path workingDir) {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        Objects.requireNonNull(workingDir, "workingDir");

        var summary = new LearningMaintenanceRunBuilder(trigger, workspaceHash, workingDir);

        try {
            var result = MemoryConsolidator.consolidate(deps.memoryStore, workingDir, deps.archiveDir);
            summary.consolidation(result);
            if (result.hasChanges() && deps.journal != null) {
                deps.journal.append("Memory consolidated (" + trigger + "): "
                        + result.deduped() + " deduped, "
                        + result.merged() + " merged, "
                        + result.pruned() + " pruned");
            }
        } catch (Exception e) {
            log.warn("Memory consolidation failed (trigger={}): {}", trigger, e.getMessage());
        }

        // Correction -> Rule auto-promotion: detect repeated corrections and promote to ACECLAW.md rules
        try {
            var aceClawMdPath = workingDir.resolve(".aceclaw").resolve("ACECLAW.md");
            var existingFingerprints = CorrectionRulePromoter.loadExistingFingerprints(aceClawMdPath);
            var allEntries = deps.memoryStore.all();
            var promotionResult = CorrectionRulePromoter.detectRepeatedCorrections(
                    allEntries, existingFingerprints);
            if (promotionResult.hasPromotions()) {
                int written = CorrectionRulePromoter.appendRules(aceClawMdPath, promotionResult.rules());
                if (deps.journal != null) {
                    deps.journal.append("Correction rule promotion (" + trigger + "): "
                            + written + " rules promoted from "
                            + promotionResult.scannedCorrections() + " corrections");
                }
                log.info("Auto-promoted {} correction rules to ACECLAW.md (trigger={})",
                        written, trigger);
            }
        } catch (Exception e) {
            log.warn("Correction rule promotion failed (trigger={}): {}", trigger, e.getMessage());
        }

        if (deps.historicalIndexRebuilder != null) {
            try {
                var rebuild = deps.historicalIndexRebuilder.rebuildWorkspaceIfStale(workspaceHash);
                if (rebuild.rebuilt() && deps.journal != null) {
                    deps.journal.append("Historical index rebuilt (" + trigger + "): "
                            + rebuild.rebuiltSessions() + " sessions re-indexed");
                }
            } catch (Exception e) {
                log.warn("Historical index rebuild failed (trigger={}): {}", trigger, e.getMessage());
            }
        }

        CrossSessionPatternMiner.MiningResult miningResult = null;
        if (deps.crossSessionPatternMiner != null && deps.historicalLogIndex != null) {
            try {
                miningResult = deps.crossSessionPatternMiner.mine(
                        deps.historicalLogIndex, deps.memoryStore, workspaceHash, workingDir);
                summary.mining(miningResult);
                if ((!miningResult.frequentErrorChains().isEmpty()
                        || !miningResult.stableWorkflows().isEmpty()
                        || !miningResult.convergingStrategies().isEmpty()
                        || !miningResult.degradationSignals().isEmpty())
                        && deps.journal != null) {
                    deps.journal.append("Cross-session miner (" + trigger + "): "
                            + miningResult.frequentErrorChains().size() + " error chains, "
                            + miningResult.stableWorkflows().size() + " stable workflows, "
                            + miningResult.convergingStrategies().size() + " converging strategies, "
                            + miningResult.degradationSignals().size() + " degradation signals");
                }
            } catch (Exception e) {
                log.warn("Cross-session pattern mining failed (trigger={}): {}", trigger, e.getMessage());
            }
        }

        List<TrendDetector.Trend> trends = List.of();
        if (deps.trendDetector != null && deps.historicalLogIndex != null) {
            try {
                trends = deps.trendDetector.detect(
                        deps.historicalLogIndex, deps.memoryStore, workspaceHash, workingDir);
                summary.trends(trends);
                if (!trends.isEmpty() && deps.journal != null) {
                    deps.journal.append("Trend detector (" + trigger + "): " + trends.size()
                            + " significant trends. " + summarizeTrends(trends));
                }
            } catch (Exception e) {
                log.warn("Trend detection failed (trigger={}): {}", trigger, e.getMessage());
            }
        }

        if (deps.maintenanceCandidateBridge != null && deps.candidateStore != null
                && (miningResult != null || !trends.isEmpty())) {
            try {
                var bridgeResult = deps.maintenanceCandidateBridge.bridge(
                        trigger,
                        miningResult != null ? miningResult
                                : new CrossSessionPatternMiner.MiningResult(List.of(), List.of(), List.of(), List.of()),
                        trends);
                summary.bridge(bridgeResult);
                if (bridgeResult.upserts() > 0 && deps.journal != null) {
                    deps.journal.append("Maintenance candidate bridge (" + trigger + "): "
                            + bridgeResult.upserts() + " observations, "
                            + bridgeResult.transitions() + " transitions, "
                            + bridgeResult.promoted() + " promoted");
                }
                for (var candidate : bridgeResult.observedCandidates()) {
                    deps.explanationRecorder.recordCandidateObservation(
                            workingDir,
                            "",
                            "maintenance-" + trigger,
                            candidate,
                            candidate.content(),
                            List.of(new LearningExplanation.EvidenceRef(
                                    "maintenance-trigger",
                                    trigger,
                                    candidate.toolTag())));
                }
                for (var transition : bridgeResult.stateTransitions()) {
                    deps.explanationRecorder.recordCandidateTransition(
                            workingDir,
                            "",
                            "maintenance-" + trigger,
                            transition);
                }
                if (bridgeResult.promoted() > 0) {
                    triggerMaintenanceDraftLifecycle(
                            "maintenance-" + trigger,
                            workingDir);
                }
            } catch (Exception e) {
                log.warn("Maintenance candidate bridge failed (trigger={}): {}", trigger, e.getMessage());
            }
        }
        try {
            deps.runStore.append(workingDir, summary.build());
        } catch (Exception e) {
            log.debug("Failed to persist maintenance summary (trigger={}): {}", trigger, e.getMessage());
        }
    }

    private void triggerMaintenanceDraftLifecycle(String trigger, Path workingDir) {
        if (deps.candidateStore == null || deps.draftPipelineLock == null) {
            return;
        }
        deps.draftPipelineLock.lock();
        try {
            try {
                var generator = new SkillDraftGenerator();
                var summary = generator.generateFromPromoted(deps.candidateStore, workingDir);
                deps.skillDraftEventPublisher.publishCreated(summary, workingDir, trigger);
                if (deps.validationGateEngine != null && summary.createdDrafts() > 0) {
                    var validation = deps.validationGateEngine.validateAll(workingDir, trigger);
                    deps.skillDraftEventPublisher.publishValidationChanged(validation, workingDir, trigger);
                    if (deps.autoReleaseController != null) {
                        var release = deps.autoReleaseController.evaluateAll(workingDir, deps.candidateStore, trigger);
                        deps.skillDraftEventPublisher.publishReleaseChanged(release, workingDir, trigger);
                    }
                }
            } catch (Exception e) {
                log.warn("Maintenance draft lifecycle failed (trigger={}): {}", trigger, e.getMessage());
            }
        } finally {
            deps.draftPipelineLock.unlock();
        }
    }

    /** Joins the first 3 trend descriptions with " | " for the journal line. */
    static String summarizeTrends(List<TrendDetector.Trend> trends) {
        return trends.stream()
                .limit(3)
                .map(TrendDetector.Trend::description)
                .collect(Collectors.joining(" | "));
    }

    /**
     * Bundle of every collaborator the pipeline needs — captured once at
     * daemon wireup time so {@link #run} can stay narrow (trigger +
     * workspace identity only). Several fields are intentionally nullable;
     * see the field-level docs below for the off-switch each one represents.
     *
     * <p>All fields use record-style getters via the generated accessors;
     * the inline package-private fields exist only because the runner
     * reads them via {@code deps.xxx} which is terser than {@code deps.xxx()}.
     */
    public record Deps(
            /* required */
            AutoMemoryStore memoryStore,
            /** {@code null} -> consolidation runs without an archive sink. */
            Path archiveDir,
            /** {@code null} -> human-readable journal lines are skipped. */
            DailyJournal journal,
            /** {@code null} -> historical-index rebuild stage is skipped. */
            HistoricalIndexRebuilder historicalIndexRebuilder,
            /** {@code null} -> cross-session pattern mining stage is skipped (also requires {@link #historicalLogIndex()}). */
            CrossSessionPatternMiner crossSessionPatternMiner,
            /** {@code null} -> trend detection stage is skipped (also requires {@link #historicalLogIndex()}). */
            TrendDetector trendDetector,
            /** {@code null} -> candidate-bridge stage (and the draft lifecycle it triggers) is skipped. */
            LearningMaintenanceCandidateBridge maintenanceCandidateBridge,
            /** {@code null} -> candidate-bridge + draft lifecycle are skipped. */
            CandidateStore candidateStore,
            /** {@code null} -> validation step in the draft lifecycle is skipped. */
            ValidationGateEngine validationGateEngine,
            /** {@code null} -> release-evaluation step in the draft lifecycle is skipped. */
            AutoReleaseController autoReleaseController,
            /** Serialises the bridge -> draft path against concurrent RPC writers. Never null. */
            ReentrantLock draftPipelineLock,
            LearningExplanationRecorder explanationRecorder,
            LearningMaintenanceRunStore runStore,
            /** {@code null} -> both miner and trend-detector stages are skipped (their other deps are gated together). */
            HistoricalLogIndex historicalLogIndex,
            SkillDraftEventPublisher skillDraftEventPublisher) {

        public Deps {
            Objects.requireNonNull(memoryStore, "memoryStore");
            Objects.requireNonNull(draftPipelineLock, "draftPipelineLock");
            Objects.requireNonNull(explanationRecorder, "explanationRecorder");
            Objects.requireNonNull(runStore, "runStore");
            Objects.requireNonNull(skillDraftEventPublisher, "skillDraftEventPublisher");
        }
    }

    /**
     * Mutable accumulator for the run summary. Each stage of the pipeline
     * calls a single setter with its result (silently ignoring null so
     * failed stages don't blow up the summary). {@link #build()} freezes
     * into the immutable {@link LearningMaintenanceRun} record persisted
     * by the run store.
     */
    private static final class LearningMaintenanceRunBuilder {
        private final String trigger;
        private final String workspaceHash;
        private final Path workingDir;
        private int deduped;
        private int merged;
        private int pruned;
        private int errorChains;
        private int stableWorkflows;
        private int convergingStrategies;
        private int degradationSignals;
        private int trends;
        private int candidateObservations;
        private int candidateTransitions;
        private int candidatePromoted;

        private LearningMaintenanceRunBuilder(String trigger, String workspaceHash, Path workingDir) {
            this.trigger = trigger;
            this.workspaceHash = workspaceHash;
            this.workingDir = workingDir;
        }

        void consolidation(MemoryConsolidator.ConsolidationResult result) {
            if (result == null) {
                return;
            }
            deduped = result.deduped();
            merged = result.merged();
            pruned = result.pruned();
        }

        void mining(CrossSessionPatternMiner.MiningResult result) {
            if (result == null) {
                return;
            }
            errorChains = result.frequentErrorChains().size();
            stableWorkflows = result.stableWorkflows().size();
            convergingStrategies = result.convergingStrategies().size();
            degradationSignals = result.degradationSignals().size();
        }

        void trends(List<TrendDetector.Trend> trendList) {
            trends = trendList != null ? trendList.size() : 0;
        }

        void bridge(LearningMaintenanceCandidateBridge.BridgeResult bridgeResult) {
            if (bridgeResult == null) {
                return;
            }
            candidateObservations = bridgeResult.upserts();
            candidateTransitions = bridgeResult.transitions();
            candidatePromoted = bridgeResult.promoted();
        }

        LearningMaintenanceRun build() {
            return new LearningMaintenanceRun(
                    Instant.now(),
                    trigger,
                    workspaceHash,
                    workingDir != null ? workingDir.toString() : "",
                    deduped,
                    merged,
                    pruned,
                    errorChains,
                    stableWorkflows,
                    convergingStrategies,
                    degradationSignals,
                    trends,
                    candidateObservations,
                    candidateTransitions,
                    candidatePromoted,
                    "maintenance "
                            + trigger
                            + ": deduped=" + deduped
                            + ", merged=" + merged
                            + ", pruned=" + pruned
                            + ", mining=" + (errorChains + stableWorkflows + convergingStrategies + degradationSignals)
                            + ", trends=" + trends
                            + ", bridge=" + candidateObservations + "/" + candidateTransitions + "/" + candidatePromoted);
        }
    }
}
