package cn.cordys.crm.aiagent.service;

import java.util.function.Consumer;

/** Separates provider reasoning from answer content, including tags split across SSE chunks. */
final class AiAgentLlmContent {
    private static final String START_TAG = "<think>";
    private static final String END_TAG = "</think>";
    private final Consumer<String> answerSink;
    private final Consumer<String> reasoningSink;
    private final StringBuilder answer = new StringBuilder();
    private String pending = "";
    private boolean atStart = true;
    private boolean thinking;
    private boolean explicitReasoning;
    private int size;

    AiAgentLlmContent(Consumer<String> answerSink, Consumer<String> reasoningSink) {
        this.answerSink = answerSink;
        this.reasoningSink = reasoningSink;
    }

    void accept(Object content, Object reasoning) {
        if (reasoning instanceof String text && !text.isEmpty()) {
            explicitReasoning = true;
            emitReasoning(text);
        }
        if (!(content instanceof String text) || text.isEmpty()) return;
        size += text.length();
        if (size > 500_000) throw new IllegalStateException("Stream content too large");
        pending += text;
        if (explicitReasoning && !thinking) {
            atStart = false;
            emitAnswer(pending);
            pending = "";
            return;
        }
        if (atStart) {
            String start = pending.stripLeading();
            if (START_TAG.startsWith(start)) return;
            atStart = false;
            if (start.startsWith(START_TAG)) {
                thinking = true;
                pending = start.substring(START_TAG.length());
            }
        }
        if (thinking) {
            int end = pending.indexOf(END_TAG);
            if (end >= 0) {
                emitReasoning(pending.substring(0, end));
                pending = pending.substring(end + END_TAG.length());
                thinking = false;
            } else {
                int suffix = 0;
                for (int i = 1; i < END_TAG.length() && i <= pending.length(); i++) {
                    if (pending.endsWith(END_TAG.substring(0, i))) suffix = i;
                }
                emitReasoning(pending.substring(0, pending.length() - suffix));
                pending = pending.substring(pending.length() - suffix);
                return;
            }
        }
        emitAnswer(pending);
        pending = "";
    }

    void finish() {
        if (thinking) emitReasoning(pending);
        else emitAnswer(pending);
        pending = "";
    }

    String answer() { return answer.toString(); }

    private void emitAnswer(String text) {
        if (text.isEmpty()) return;
        answer.append(text);
        answerSink.accept(text);
    }

    private void emitReasoning(String text) {
        if (text.isEmpty()) return;
        size += text.length();
        if (size > 1_000_000) throw new IllegalStateException("Model reasoning too large");
        reasoningSink.accept(text);
    }
}
