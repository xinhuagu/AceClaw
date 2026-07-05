package dev.aceclaw.daemon;

/**
 * Thematic config grouping for the agent + plan watchdog budgets — the 6
 * scalars that wall-clock-bound and turn-bound a single user prompt and
 * any plan steps it spawns. Batch 4 of the AceClawConfig decomposition.
 *
 * <h3>Shape</h3>
 *
 * <ul>
 *   <li><b>Soft agent budgets</b>:
 *     <ul>
 *       <li>{@code agentTurns} — soft per-prompt turn limit (default 200);
 *           past this the agent gets a hint to wrap up but the turn loop
 *           keeps going up to the hard ceiling.</li>
 *       <li>{@code agentWallTimeSec} — soft per-prompt wall-clock budget in
 *           seconds (default 1800).</li>
 *     </ul>
 *   </li>
 *   <li><b>Hard agent ceilings</b>:
 *     <ul>
 *       <li>{@code agentHardTurns} — hard turn ceiling. {@code 0} means
 *           "derive from soft limit (3×)" — the daemon's StreamingAgentHandler
 *           applies that derivation.</li>
 *       <li>{@code agentHardWallTimeSec} — hard wall-clock ceiling. Same
 *           "0 = 3× soft" convention.</li>
 *     </ul>
 *   </li>
 *   <li><b>Plan budgets</b>:
 *     <ul>
 *       <li>{@code planStepWallTimeSec} — per-step wall-clock budget for
 *           plan-mode prompts (default 1800).</li>
 *       <li>{@code planTotalWallTimeSec} — total wall-clock budget across
 *           all plan steps in one prompt (default 3600).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>All 6 values are non-negative integers. {@code 0} on the hard
 * ceilings has a special "derive from soft" meaning preserved here for
 * compatibility — interpreting that is the StreamingAgentHandler's job,
 * not this record's.
 */
public record WatchdogSettings(
        int agentTurns,
        int agentWallTimeSec,
        int agentHardTurns,
        int agentHardWallTimeSec,
        int planStepWallTimeSec,
        int planTotalWallTimeSec) {

    /** Defaults matched 1:1 to the prior {@code DEFAULT_MAX_AGENT_*} / {@code DEFAULT_MAX_PLAN_*} constants. */
    public static WatchdogSettings defaults() {
        return new WatchdogSettings(
                200,    // agentTurns         — soft per-prompt turn limit
                1800,   // agentWallTimeSec   — soft per-prompt wall-clock budget
                0,      // agentHardTurns     — 0 = derive 3× soft at consumer
                0,      // agentHardWallTimeSec — same 0-as-derive convention
                1800,   // planStepWallTimeSec — per-step wall-clock budget
                3600    // planTotalWallTimeSec — total across all plan steps
        );
    }

    /** Fresh builder pre-loaded with {@link #defaults()}. */
    static Builder builder() {
        return new Builder(defaults());
    }

    static final class Builder {
        private int agentTurns;
        private int agentWallTimeSec;
        private int agentHardTurns;
        private int agentHardWallTimeSec;
        private int planStepWallTimeSec;
        private int planTotalWallTimeSec;

        Builder(WatchdogSettings seed) {
            java.util.Objects.requireNonNull(seed, "seed");
            this.agentTurns = seed.agentTurns();
            this.agentWallTimeSec = seed.agentWallTimeSec();
            this.agentHardTurns = seed.agentHardTurns();
            this.agentHardWallTimeSec = seed.agentHardWallTimeSec();
            this.planStepWallTimeSec = seed.planStepWallTimeSec();
            this.planTotalWallTimeSec = seed.planTotalWallTimeSec();
        }

        // Pre-decomp validation: each field skipped on null OR negative
        // (the file-merge path used `v >= 0` everywhere). Preserved here.
        // The env-var paths used Math.max(0, parsed) — same effect on the
        // observable stored value (negative gets clamped or skipped, never
        // stored).
        Builder agentTurns(Integer v) { if (v != null && v >= 0) this.agentTurns = v; return this; }
        Builder agentWallTimeSec(Integer v) { if (v != null && v >= 0) this.agentWallTimeSec = v; return this; }
        Builder agentHardTurns(Integer v) { if (v != null && v >= 0) this.agentHardTurns = v; return this; }
        Builder agentHardWallTimeSec(Integer v) { if (v != null && v >= 0) this.agentHardWallTimeSec = v; return this; }
        Builder planStepWallTimeSec(Integer v) { if (v != null && v >= 0) this.planStepWallTimeSec = v; return this; }
        Builder planTotalWallTimeSec(Integer v) { if (v != null && v >= 0) this.planTotalWallTimeSec = v; return this; }

        WatchdogSettings build() {
            return new WatchdogSettings(
                    agentTurns, agentWallTimeSec,
                    agentHardTurns, agentHardWallTimeSec,
                    planStepWallTimeSec, planTotalWallTimeSec);
        }
    }
}
