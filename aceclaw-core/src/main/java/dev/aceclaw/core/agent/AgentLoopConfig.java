package dev.aceclaw.core.agent;

import dev.aceclaw.infra.event.EventBus;

/**
 * Configuration for optional integrations in the agent loop.
 *
 * <p>All fields are nullable — when null, the corresponding integration is disabled.
 * Use the {@link #builder()} to construct instances.
 *
 * @param sessionId          unique session identifier for event correlation
 * @param eventBus           event bus for publishing agent and tool events
 * @param permissionChecker  permission checker for tool execution authorization
 * @param memoryHandler      handler for persisting context extracted during compaction
 * @param metricsCollector   collector for per-tool execution statistics (null = disabled)
 * @param maxIterations      maximum ReAct iterations per turn (null/<=0 uses default)
 */
public record AgentLoopConfig(
        String sessionId,
        EventBus eventBus,
        ToolPermissionChecker permissionChecker,
        CompactionMemoryHandler memoryHandler,
        ToolMetricsCollector metricsCollector,
        Integer maxIterations
) {

    /** Default maximum ReAct iterations per turn when no override is provided. */
    public static final int DEFAULT_MAX_ITERATIONS = 25;

    /** Empty config with all integrations disabled. */
    public static final AgentLoopConfig EMPTY = new AgentLoopConfig(null, null, null, null, null, null);

    /**
     * Resolves the effective max iterations for this loop config.
     */
    public int effectiveMaxIterations() {
        if (maxIterations == null || maxIterations <= 0) {
            return DEFAULT_MAX_ITERATIONS;
        }
        return maxIterations;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sessionId;
        private EventBus eventBus;
        private ToolPermissionChecker permissionChecker;
        private CompactionMemoryHandler memoryHandler;
        private ToolMetricsCollector metricsCollector;
        private Integer maxIterations;

        private Builder() {}

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder eventBus(EventBus eventBus) {
            this.eventBus = eventBus;
            return this;
        }

        public Builder permissionChecker(ToolPermissionChecker permissionChecker) {
            this.permissionChecker = permissionChecker;
            return this;
        }

        public Builder memoryHandler(CompactionMemoryHandler memoryHandler) {
            this.memoryHandler = memoryHandler;
            return this;
        }

        public Builder metricsCollector(ToolMetricsCollector metricsCollector) {
            this.metricsCollector = metricsCollector;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public AgentLoopConfig build() {
            return new AgentLoopConfig(
                    sessionId, eventBus, permissionChecker, memoryHandler, metricsCollector, maxIterations);
        }
    }
}
