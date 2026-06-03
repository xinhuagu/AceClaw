package dev.aceclaw.daemon;

import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.PlanCheckpoint.CheckpointStatus;
import dev.aceclaw.core.planner.PlanCheckpointStore;
import dev.aceclaw.core.planner.TurnCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Detects resumable checkpoints and routes resume decisions across both plan
 * and turn checkpoint stores.
 *
 * <p>Routing priority (highest first):
 * <ol>
 *   <li>Plan checkpoint with same {@code sessionId} (exact match, most recent)</li>
 *   <li>Plan checkpoint with same {@code workspaceHash} (cross-session resume)</li>
 *   <li>Turn checkpoint with same {@code sessionId} (issue #501)</li>
 *   <li>Turn checkpoint with same {@code workspaceHash} (issue #501)</li>
 * </ol>
 *
 * <p>Never auto-resumes across workspaces.
 *
 * <p>Backward compatibility: callers that only care about plan-level resume can
 * construct with {@code new ResumeRouter(planStore)} and the turn-tier lookup
 * is skipped entirely (turn store treated as absent).
 */
public final class ResumeRouter {

    static final int DEFAULT_RESUME_PROMPT_MAX_CHARS = 6_000;

    private static final Logger log = LoggerFactory.getLogger(ResumeRouter.class);

    private final PlanCheckpointStore planStore;
    private final TurnCheckpointStore turnStore; // nullable — older callers may pass plan-only

    public ResumeRouter(PlanCheckpointStore planStore) {
        this(planStore, null);
    }

    public ResumeRouter(PlanCheckpointStore planStore, TurnCheckpointStore turnStore) {
        this.planStore = Objects.requireNonNull(planStore, "planStore");
        this.turnStore = turnStore;
    }

    /**
     * Result of a resume routing decision.
     *
     * @param resumable the best matching resumable (null if none)
     * @param route     routing scope: {@code "session"}, {@code "workspace"} (plan tiers),
     *                  {@code "turn_session"}, {@code "turn_workspace"}, or {@code "none"}.
     *                  Plan tiers retain their old labels for wire-format compatibility
     *                  with {@code resume.detected} notifications.
     * @param ambiguous true if multiple candidates exist at the same priority level
     */
    public record RouteDecision(
            Resumable resumable,
            String route,
            boolean ambiguous
    ) {
        public RouteDecision {
            Objects.requireNonNull(route, "route");
        }

        /** Returns true if a resumable was found at any priority tier. */
        public boolean hasCheckpoint() {
            return resumable != null;
        }

        /**
         * Convenience accessor: returns the plan checkpoint if the resumable is a
         * plan, otherwise null. Kept for callers (and tests) written before the
         * turn-tier extension.
         */
        public PlanCheckpoint checkpoint() {
            return resumable instanceof Resumable.OfPlan p ? p.checkpoint() : null;
        }
    }

    /**
     * Finds the highest-priority resumable checkpoint for the given session and
     * workspace, applying the 4-tier priority above.
     */
    public RouteDecision route(String sessionId, Path workspacePath) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspacePath, "workspacePath");

        String wsHash = hashWorkspace(workspacePath);

        // Priority 1: plan / same session
        var planSession = candidatePlans(planStore.findBySession(sessionId));
        if (!planSession.isEmpty()) {
            boolean ambiguous = planSession.size() > 1;
            log.info("Resume route: plan/session match, planId={}, ambiguous={}",
                    planSession.getFirst().planId(), ambiguous);
            return new RouteDecision(
                    new Resumable.OfPlan(planSession.getFirst()), "session", ambiguous);
        }

        // Priority 2: plan / same workspace
        var planWorkspace = candidatePlans(planStore.findResumable(wsHash));
        if (!planWorkspace.isEmpty()) {
            boolean ambiguous = planWorkspace.size() > 1;
            log.info("Resume route: plan/workspace match, planId={}, ambiguous={}",
                    planWorkspace.getFirst().planId(), ambiguous);
            return new RouteDecision(
                    new Resumable.OfPlan(planWorkspace.getFirst()), "workspace", ambiguous);
        }

        // Priority 3: turn / same session
        if (turnStore != null) {
            var turnSession = candidateTurns(turnStore.findBySession(sessionId));
            if (!turnSession.isEmpty()) {
                boolean ambiguous = turnSession.size() > 1;
                log.info("Resume route: turn/session match, turnId={}, ambiguous={}",
                        turnSession.getFirst().turnId(), ambiguous);
                return new RouteDecision(
                        new Resumable.OfTurn(turnSession.getFirst()), "turn_session", ambiguous);
            }

            // Priority 4: turn / same workspace
            var turnWorkspace = candidateTurns(turnStore.findResumable(wsHash));
            if (!turnWorkspace.isEmpty()) {
                boolean ambiguous = turnWorkspace.size() > 1;
                log.info("Resume route: turn/workspace match, turnId={}, ambiguous={}",
                        turnWorkspace.getFirst().turnId(), ambiguous);
                return new RouteDecision(
                        new Resumable.OfTurn(turnWorkspace.getFirst()), "turn_workspace", ambiguous);
            }
        }

        log.debug("Resume route: no resumable checkpoint found for session={}, workspace={}",
                sessionId, wsHash.substring(0, Math.min(8, wsHash.length())));
        return new RouteDecision(null, "none", false);
    }

    /**
     * Returns up to two resumables — the best plan-tier match (if any) and the
     * best turn-tier match (if any) — for surfacing as a numbered choice when
     * both coexist in the same workspace (issue #501).
     *
     * <p>The list is empty when there's nothing to resume, length 1 when only
     * one type matches (caller may resume directly without prompting), and
     * length 2 when both types match (caller fans out a {@code resume.choices}
     * notification).
     */
    public List<Resumable> findAllResumable(String sessionId, Path workspacePath) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspacePath, "workspacePath");

        String wsHash = hashWorkspace(workspacePath);
        var out = new ArrayList<Resumable>(2);

        // Best plan: session beats workspace (mirrors route() priority within tier)
        PlanCheckpoint plan = bestOf(candidatePlans(planStore.findBySession(sessionId)));
        if (plan == null) {
            plan = bestOf(candidatePlans(planStore.findResumable(wsHash)));
        }
        if (plan != null) {
            out.add(new Resumable.OfPlan(plan));
        }

        // Best turn: same intra-tier priority
        if (turnStore != null) {
            TurnCheckpoint turn = bestOfTurn(candidateTurns(turnStore.findBySession(sessionId)));
            if (turn == null) {
                turn = bestOfTurn(candidateTurns(turnStore.findResumable(wsHash)));
            }
            if (turn != null) {
                out.add(new Resumable.OfTurn(turn));
            }
        }
        return out;
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

        var plan = new ContextAssemblyPlan();
        plan.addSection("resume-header", """
                [PLAN_RESUME_CONTEXT]
                goal: %s
                planId: %s
                progress: %d/%d steps completed
                """.formatted(
                singleLine(checkpoint.originalGoal()),
                checkpoint.planId(),
                checkpoint.lastCompletedStepIndex() + 1,
                total
        ), 100, true);

        var doneSteps = new StringBuilder();
        doneSteps.append("doneSteps:\n");
        for (int i = 0; i <= checkpoint.lastCompletedStepIndex()
                && i < checkpoint.plan().steps().size(); i++) {
            var step = checkpoint.plan().steps().get(i);
            var result = i < checkpoint.completedStepResults().size()
                    ? checkpoint.completedStepResults().get(i) : null;
            doneSteps.append("  - Step ").append(i + 1).append(": ").append(step.name());
            if (result != null && result.success()) {
                String out = result.output();
                String summary = out != null && out.length() > 120
                        ? out.substring(0, 120) + "..." : (out != null ? out : "done");
                doneSteps.append(" [OK] ").append(singleLine(summary));
            } else if (result != null) {
                doneSteps.append(" [FAILED] ").append(singleLine(
                        result.error() != null ? result.error() : "unknown"));
            }
            doneSteps.append('\n');
        }
        plan.addSection("resume-done-steps", doneSteps.toString(), 45, false);

        if (nextStepObj != null) {
            plan.addSection("resume-next-step", """
                    nextStep:
                      - index: %d
                      - name: %s
                      - description: %s
                    """.formatted(
                    nextStep,
                    singleLine(nextStepObj.name()),
                    singleLine(nextStepObj.description())
            ), 95, true);
        }

        if (checkpoint.resumeHint() != null && !checkpoint.resumeHint().isBlank()) {
            plan.addSection("resume-do-not-repeat", """
                    doNotRepeat:
                      - %s
                    """.formatted(singleLine(checkpoint.resumeHint())), 85, false);
        }

        if (!checkpoint.artifacts().isEmpty()) {
            var artifacts = new StringBuilder("artifacts:\n");
            for (var artifact : checkpoint.artifacts()) {
                artifacts.append("  - ").append(singleLine(artifact)).append('\n');
            }
            plan.addSection("resume-artifacts", artifacts.toString(), 65, false);
        }

        plan.addSection("resume-action", """
                action: Continue from step %d without restarting completed work.
                [/PLAN_RESUME_CONTEXT]
                """.formatted(nextStep), 100, true);

        var budget = new SystemPromptBudget(
                Math.max(256, effectiveMaxChars / 3),
                effectiveMaxChars);
        return plan.build(budget).prompt();
    }

    /**
     * Builds a turn-level resume context prompt — analogous to
     * {@link #buildResumePrompt(PlanCheckpoint)} but for single-turn ReAct
     * checkpoints. Emits a {@code [TURN_RESUME_CONTEXT]} block with the
     * original prompt, a per-iteration completion summary, and a hard
     * instruction not to repeat completed work.
     *
     * <p>The conversationSnapshot is intentionally NOT inlined here — the
     * caller replays it as conversation history. This builder produces only
     * the textual hint the LLM sees as its first user message.
     */
    public static String buildTurnResumePrompt(TurnCheckpoint checkpoint) {
        return buildTurnResumePrompt(checkpoint, DEFAULT_RESUME_PROMPT_MAX_CHARS);
    }

    public static String buildTurnResumePrompt(TurnCheckpoint checkpoint, int maxChars) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        int effectiveMaxChars = Math.max(512, maxChars);
        int nextIteration = checkpoint.completedIterations() + 1;

        var plan = new ContextAssemblyPlan();
        plan.addSection("turn-resume-header", """
                [TURN_RESUME_CONTEXT]
                originalPrompt: %s
                turnId: %s
                completedIterations: %d
                """.formatted(
                singleLine(checkpoint.originalPrompt()),
                checkpoint.turnId(),
                checkpoint.completedIterations()
        ), 100, true);

        // Per-iteration line summary parsed from the snapshot — each assistant
        // tool_use becomes "iter N: <tool> -> <outcome>". The snapshot may be
        // empty (turn crashed before its first iteration) or in a format we
        // can't parse — in either case we degrade to the count + lastToolUseId.
        var summaryLines = summarizeIterations(checkpoint.conversationSnapshot());
        var done = new StringBuilder("completedWork:\n");
        if (!summaryLines.isEmpty()) {
            for (var line : summaryLines) {
                done.append("  - ").append(line).append('\n');
            }
        } else if (checkpoint.completedIterations() > 0) {
            done.append("  - ").append(checkpoint.completedIterations())
                    .append(" iteration(s) already executed in this turn.\n");
            if (checkpoint.lastToolUseId() != null) {
                done.append("  - lastToolUseId: ")
                        .append(singleLine(checkpoint.lastToolUseId()))
                        .append('\n');
            }
        } else {
            done.append("  - (no completed iterations recorded)\n");
        }
        plan.addSection("turn-resume-done", done.toString(), 70, false);

        if (!checkpoint.artifacts().isEmpty()) {
            var artifacts = new StringBuilder("artifacts:\n");
            for (var artifact : checkpoint.artifacts()) {
                artifacts.append("  - ").append(singleLine(artifact)).append('\n');
            }
            plan.addSection("turn-resume-artifacts", artifacts.toString(), 60, false);
        }

        plan.addSection("turn-resume-action", """
                action: Do not repeat work that already succeeded. Continue from iteration %d.
                [/TURN_RESUME_CONTEXT]
                """.formatted(nextIteration), 100, true);

        var budget = new SystemPromptBudget(
                Math.max(256, effectiveMaxChars / 3),
                effectiveMaxChars);
        return plan.build(budget).prompt();
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

    private static List<PlanCheckpoint> candidatePlans(List<PlanCheckpoint> raw) {
        return raw.stream()
                .filter(c -> isResumable(c.status()))
                .filter(PlanCheckpoint::hasRemainingSteps)
                .sorted(Comparator.comparing(PlanCheckpoint::updatedAt).reversed())
                .toList();
    }

    private static List<TurnCheckpoint> candidateTurns(List<TurnCheckpoint> raw) {
        return raw.stream()
                .filter(c -> isResumable(c.status()))
                .sorted(Comparator.comparing(TurnCheckpoint::updatedAt).reversed())
                .toList();
    }

    private static PlanCheckpoint bestOf(List<PlanCheckpoint> sorted) {
        return sorted.isEmpty() ? null : sorted.getFirst();
    }

    private static TurnCheckpoint bestOfTurn(List<TurnCheckpoint> sorted) {
        return sorted.isEmpty() ? null : sorted.getFirst();
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace('\n', ' ').strip();
    }

    /**
     * Parses {@link dev.aceclaw.core.agent.ConversationSnapshot}-shaped JSON
     * lines into per-iteration summary strings for the TURN_RESUME prompt.
     * Each "iteration" is the sliding window from one tool_use to the matching
     * tool_result; the output line reads {@code "iter N: <tool> -> <outcome>"}.
     *
     * <p>Returns an empty list if the snapshot is empty, malformed, or contains
     * no recognizable tool_use blocks — the caller falls back to a degenerate
     * count line in that case.
     *
     * <p>Best-effort regex-based extraction so this stays free of Jackson at
     * the daemon-side router (the snapshot is hand-encoded for the same
     * reason in {@code aceclaw-core}). We are NOT trying to round-trip
     * messages — just produce a human/LLM-readable digest.
     */
    static List<String> summarizeIterations(List<String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<String>();

        // Two passes over the snapshot: first collect tool_use (id → name),
        // then walk in order pairing each tool_use with its matching
        // tool_result (looked up by id) — the user/assistant message
        // ordering already serializes iterations chronologically.
        var toolNameById = new java.util.LinkedHashMap<String, String>();
        var resultById = new java.util.HashMap<String, String>(); // id -> outcome snippet
        var resultErrorById = new java.util.HashMap<String, Boolean>();

        // Regexes deliberately permissive — the format is internal but may grow
        // new fields. We extract id / name / content / is_error from each block.
        var toolUsePattern = java.util.regex.Pattern.compile(
                "\"type\"\\s*:\\s*\"tool_use\"\\s*,\\s*\"id\"\\s*:\\s*\"([^\"]+)\""
                        + "\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]+)\"");
        var toolResultPattern = java.util.regex.Pattern.compile(
                "\"type\"\\s*:\\s*\"tool_result\"\\s*,\\s*\"tool_use_id\"\\s*:\\s*\"([^\"]+)\""
                        + "\\s*,\\s*\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
                        + "\\s*,\\s*\"is_error\"\\s*:\\s*(true|false)");

        for (var line : snapshot) {
            if (line == null || line.isEmpty()) continue;
            var u = toolUsePattern.matcher(line);
            while (u.find()) {
                toolNameById.putIfAbsent(u.group(1), u.group(2));
            }
            var r = toolResultPattern.matcher(line);
            while (r.find()) {
                resultById.putIfAbsent(r.group(1), unescapeJsonString(r.group(2)));
                resultErrorById.putIfAbsent(r.group(1), Boolean.parseBoolean(r.group(3)));
            }
        }

        int idx = 0;
        for (var entry : toolNameById.entrySet()) {
            idx++;
            String id = entry.getKey();
            String tool = entry.getValue();
            String outcomeRaw = resultById.get(id);
            String outcome;
            if (outcomeRaw == null) {
                outcome = "(no result recorded — turn cut here)";
            } else {
                boolean err = resultErrorById.getOrDefault(id, false);
                String snippet = outcomeRaw.length() > 80
                        ? outcomeRaw.substring(0, 80) + "..." : outcomeRaw;
                outcome = (err ? "FAILED: " : "ok: ") + singleLine(snippet);
            }
            out.add("iter " + idx + ": " + tool + " -> " + outcome);
        }
        return out;
    }

    private static String unescapeJsonString(String escaped) {
        if (escaped == null || escaped.isEmpty()) return escaped;
        var sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 1 < escaped.length()) {
                char n = escaped.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        if (i + 4 < escaped.length()) {
                            try {
                                sb.append((char) Integer.parseInt(
                                        escaped.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException nfe) {
                                sb.append(n);
                            }
                        } else {
                            sb.append(n);
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
