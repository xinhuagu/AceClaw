package dev.aceclaw.daemon;

import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.PlanCheckpoint.CheckpointStatus;
import dev.aceclaw.core.planner.PlanCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Detects resumable plan checkpoints and routes resume decisions.
 *
 * <p>Routing priority:
 * <ol>
 *   <li>Same session ID (exact match, most recent)</li>
 *   <li>Same workspace hash (cross-session resume, most recent)</li>
 * </ol>
 *
 * <p>Never auto-resumes across workspaces.
 */
public final class ResumeRouter {

    static final int DEFAULT_RESUME_PROMPT_MAX_CHARS = 6_000;

    private static final Logger log = LoggerFactory.getLogger(ResumeRouter.class);

    private final PlanCheckpointStore checkpointStore;

    public ResumeRouter(PlanCheckpointStore checkpointStore) {
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
    }

    /**
     * Result of a resume routing decision.
     *
     * @param checkpoint the best matching checkpoint (null if none)
     * @param route      routing scope: "session", "workspace", or "none"
     * @param ambiguous  true if multiple candidates exist at the same priority level
     */
    public record RouteDecision(
            PlanCheckpoint checkpoint,
            String route,
            boolean ambiguous
    ) {
        /** Returns true if a resumable checkpoint was found. */
        public boolean hasCheckpoint() {
            return checkpoint != null;
        }
    }

    /**
     * Finds the best resumable checkpoint for the given session and workspace.
     *
     * @param sessionId     the current session ID
     * @param workspacePath the project path for workspace hash computation
     * @return routing decision with the best checkpoint, or empty decision if none found
     */
    public RouteDecision route(String sessionId, Path workspacePath) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspacePath, "workspacePath");

        String wsHash = hashWorkspace(workspacePath);

        // Priority 1: same session
        var bySession = checkpointStore.findBySession(sessionId).stream()
                .filter(c -> isResumable(c.status()))
                .filter(PlanCheckpoint::hasRemainingSteps)
                .sorted(recencyComparator())
                .toList();
        if (!bySession.isEmpty()) {
            boolean ambiguous = bySession.size() > 1;
            log.info("Resume route: session match, planId={}, ambiguous={}",
                    bySession.getFirst().planId(), ambiguous);
            return new RouteDecision(bySession.getFirst(), "session", ambiguous);
        }

        // Priority 2: same workspace (cross-session)
        var byWorkspace = checkpointStore.findResumable(wsHash).stream()
                .filter(c -> isResumable(c.status()))
                .filter(PlanCheckpoint::hasRemainingSteps)
                .sorted(recencyComparator())
                .toList();
        if (!byWorkspace.isEmpty()) {
            boolean ambiguous = byWorkspace.size() > 1;
            log.info("Resume route: workspace match, planId={}, ambiguous={}",
                    byWorkspace.getFirst().planId(), ambiguous);
            return new RouteDecision(byWorkspace.getFirst(), "workspace", ambiguous);
        }

        log.debug("Resume route: no resumable checkpoint found for session={}, workspace={}",
                sessionId, wsHash.substring(0, Math.min(8, wsHash.length())));
        return new RouteDecision(null, "none", false);
    }

    /**
     * Builds a resume context prompt for injecting into the agent loop.
     * Uses a clearly delimited block so the LLM can parse the context.
     */
    public static String buildResumePrompt(PlanCheckpoint checkpoint) {
        return buildResumePrompt(checkpoint, DEFAULT_RESUME_PROMPT_MAX_CHARS);
    }

    /**
     * Builds a resume context prompt for injecting into the agent loop with a hard size cap.
     */
    public static String buildResumePrompt(PlanCheckpoint checkpoint, int maxChars) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        int effectiveMaxChars = Math.max(512, maxChars);
        int nextStep = checkpoint.nextStepIndex() + 1; // 1-based for display
        int total = checkpoint.plan().steps().size();
        var nextStepObj = checkpoint.hasRemainingSteps()
                ? checkpoint.plan().steps().get(checkpoint.nextStepIndex())
                : null;

        var sb = new StringBuilder();
        sb.append("[PLAN_RESUME_CONTEXT]\n");
        sb.append("goal: ").append(singleLine(checkpoint.originalGoal())).append('\n');
        sb.append("planId: ").append(checkpoint.planId()).append('\n');
        sb.append("progress: ").append(checkpoint.lastCompletedStepIndex() + 1)
                .append("/").append(total).append(" steps completed\n");
        sb.append("doneSteps:\n");
        for (int i = 0; i <= checkpoint.lastCompletedStepIndex()
                && i < checkpoint.plan().steps().size(); i++) {
            var step = checkpoint.plan().steps().get(i);
            var result = i < checkpoint.completedStepResults().size()
                    ? checkpoint.completedStepResults().get(i) : null;
            sb.append("  - Step ").append(i + 1).append(": ").append(step.name());
            if (result != null && result.success()) {
                String out = result.output();
                String summary = out != null && out.length() > 120
                        ? out.substring(0, 120) + "..." : (out != null ? out : "done");
                sb.append(" [OK] ").append(singleLine(summary));
            } else if (result != null) {
                sb.append(" [FAILED] ").append(singleLine(
                        result.error() != null ? result.error() : "unknown"));
            }
            sb.append('\n');
        }

        if (nextStepObj != null) {
            sb.append("nextStep:\n");
            sb.append("  - index: ").append(nextStep).append('\n');
            sb.append("  - name: ").append(singleLine(nextStepObj.name())).append('\n');
            sb.append("  - description: ").append(singleLine(nextStepObj.description())).append('\n');
        }

        if (checkpoint.resumeHint() != null && !checkpoint.resumeHint().isBlank()) {
            sb.append("doNotRepeat:\n");
            sb.append("  - ").append(singleLine(checkpoint.resumeHint())).append('\n');
        }

        if (!checkpoint.artifacts().isEmpty()) {
            sb.append("artifacts:\n");
            for (var artifact : checkpoint.artifacts()) {
                sb.append("  - ").append(singleLine(artifact)).append('\n');
            }
        }

        sb.append("action: Continue from step ").append(nextStep)
                .append(" without restarting completed work.\n");
        sb.append("[/PLAN_RESUME_CONTEXT]");
        String prompt = sb.toString();
        if (prompt.length() <= effectiveMaxChars) {
            return prompt;
        }
        return TierTruncator.truncateContent(prompt, effectiveMaxChars);
    }

    /**
     * Computes a deterministic SHA-256 hash of the normalized workspace path.
     * Used for cross-session matching without storing raw file paths.
     */
    static String hashWorkspace(Path workspacePath) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    workspacePath.toAbsolutePath().normalize().toString().getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static boolean isResumable(CheckpointStatus status) {
        return status == CheckpointStatus.ACTIVE || status == CheckpointStatus.INTERRUPTED;
    }

    private static Comparator<PlanCheckpoint> recencyComparator() {
        return Comparator.comparing(PlanCheckpoint::updatedAt).reversed();
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace('\n', ' ').strip();
    }
}
