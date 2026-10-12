package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;

/**
 * Captures model, timing and provider-reported token usage for each task call.
 */
final class AiAgentLlmUsage implements AutoCloseable {
    private static final ThreadLocal<AiAgentLlmUsage> CURRENT = new ThreadLocal<>();

    private final AiAgentLlmUsage previous;
    private final java.util.function.LongSupplier clock;
    private final long requestStarted;
    private Long answerFinished;
    private String provider = "none";
    private String model;
    private long started;
    private String stage;
    private final java.util.List<AiAgentChatResponse.ModelCall> calls = new java.util.ArrayList<>();
    private final java.util.List<String> reasoning = new java.util.ArrayList<>();
    private final StringBuilder pendingReasoning = new StringBuilder();
    private int callId;

    static boolean reasoningEnabled() {
        var steps = AiAgentProgress.steps();
        String stage = steps.isEmpty() ? "llm" : steps.getLast().name();
        return java.util.Set.of("llm", "sql_generation", "chart", "analysis", "predict", "attachment").contains(stage);
    }

    static void startCall() {
        var usage = CURRENT.get();
        if (usage == null) return;
        usage.started = usage.clock.getAsLong();
        usage.pendingReasoning.setLength(0);
        usage.callId++;
        var steps = AiAgentProgress.steps();
        usage.stage = steps.isEmpty() ? "llm" : steps.getLast().name();
    }

    private AiAgentLlmUsage(java.util.function.LongSupplier clock) {
        this.clock = clock;
        requestStarted = clock.getAsLong();
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static AiAgentLlmUsage begin() {
        return begin(System::nanoTime);
    }

    static AiAgentLlmUsage begin(java.util.function.LongSupplier clock) { return new AiAgentLlmUsage(clock); }

    static void finishAnswer() {
        var usage = CURRENT.get();
        if (usage != null && usage.answerFinished == null) usage.answerFinished = usage.clock.getAsLong();
    }

    static void record(String provider, String model) {
        record(provider, model, null);
    }

    static void record(String provider, String model, Object tokens) {
        AiAgentLlmUsage usage = CURRENT.get();
        if (usage != null) {
            usage.provider = provider;
            usage.model = model;
            java.util.Map<?, ?> values = tokens instanceof java.util.Map<?, ?> m ? m : java.util.Map.of();
            usage.calls.add(new AiAgentChatResponse.ModelCall(usage.stage, provider, model,
                    usage.callDurationMs(),
                    token(values.get("prompt_tokens")), token(values.get("completion_tokens")), token(values.get("total_tokens")), "SUCCESS"));
            if (!usage.pendingReasoning.isEmpty()) usage.reasoning.add(usage.pendingReasoning.toString());
            usage.pendingReasoning.setLength(0);
        }
    }

    static void reasoning(String text) {
        var usage = CURRENT.get();
        if (usage == null || !reasoningEnabled() || text == null || text.isEmpty()) return;
        usage.pendingReasoning.append(text);
        AiAgentProgress.send("reasoning", java.util.Map.of("callId", usage.callId, "text", text));
    }

    static void failed(String provider, String model) {
        var usage = CURRENT.get();
        if (usage != null) {
            usage.calls.add(new AiAgentChatResponse.ModelCall(usage.stage, provider, model,
                    usage.callDurationMs(), null, null, null, "FAILED"));
            if (!usage.pendingReasoning.isEmpty())
                AiAgentProgress.send("reasoning_reset", java.util.Map.of("callId", usage.callId));
            usage.pendingReasoning.setLength(0);
        }
    }
    private long callDurationMs() { return stage == null ? 0 : Math.max(0, (clock.getAsLong() - started) / 1_000_000); }
    private static Long token(Object value) { return value instanceof Number n ? n.longValue() : null; }

    void applyTo(AiAgentChatResponse response) {
        response.setLlmProvider(provider);
        response.setLlmModel(model);
        response.setReasoningContent(String.join("\n\n", reasoning));
        response.setModelCalls(java.util.List.copyOf(calls));
        response.setDurationMs(Math.max(0, ((answerFinished == null ? clock.getAsLong() : answerFinished) - requestStarted) / 1_000_000));
        // SQLBot excludes the separate recommended-question task from answer token usage.
        var counted = calls.stream().filter(c -> !"guess".equals(c.stage()) && "SUCCESS".equals(c.status())).toList();
        response.setTotalTokens(counted.stream().anyMatch(c -> c.totalTokens() == null) ? null
                : counted.stream().mapToLong(AiAgentChatResponse.ModelCall::totalTokens).sum());
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
