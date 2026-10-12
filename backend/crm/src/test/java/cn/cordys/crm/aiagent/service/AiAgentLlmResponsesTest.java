package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.dto.AiAgentConversationMessage;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAgentLlmResponsesTest {
    private HttpServer server;
    private AiAgentLlmClient client;
    private AiAgentRequestCancellationService cancellation;
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> reasoning = new ArrayList<>();
    private final List<String> answers = new ArrayList<>();
    private String answer = "{\"sql\":\"SELECT 1\"}";
    private String summary = "用户需要统计金额，使用 SUM 汇总。";
    private boolean brokenStream;
    private boolean ordinaryJson;
    private boolean rejectJsonFormat;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            var request = JSON.parseToMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            boolean stream = Boolean.TRUE.equals(request.get("stream")) && !ordinaryJson;
            boolean rejected = rejectJsonFormat && request.containsKey("text");
            String data = stream ? frame(Map.of("type", "response.reasoning_summary_text.delta", "delta", summary,
                    "output_index", 0, "summary_index", 0))
                    + (brokenStream ? "" : frame(Map.of("type", "response.output_text.delta", "delta", answer))
                    + frame(Map.of("type", "response.completed", "response", response()))) : JSON.toJSONString(response());
            if (rejected) data = "{}";
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(rejected ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var props = new AiAgentLlmProperties();
        props.setEnabled(true); props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        props.setModel("gpt-6.1-sol"); props.setApiType("responses"); props.setMaxRetry(0); props.setTimeoutSeconds(3);
        cancellation = new AiAgentRequestCancellationService(); client = new AiAgentLlmClient(props, cancellation);
    }

    private Map<String, Object> response() {
        return Map.of("object", "response", "status", "completed", "model", "actual-model", "output", List.of(
                Map.of("type", "reasoning", "summary", List.of(Map.of("type", "summary_text", "text", summary))),
                Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", answer)))),
                "usage", Map.of("input_tokens", 100, "output_tokens", 20, "total_tokens", 120));
    }
    private String frame(Map<String, Object> data) { return "data: " + JSON.toJSONString(data) + "\r\n\r\n"; }
    private AiAgentProgress progress() {
        return AiAgentProgress.open((event, data) -> {
            events.add(event);
            if ("reasoning".equals(event)) reasoning.add(((Map<?, ?>) data).get("text").toString());
            if ("delta".equals(event)) answers.add(((Map<?, ?>) data).get("text").toString());
        });
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void streamsRealSummaryWithoutLeakingSqlJsonAndRetainsHistoryAndUsage() {
        try (var progress = progress(); var usage = AiAgentLlmUsage.begin()) {
            AiAgentProgress.stage("sql_generation", "生成 SQL");
            var history = List.of(new AiAgentConversationMessage("user", "今年金额"), new AiAgentConversationMessage("assistant", "SELECT 1"));
            assertThat(client.chat("SQL rules", "今年金额", "primary", history)).isEqualTo(answer);
            assertThat(reasoning).containsExactly(summary);
            assertThat(answers).isEmpty();
            var result = new AiAgentChatResponse(); usage.applyTo(result);
            assertThat(result.getReasoningContent()).isEqualTo(summary);
            assertThat(result.getLlmModel()).isEqualTo("actual-model");
            assertThat(result.getTotalTokens()).isEqualTo(120L);
            assertThat(result.getModelCalls().getFirst().promptTokens()).isEqualTo(100L);
            assertThat(result.getModelCalls().getFirst().completionTokens()).isEqualTo(20L);
            assertThat(requests.getFirst()).containsEntry("store", false)
                    .containsEntry("reasoning", Map.of("effort", "medium", "summary", "auto"))
                    .containsEntry("text", Map.of("format", Map.of("type", "json_object")))
                    .doesNotContainKeys("temperature", "stream_options", "messages", "response_format");
            assertThat((List<?>) requests.getFirst().get("input")).hasSize(4);
        }
    }

    @Test void streamsAnalysisAnswerSeparatelyAndDoesNotDuplicateCompletedSummary() {
        answer = "金额增长了 10%。";
        try (var progress = progress(); var usage = AiAgentLlmUsage.begin()) {
            AiAgentProgress.stage("analysis", "分析数据");
            assertThat(client.chatText("s", "q", "primary")).isEqualTo(answer);
            assertThat(answers).containsExactly(answer);
            assertThat(reasoning).containsExactly(summary);
        }
    }

    @Test void ordinaryResponseRetainsSummaryWithoutAdditionalGeneration() {
        ordinaryJson = true;
        try (var progress = progress(); var usage = AiAgentLlmUsage.begin()) {
            AiAgentProgress.stage("chart", "生成图表");
            assertThat(client.chat("s", "q", "primary")).isEqualTo(answer);
            var result = new AiAgentChatResponse(); usage.applyTo(result);
            assertThat(result.getReasoningContent()).isEqualTo(summary);
            assertThat(requests).hasSize(1);
        }
    }

    @Test void quietKeywordAndSuggestionTasksDoNotRequestOrDisplaySummaries() {
        try (var progress = progress(); var usage = AiAgentLlmUsage.begin()) {
            for (String stage : List.of("extract_keywords", "guess")) {
                AiAgentProgress.stage(stage, stage);
                client.chatTextQuiet("s", "q", "primary", List.of());
            }
            assertThat(reasoning).isEmpty(); assertThat(answers).isEmpty();
            assertThat(requests).allSatisfy(r -> assertThat(r).doesNotContainKeys("reasoning", "stream"));
            var result = new AiAgentChatResponse(); usage.applyTo(result);
            assertThat(result.getReasoningContent()).isEmpty();
        }
    }

    @Test void incompleteStreamClearsItsSummaryBeforeSuccessfulNonstreamRetry() {
        brokenStream = true;
        try (var progress = progress(); var usage = AiAgentLlmUsage.begin()) {
            AiAgentProgress.stage("sql_generation", "生成 SQL");
            assertThat(client.chat("s", "q", "primary")).isEqualTo(answer);
            var result = new AiAgentChatResponse(); usage.applyTo(result);
            assertThat(events).contains("reasoning_reset");
            assertThat(result.getReasoningContent()).isEqualTo(summary);
            assertThat(result.getModelCalls()).extracting(AiAgentChatResponse.ModelCall::status).containsExactly("FAILED", "SUCCESS");
            assertThat(requests).hasSize(2);
        }
    }

    @Test void retriesWithoutJsonModeWhenProviderRejectsIt() {
        rejectJsonFormat = true;
        try (var progress = progress(); var usage = AiAgentLlmUsage.begin()) {
            AiAgentProgress.stage("sql_generation", "生成 SQL");
            assertThat(client.chat("s", "q", "primary")).isEqualTo(answer);
            assertThat(requests).hasSize(3);
            assertThat(requests.getLast()).doesNotContainKey("text").containsKey("reasoning");
            var result = new AiAgentChatResponse(); usage.applyTo(result);
            assertThat(result.getReasoningContent()).isEqualTo(summary);
        }
    }

    @Test void cancellationDuringSummaryDoesNotRetryOrEmitAnswer() {
        cancellation.register("request", "user");
        try (var progress = AiAgentProgress.open((event, data) -> {
            if ("reasoning".equals(event)) cancellation.cancel("request", "user");
        }); var usage = AiAgentLlmUsage.begin()) {
            AiAgentProgress.stage("analysis", "分析数据");
            assertThatThrownBy(() -> client.chatText("s", "q", "primary")).isInstanceOf(AiAgentRequestCancelledException.class);
            assertThat(requests).hasSize(1);
        } finally { cancellation.unregister("request"); }
    }

    @Test void imageInputUsesResponsesContentTypes() {
        try (var progress = AiAgentProgress.captureIfAbsent(); var usage = AiAgentLlmUsage.begin()) {
            AiAgentProgress.stage("attachment", "解析图片");
            client.analyzeImage("s", "识别图片", new byte[]{1}, "image/png", "primary");
            var input = (List<?>) requests.getFirst().get("input");
            var user = (Map<?, ?>) input.getLast();
            assertThat(user.get("content")).isEqualTo(List.of(
                    Map.of("type", "input_text", "text", "识别图片"),
                    Map.of("type", "input_image", "image_url", "data:image/png;base64,AQ==")));
        }
    }

    @Test void missingSummaryIsNotInventedAndFailedResponsesAreRejected() {
        var text = new AiAgentLlmContent(t -> {}, reasoning::add);
        var parser = new AiAgentResponsesContent(text);
        assertThatThrownBy(() -> parser.accept(Map.of("object", "response", "status", "incomplete"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parser.accept(Map.of("type", "response.failed"))).isInstanceOf(IllegalStateException.class);
        parser.accept(Map.of("type", "response.output_text.delta", "delta", ""));
        parser.accept(Map.of("object", "response", "status", "completed", "output", List.of(
                Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", "完成"))))));
        assertThat(text.answer()).isEqualTo("完成");
        assertThat(reasoning).isEmpty();
    }

    @Test void streamsMultipleSummaryPartsWithParagraphBreaks() {
        var text = new AiAgentLlmContent(t -> {}, reasoning::add);
        var parser = new AiAgentResponsesContent(text);
        parser.accept(Map.of("type", "response.reasoning_summary_text.delta", "delta", "先确认", "output_index", 0, "summary_index", 0));
        parser.accept(Map.of("type", "response.reasoning_summary_text.delta", "delta", "字段。", "output_index", 0, "summary_index", 0));
        parser.accept(Map.of("type", "response.reasoning_summary_text.delta", "delta", "再汇总金额。", "output_index", 0, "summary_index", 1));
        parser.accept(Map.of("type", "response.completed", "response", response()));
        assertThat(String.join("", reasoning)).isEqualTo("先确认字段。\n\n再汇总金额。");
        assertThat(text.answer()).isEqualTo(answer);
    }
}
