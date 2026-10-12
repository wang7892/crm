package cn.cordys.crm.aiagent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads provider-exposed Responses summaries separately from answer text and token usage. */
final class AiAgentResponsesContent {
    private final AiAgentLlmContent content;
    private boolean answerDeltas;
    private boolean reasoningDeltas;
    private boolean completed;
    private String summaryPart;
    private String model;
    private Object usage;

    AiAgentResponsesContent(AiAgentLlmContent content) { this.content = content; }

    void accept(Map<String, Object> event) {
        String type = event.get("type") instanceof String value ? value : "";
        if (List.of("error", "response.failed", "response.incomplete").contains(type))
            throw new IllegalStateException("Model Responses request failed or incomplete");
        if ("response.output_text.delta".equals(type) && event.get("delta") instanceof String text && !text.isEmpty()) {
            content.accept(text, null);
            answerDeltas = true;
        }
        if ("response.reasoning_summary_text.delta".equals(type) && event.get("delta") instanceof String text && !text.isEmpty()) {
            String part = event.get("output_index") + ":" + event.get("summary_index");
            if (reasoningDeltas && !part.equals(summaryPart)) content.accept(null, "\n\n");
            content.accept(null, text);
            summaryPart = part;
            reasoningDeltas = true;
        }
        Map<?, ?> response = event.get("response") instanceof Map<?, ?> value ? value : event;
        if (response.get("model") instanceof String value && !value.isBlank()) model = value;
        if (response.get("usage") instanceof Map<?, ?> values) {
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("prompt_tokens", values.get("input_tokens"));
            normalized.put("completion_tokens", values.get("output_tokens"));
            normalized.put("total_tokens", values.get("total_tokens"));
            usage = normalized;
        }
        if ("response.completed".equals(type) || "response".equals(event.get("object"))) {
            if (!"completed".equals(response.get("status")))
                throw new IllegalStateException("Model Responses request did not complete");
            completed = true;
            if (response.get("output") instanceof List<?> output) {
                var summaries = new java.util.ArrayList<String>();
                var answer = new StringBuilder();
                for (Object item : output) {
                    if (!(item instanceof Map<?, ?> value)) continue;
                    if ("reasoning".equals(value.get("type")) && value.get("summary") instanceof List<?> parts) {
                        for (Object part : parts)
                            if (part instanceof Map<?, ?> p && "summary_text".equals(p.get("type")) && p.get("text") instanceof String text)
                                summaries.add(text);
                    }
                    if ("message".equals(value.get("type")) && value.get("content") instanceof List<?> parts) {
                        for (Object part : parts)
                            if (part instanceof Map<?, ?> p && "output_text".equals(p.get("type")) && p.get("text") instanceof String text)
                                answer.append(text);
                    }
                }
                if (!reasoningDeltas) content.accept(null, String.join("\n\n", summaries));
                if (!answerDeltas) content.accept(answer.toString(), null);
            }
        }
    }

    boolean completed() { return completed; }
    String model(String fallback) { return model == null ? fallback : model; }
    Object usage() { return usage; }
}
