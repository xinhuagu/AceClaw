package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializes a {@code List<Message>} to a {@code List<String>} for inclusion
 * in a {@code TurnCheckpoint}. One JSON-encoded line per message, capturing
 * enough structure (role, text, tool-use id/name/args, tool-result id/error)
 * for a future resume path to reconstruct the conversation tail.
 *
 * <p>Deliberately hand-written rather than going through Jackson: keeps
 * {@code aceclaw-core} free of a Jackson dependency at the agent-loop layer,
 * and the on-disk format is checkpoint-internal so it doesn't need to match
 * any wire protocol.
 */
final class ConversationSnapshot {

    private ConversationSnapshot() {}

    static List<String> serialize(List<Message> messages) {
        var out = new ArrayList<String>(messages.size());
        for (var msg : messages) {
            out.add(switch (msg) {
                case Message.UserMessage u -> encodeMessage("user", u.content());
                case Message.AssistantMessage a -> encodeMessage("assistant", a.content());
            });
        }
        return out;
    }

    private static String encodeMessage(String role, List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        sb.append("{\"role\":\"").append(role).append("\",\"blocks\":[");
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(encodeBlock(blocks.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String encodeBlock(ContentBlock block) {
        return switch (block) {
            case ContentBlock.Text t ->
                    "{\"type\":\"text\",\"text\":" + jsonString(t.text()) + "}";
            case ContentBlock.Thinking th ->
                    "{\"type\":\"thinking\",\"text\":" + jsonString(th.text()) + "}";
            case ContentBlock.ToolUse u ->
                    "{\"type\":\"tool_use\",\"id\":" + jsonString(u.id())
                            + ",\"name\":" + jsonString(u.name())
                            + ",\"input\":" + jsonString(u.inputJson()) + "}";
            case ContentBlock.ToolResult r ->
                    "{\"type\":\"tool_result\",\"tool_use_id\":" + jsonString(r.toolUseId())
                            + ",\"content\":" + jsonString(r.content())
                            + ",\"is_error\":" + r.isError() + "}";
        };
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        var sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
