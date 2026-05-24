package dev.aceclaw.daemon;

/**
 * Thematic config grouping for the daemon's per-subsystem lifecycle gates +
 * tick cadences — the 8 small scalars that decide whether the boot script,
 * cron scheduler, heartbeat tasks, and deferred-action scheduler run, and
 * how often the tick-driven ones poll. Batch 5 of the AceClawConfig
 * decomposition.
 *
 * <h3>Shape</h3>
 *
 * <ul>
 *   <li><b>Boot</b>:
 *     <ul>
 *       <li>{@code bootEnabled} — run {@code BOOT.md} at daemon startup
 *           (default true).</li>
 *       <li>{@code bootTimeoutSeconds} — wall-clock cap on boot script
 *           execution (default 120s).</li>
 *     </ul>
 *   </li>
 *   <li><b>Cron scheduler</b>:
 *     <ul>
 *       <li>{@code schedulerEnabled} — start the cron scheduler at boot
 *           (default true). The scheduler also feeds the heartbeat tasks
 *           below — disabling it disables both.</li>
 *       <li>{@code schedulerTickSeconds} — scheduler tick cadence
 *           (default 60s).</li>
 *     </ul>
 *   </li>
 *   <li><b>Heartbeat</b>:
 *     <ul>
 *       <li>{@code heartbeatEnabled} — run heartbeat tasks from
 *           {@code HEARTBEAT.md} (default true). No-op if the cron
 *           scheduler is disabled.</li>
 *       <li>{@code heartbeatActiveHours} — optional {@code "HH:mm-HH:mm"}
 *           window when heartbeat tasks may fire; {@code null} = always
 *           active.</li>
 *     </ul>
 *   </li>
 *   <li><b>Deferred-action scheduler</b>:
 *     <ul>
 *       <li>{@code deferredActionEnabled} — start the deferred-action
 *           scheduler at boot (default true).</li>
 *       <li>{@code deferredActionTickSeconds} — deferred-action tick
 *           cadence (default 5s — much tighter than cron because deferred
 *           actions are user-facing latency).</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public record DaemonLifecycleSettings(
        boolean bootEnabled,
        int bootTimeoutSeconds,
        boolean schedulerEnabled,
        int schedulerTickSeconds,
        boolean heartbeatEnabled,
        String heartbeatActiveHours,
        boolean deferredActionEnabled,
        int deferredActionTickSeconds) {

    /** Defaults matched 1:1 to the prior {@code DEFAULT_BOOT_*} / {@code DEFAULT_SCHEDULER_*}
     *  / {@code DEFAULT_HEARTBEAT_*} / {@code DEFAULT_DEFERRED_ACTION_*} constants. */
    public static DaemonLifecycleSettings defaults() {
        return new DaemonLifecycleSettings(
                true,    // bootEnabled
                120,     // bootTimeoutSeconds
                true,    // schedulerEnabled
                60,      // schedulerTickSeconds
                true,    // heartbeatEnabled
                null,    // heartbeatActiveHours — null = always active
                true,    // deferredActionEnabled
                5        // deferredActionTickSeconds
        );
    }

    /** Fresh builder pre-loaded with {@link #defaults()}. */
    static Builder builder() {
        return new Builder(defaults());
    }

    static final class Builder {
        private boolean bootEnabled;
        private int bootTimeoutSeconds;
        private boolean schedulerEnabled;
        private int schedulerTickSeconds;
        private boolean heartbeatEnabled;
        private String heartbeatActiveHours;
        private boolean deferredActionEnabled;
        private int deferredActionTickSeconds;

        Builder(DaemonLifecycleSettings seed) {
            java.util.Objects.requireNonNull(seed, "seed");
            this.bootEnabled = seed.bootEnabled();
            this.bootTimeoutSeconds = seed.bootTimeoutSeconds();
            this.schedulerEnabled = seed.schedulerEnabled();
            this.schedulerTickSeconds = seed.schedulerTickSeconds();
            this.heartbeatEnabled = seed.heartbeatEnabled();
            this.heartbeatActiveHours = seed.heartbeatActiveHours();
            this.deferredActionEnabled = seed.deferredActionEnabled();
            this.deferredActionTickSeconds = seed.deferredActionTickSeconds();
        }

        // Pre-decomp file-merge gates were:
        //   bool flags:   if (fileConfig.xxx != null)
        //   int ticks:    if (fileConfig.xxx > 0)          (>0, not >=0 — 0 was treated as "unset")
        //   activeHours:  if (s != null) set (blank → null)
        // Preserved here so the builder contract matches observable behaviour.
        Builder bootEnabled(Boolean v) { if (v != null) this.bootEnabled = v; return this; }
        Builder bootTimeoutSeconds(int v) { if (v > 0) this.bootTimeoutSeconds = v; return this; }
        Builder schedulerEnabled(Boolean v) { if (v != null) this.schedulerEnabled = v; return this; }
        Builder schedulerTickSeconds(int v) { if (v > 0) this.schedulerTickSeconds = v; return this; }
        Builder heartbeatEnabled(Boolean v) { if (v != null) this.heartbeatEnabled = v; return this; }
        Builder heartbeatActiveHours(String v) {
            if (v != null) this.heartbeatActiveHours = v.isBlank() ? null : v;
            return this;
        }
        Builder deferredActionEnabled(Boolean v) { if (v != null) this.deferredActionEnabled = v; return this; }
        Builder deferredActionTickSeconds(int v) { if (v > 0) this.deferredActionTickSeconds = v; return this; }

        DaemonLifecycleSettings build() {
            return new DaemonLifecycleSettings(
                    bootEnabled, bootTimeoutSeconds,
                    schedulerEnabled, schedulerTickSeconds,
                    heartbeatEnabled, heartbeatActiveHours,
                    deferredActionEnabled, deferredActionTickSeconds);
        }
    }
}
