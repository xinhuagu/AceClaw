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
 */
public record AgentLoopConfig(
        String sessionId,
        EventBus eventBus,
        ToolPermissionChecker permissionChecker,
        CompactionMemoryHandler memoryHandler
) {

    /** Empty config with all integrations disabled. */
    public static final AgentLoopConfig EMPTY = new AgentLoopConfig(null, null, null, null);

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sessionId;
        private EventBus eventBus;
        private ToolPermissionChecker permissionChecker;
        private CompactionMemoryHandler memoryHandler;

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

        public AgentLoopConfig build() {
            return new AgentLoopConfig(sessionId, eventBus, permissionChecker, memoryHandler);
        }
    }
}
