package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.AgentLoop;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles {@code agent.prompt} JSON-RPC requests by running the agent's ReAct loop.
 *
 * <p>Bridges the daemon's session model with the core agent loop: converts
 * {@link AgentSession.ConversationMessage} history into LLM {@link Message} format,
 * runs the agent turn, and stores the results back into the session.
 */
public final class AgentHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentHandler.class);

    private final SessionManager sessionManager;
    private final AgentLoop agentLoop;
    private final ObjectMapper objectMapper;

    /**
     * Creates an agent handler.
     *
     * @param sessionManager the session manager for looking up sessions
     * @param agentLoop      the agent loop to execute prompts
     * @param objectMapper   Jackson mapper for building JSON responses
     */
    public AgentHandler(SessionManager sessionManager, AgentLoop agentLoop, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.agentLoop = agentLoop;
        this.objectMapper = objectMapper;
    }

    /**
     * Registers the {@code agent.prompt} handler with the given router.
     */
    public void register(RequestRouter router) {
        router.register("agent.prompt", this::handlePrompt);
    }

    private Object handlePrompt(JsonNode params) throws Exception {
        var sessionId = requireString(params, "sessionId");
        var prompt = requireString(params, "prompt");

        var session = sessionManager.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        if (!session.isActive()) {
            throw new IllegalArgumentException("Session is not active: " + sessionId);
        }

        log.info("Agent prompt: sessionId={}, promptLength={}", sessionId, prompt.length());

        // Convert session conversation history to LLM messages
        var conversationHistory = toMessages(session.messages());

        // Run the agent turn
        var turn = agentLoop.runTurn(prompt, conversationHistory);

        // Store messages in the session
        session.addMessage(new AgentSession.ConversationMessage.User(prompt));
        var responseText = turn.text();
        if (!responseText.isEmpty()) {
            session.addMessage(new AgentSession.ConversationMessage.Assistant(responseText));
        }

        // Build response
        var result = objectMapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("response", responseText);
        result.put("stopReason", turn.finalStopReason().name());

        var usageNode = objectMapper.createObjectNode();
        usageNode.put("inputTokens", turn.totalUsage().inputTokens());
        usageNode.put("outputTokens", turn.totalUsage().outputTokens());
        usageNode.put("totalTokens", turn.totalUsage().totalTokens());
        result.set("usage", usageNode);

        log.info("Agent turn complete: sessionId={}, stopReason={}, tokens={}",
                sessionId, turn.finalStopReason(), turn.totalUsage().totalTokens());

        return result;
    }

    /**
     * Converts the session's conversation messages into LLM {@link Message} format.
     * For the MVP, only user and assistant text messages are converted.
     */
    private static List<Message> toMessages(List<AgentSession.ConversationMessage> conversationMessages) {
        var messages = new ArrayList<Message>();
        for (var msg : conversationMessages) {
            switch (msg) {
                case AgentSession.ConversationMessage.User u ->
                        messages.add(Message.user(u.content()));
                case AgentSession.ConversationMessage.Assistant a ->
                        messages.add(Message.assistant(a.content()));
                case AgentSession.ConversationMessage.System ignored -> {
                    // System messages are handled via the system prompt, not in conversation history
                }
            }
        }
        return messages;
    }

    private static String requireString(JsonNode params, String field) {
        if (params == null || !params.has(field) || params.get(field).isNull()) {
            throw new IllegalArgumentException("Missing required parameter: " + field);
        }
        return params.get(field).asText();
    }
}
