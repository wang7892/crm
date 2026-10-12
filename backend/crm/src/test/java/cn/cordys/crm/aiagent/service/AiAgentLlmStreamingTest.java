package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AiAgentLlmStreamingTest {
    private AiAgentLlmClient client(HttpServer server, AiAgentRequestCancellationService cancellation) {
        var props = new AiAgentLlmProperties(); props.setEnabled(true); props.setModel("test"); props.setMaxRetry(0);
        props.setTimeoutSeconds(3); props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new AiAgentLlmClient(props, cancellation);
    }
    private String chunk(String value) {
        return "data: " + JSON.toJSONString(Map.of("model", "actual-model", "choices", List.of(Map.of("delta", Map.of("content", value))))) + "\r\n\r\n";
    }

    @Test void streamsOnlyAnswerTextAndRetainsActualModel() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Map<String, Object>> requests = new ArrayList<>();
        server.createContext("/v1/chat/completions", exchange -> {
            var request = JSON.parseToMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            String body = Boolean.TRUE.equals(request.get("stream")) ? chunk("销售") + chunk("额为100") + "data: [DONE]\r\n\r\n"
                    : "{\"choices\":[{\"message\":{\"content\":\"private planning JSON\"}}]}";
            exchange.getResponseHeaders().set("Content-Type", Boolean.TRUE.equals(request.get("stream")) ? "text/event-stream" : "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        List<String> deltas = new ArrayList<>();
        try (var usage = AiAgentLlmUsage.begin();
             var scope = AiAgentProgress.open((event, data) -> { if ("delta".equals(event)) deltas.add((String) ((Map<?, ?>) data).get("text")); })) {
            var client = client(server, new AiAgentRequestCancellationService());
            AiAgentProgress.stage("extract_keywords", "提取关键词");
            assertThat(client.chat("planner", "query", "primary")).isEqualTo("private planning JSON");
            assertThat(deltas).isEmpty();
            assertThat(client.chatText("analyst", "answer", "primary")).isEqualTo("销售额为100");
            assertThat(deltas).containsExactly("销售", "额为100");
            var answer = new cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse(); usage.applyTo(answer);
            assertThat(answer.getLlmModel()).isEqualTo("actual-model");
            assertThat(requests).hasSize(2);
            assertThat(requests.getFirst()).doesNotContainKey("stream");
        } finally { server.stop(0); }
        assertThat(AiAgentProgress.streaming()).isFalse();
    }

    private String reasoningChunk(String reasoning, String content) {
        return "data: " + JSON.toJSONString(Map.of("choices", List.of(Map.of("delta",
                Map.of("reasoning_content", reasoning, "content", content))))) + "\n\n";
    }

    @Test void streamsSqlAndChartReasoningWithoutExposingGeneratedJson() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Map<String, Object>> requests = new ArrayList<>();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JSON.parseToMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = (reasoningChunk("先按产品汇总。", "") + reasoningChunk("再取前五名。", "{\"sql\":\"SELECT 1\"}")
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var deltas = new ArrayList<String>();
        var thinking = new ArrayList<String>();
        try (var scope = AiAgentProgress.open((event, data) -> {
                 if ("delta".equals(event)) deltas.add(((Map<?, ?>) data).get("text").toString());
                 if ("reasoning".equals(event)) thinking.add(((Map<?, ?>) data).get("text").toString());
             }); var usage = AiAgentLlmUsage.begin()) {
            var client = client(server, new AiAgentRequestCancellationService());
            for (String stage : List.of("sql_generation", "chart")) {
                AiAgentProgress.stage(stage, stage);
                assertThat(client.chat("s", "u", "primary")).isEqualTo("{\"sql\":\"SELECT 1\"}");
            }
            assertThat(deltas).isEmpty();
            assertThat(thinking).containsExactly("先按产品汇总。", "再取前五名。", "先按产品汇总。", "再取前五名。");
            var response = new cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse(); usage.applyTo(response);
            assertThat(response.getReasoningContent()).isEqualTo("先按产品汇总。再取前五名。\n\n先按产品汇总。再取前五名。");
            assertThat(requests).allSatisfy(r -> {
                assertThat(r.get("stream")).isEqualTo(true);
                assertThat(r.get("response_format")).isEqualTo(Map.of("type", "json_object"));
            });
            var restored = JSON.parseObject(JSON.toJSONString(response), cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.class);
            assertThat(restored.getReasoningContent()).isEqualTo(response.getReasoningContent());
        } finally { server.stop(0); }
    }

    @Test void plainResponsesPersistReasoningAndExcludeKeywordAndSuggestionThoughts() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = JSON.toJSONString(Map.of("choices", List.of(Map.of("message", Map.of(
                    "content", "{\"sql\":\"SELECT 1\"}", "reasoning_content", "需要先确认数据表。")))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try (var progress = AiAgentProgress.captureIfAbsent(); var usage = AiAgentLlmUsage.begin()) {
            var client = client(server, new AiAgentRequestCancellationService());
            for (String stage : List.of("extract_keywords", "sql_generation", "guess")) {
                AiAgentProgress.stage(stage, stage);
                client.chat("s", "u", "primary");
            }
            var response = new cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse(); usage.applyTo(response);
            assertThat(response.getReasoningContent()).isEqualTo("需要先确认数据表。");
        } finally { server.stop(0); }
    }

    @Test void failedStreamRemovesPartialReasoningAndKeepsEarlierSuccessfulCall() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var request = JSON.parseToMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean stream = Boolean.TRUE.equals(request.get("stream"));
            byte[] bytes = (stream ? reasoningChunk("失败请求的片段", "")
                    : "{\"choices\":[{\"message\":{\"content\":\"回答\",\"reasoning_content\":\"重试成功的文本\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var resets = new ArrayList<Integer>();
        try (var progress = AiAgentProgress.open((event, data) -> {
            if ("reasoning_reset".equals(event)) resets.add((Integer) ((Map<?, ?>) data).get("callId"));
        }); var usage = AiAgentLlmUsage.begin()) {
            AiAgentLlmUsage.startCall(); AiAgentLlmUsage.reasoning("前一任务的文本"); AiAgentLlmUsage.record("p", "m");
            assertThat(client(server, new AiAgentRequestCancellationService()).chatText("s", "u", "primary")).isEqualTo("回答");
            var response = new cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse(); usage.applyTo(response);
            assertThat(response.getReasoningContent()).isEqualTo("前一任务的文本\n\n重试成功的文本");
            assertThat(resets).containsExactly(2);
        } finally { server.stop(0); }
    }

    @Test void separatesSplitThinkingTagsAndLeavesOrdinaryTextUntouched() {
        var reasoning = new StringBuilder(); var deltas = new ArrayList<String>();
        var content = new AiAgentLlmContent(deltas::add, reasoning::append);
        for (String chunk : List.of("<thi", "nk>先确认", "数据。</th", "ink>最后", "回答")) content.accept(chunk, null);
        content.finish();
        assertThat(reasoning.toString()).isEqualTo("先确认数据。");
        assertThat(content.answer()).isEqualTo("最后回答");
        assertThat(String.join("", deltas)).isEqualTo("最后回答");
        var plain = new AiAgentLlmContent(t -> {}, t -> fail("Unexpected reasoning"));
        plain.accept("普通回答包含 <think> 字面文本", null); plain.finish();
        assertThat(plain.answer()).isEqualTo("普通回答包含 <think> 字面文本");
    }

    @Test void providerReturningOrdinaryJsonDoesNotTriggerDuplicateGeneration() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new ArrayList<>();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = "{\"choices\":[{\"message\":{\"content\":\"普通回答\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try (var scope = AiAgentProgress.open((event, data) -> {})) {
            assertThat(client(server, new AiAgentRequestCancellationService()).chatText("s", "u", "primary")).isEqualTo("普通回答");
            assertThat(requests).hasSize(1);
        } finally { server.stop(0); }
    }

    @Test void cancellationDuringStreamDoesNotRetryProvider() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new ArrayList<>();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = (chunk("first") + chunk("second") + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var cancellation = new AiAgentRequestCancellationService(); cancellation.register("request", "user");
        try (var scope = AiAgentProgress.open((event, data) -> { if ("delta".equals(event)) cancellation.cancel("request", "user"); })) {
            assertThatThrownBy(() -> client(server, cancellation).chatText("s", "u", "primary"))
                    .isInstanceOf(AiAgentRequestCancelledException.class);
            assertThat(requests).hasSize(1);
        } finally { cancellation.unregister("request"); server.stop(0); }
    }
}
