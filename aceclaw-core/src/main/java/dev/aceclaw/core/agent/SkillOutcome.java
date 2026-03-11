package dev.aceclaw.core.agent;

import java.time.Instant;

/**
 * Outcome of a single skill invocation.
 */
public sealed interface SkillOutcome permits
        SkillOutcome.Success,
        SkillOutcome.Failure,
        SkillOutcome.UserCorrected {

    /**
     * Timestamp when the outcome was observed.
     */
    Instant timestamp();

    /**
     * Successful skill invocation.
     *
     * @param timestamp when the invocation finished successfully
     * @param turnsUsed turn segments consumed by the invocation
     */
    record Success(Instant timestamp, int turnsUsed) implements SkillOutcome {
        public Success {
            timestamp = timestamp != null ? timestamp : Instant.now();
            turnsUsed = Math.max(1, turnsUsed);
        }
    }

    /**
     * Failed skill invocation.
     *
     * @param timestamp when the failure was observed
     * @param reason    compact failure summary
     */
    record Failure(Instant timestamp, String reason) implements SkillOutcome {
        public Failure {
            timestamp = timestamp != null ? timestamp : Instant.now();
            reason = reason == null ? "" : reason;
        }
    }

    /**
     * User correction that invalidated or amended a prior skill result.
     *
     * @param timestamp  when the correction was observed
     * @param correction compact user correction text
     */
    record UserCorrected(Instant timestamp, String correction) implements SkillOutcome {
        public UserCorrected {
            timestamp = timestamp != null ? timestamp : Instant.now();
            correction = correction == null ? "" : correction;
        }
    }
}
