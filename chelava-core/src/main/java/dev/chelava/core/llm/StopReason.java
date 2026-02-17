package dev.chelava.core.llm;

/**
 * Reason the model stopped generating output.
 */
public enum StopReason {

    /** The model finished its turn naturally. */
    END_TURN,

    /** The model wants to invoke a tool. */
    TOOL_USE,

    /** The response hit the max-tokens limit. */
    MAX_TOKENS,

    /** A custom stop sequence was encountered. */
    STOP_SEQUENCE,

    /** An error occurred during generation. */
    ERROR;

    /**
     * Parses a provider-specific stop reason string into this enum.
     */
    public static StopReason fromString(String value) {
        if (value == null) {
            return ERROR;
        }
        return switch (value) {
            case "end_turn" -> END_TURN;
            case "tool_use" -> TOOL_USE;
            case "max_tokens" -> MAX_TOKENS;
            case "stop_sequence" -> STOP_SEQUENCE;
            default -> ERROR;
        };
    }
}
