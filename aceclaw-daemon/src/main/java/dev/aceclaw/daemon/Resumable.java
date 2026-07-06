package dev.aceclaw.daemon;

import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpoint;

/**
 * A resumable execution slice returned by {@link ResumeRouter}. Either a
 * mid-flight {@link PlanCheckpoint} (multi-step plan that hadn't finished)
 * or a {@link TurnCheckpoint} (single ReAct turn that hadn't finished).
 *
 * <p>Sealed so the handler's resume code can switch exhaustively — the
 * compiler enforces both branches are handled when new resume types are
 * added (none planned, but the type makes the intent explicit).
 */
public sealed interface Resumable {

    /** Plan-level resume: pick up an in-progress {@code TaskPlan}. */
    record OfPlan(PlanCheckpoint checkpoint) implements Resumable {
        public OfPlan {
            if (checkpoint == null) {
                throw new IllegalArgumentException("checkpoint");
            }
        }
    }

    /** Turn-level resume: pick up an in-progress ReAct turn. */
    record OfTurn(TurnCheckpoint checkpoint) implements Resumable {
        public OfTurn {
            if (checkpoint == null) {
                throw new IllegalArgumentException("checkpoint");
            }
        }
    }
}
