package cn.cordys.crm.aiagent.dto;

/** A text turn replayed to an OpenAI-compatible chat endpoint. */
public record AiAgentConversationMessage(String role, String content) {
}
