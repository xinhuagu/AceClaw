package dev.aceclaw.core.planner;

import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.RequestAttribution;
import dev.aceclaw.core.llm.RequestSource;
import dev.aceclaw.core.llm.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the {@link TaskPlanner} interface default method that records a single PLANNER
 * request on success. The default implementation is correct for every planner in-tree
 * today (each issues exactly one LLM call per invocation). A concrete planner that
 * issued multiple calls would need to override this method.
 */
class TaskPlannerAttributionTest {

    private static final TaskPlan STUB_PLAN = new TaskPlan(
            "plan-1", "goal", List.of(),
            new PlanStatus.Draft(), Instant.EPOCH);

    @Test
    void defaultPlanOverloadRecordsOnePlannerRequest() throws LlmException {
        TaskPlanner planner = (goal, tools) -> STUB_PLAN;
        var attribution = RequestAttribution.builder();

        planner.plan("goal", List.of(), attribution);

        var snapshot = attribution.build();
        assertThat(snapshot.total()).isEqualTo(1);
        assertThat(snapshot.count(RequestSource.PLANNER)).isEqualTo(1);
    }

    @Test
    void defaultPlanOverloadToleratesNullAttribution() throws LlmException {
        TaskPlanner planner = (goal, tools) -> STUB_PLAN;
        // The null case covers callers that don't care about attribution (existing callers).
        assertThat(planner.plan("goal", List.of(), null)).isSameAs(STUB_PLAN);
    }

    @Test
    void defaultPlanOverloadDoesNotRecordWhenPlanThrows() {
        TaskPlanner failing = new TaskPlanner() {
            @Override
            public TaskPlan plan(String goal, List<ToolDefinition> availableTools) throws LlmException {
                throw new LlmException("boom");
            }
        };
        var attribution = RequestAttribution.builder();

        assertThatThrownBy(() -> failing.plan("goal", List.of(), attribution))
                .isInstanceOf(LlmException.class);
        // On failure, no successful LLM call happened — attribution stays empty so counts
        // match actual billed requests.
        assertThat(attribution.build()).isEqualTo(RequestAttribution.empty());
    }
}
