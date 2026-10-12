package cn.cordys.crm.aiagent.service;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessStep;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessDetail;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Streaming stays on the authenticated request thread, including organization and cancellation context. */
public final class AiAgentProgress implements AutoCloseable {
    private static final ThreadLocal<AiAgentProgress> CURRENT = new ThreadLocal<>();
    private final AiAgentProgress previous;
    private final BiConsumer<String, Object> sink;
    private final List<ProcessStep> steps = new ArrayList<>();
    private final LongSupplier clock;
    private long stageStarted;
    private AiAgentProgress(BiConsumer<String, Object> sink, LongSupplier clock) {
        previous = CURRENT.get(); this.sink = sink; this.clock = clock; CURRENT.set(this);
    }
    public static AiAgentProgress open(BiConsumer<String, Object> sink) { return open(sink, System::nanoTime); }
    static AiAgentProgress open(BiConsumer<String, Object> sink, LongSupplier clock) { return new AiAgentProgress(sink, clock); }
    public static AiAgentProgress captureIfAbsent() { return CURRENT.get() == null ? open(null) : null; }
    public static boolean streaming() { return CURRENT.get() != null && CURRENT.get().sink != null; }
    public static void send(String event, Object data) { if (streaming()) CURRENT.get().sink.accept(event, data); }
    public static List<ProcessStep> steps() { return CURRENT.get() == null ? List.of() : List.copyOf(CURRENT.get().steps); }
    public static void stage(String name, String summary) {
        stage(name, summary, List.of());
    }
    public static void stage(String name, String summary, List<ProcessDetail> details) {
        boolean failed = name.endsWith("_failed") || name.endsWith("_rejected");
        var step = new ProcessStep(name, summary, details, null, failed ? "FAILED" : "RUNNING");
        var progress = CURRENT.get();
        if (progress != null) {
            progress.finishCurrent(failed);
            if (progress.steps.size() < 100) {
                progress.steps.add(step);
                progress.stageStarted = progress.clock.getAsLong();
            }
        }
        send("stage", step);
    }
    public static void failCurrent() { if (CURRENT.get() != null) CURRENT.get().finishCurrent(true); }
    public static void completeCurrent(String summary, List<ProcessDetail> details) {
        var progress = CURRENT.get();
        if (progress == null || progress.steps.isEmpty()) return;
        progress.finishCurrent(false);
        var step = progress.steps.getLast();
        progress.steps.set(progress.steps.size() - 1,
                new ProcessStep(step.name(), summary, details, step.durationMs(), step.status()));
    }
    public static List<ProcessStep> finish(boolean failed) {
        if (CURRENT.get() != null) CURRENT.get().finishCurrent(failed);
        return steps();
    }
    private void finishCurrent(boolean failed) {
        if (steps.isEmpty()) return;
        var step = steps.getLast();
        steps.set(steps.size() - 1, new ProcessStep(step.name(), step.summary(), step.details(),
                step.durationMs() == null ? Math.max(0, (clock.getAsLong() - stageStarted) / 1_000_000) : step.durationMs(),
                failed || "FAILED".equals(step.status()) ? "FAILED" : "SUCCESS"));
    }
    public static void delta(String text) { send("delta", Map.of("text", text)); }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
