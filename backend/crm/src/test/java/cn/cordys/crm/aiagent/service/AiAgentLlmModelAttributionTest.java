package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAgentLlmModelAttributionTest {
    private HttpServer server;
    private AiAgentLlmProperties properties;
    private AiAgentLlmClient client;

    @BeforeEach
    void startModelServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            Map<String, Object> input = JSON.parseToMap(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String model = (String) input.get("model");
            boolean failed = "unavailable-model".equals(model);
            Map<String, Object> output = new java.util.LinkedHashMap<>();
            output.put("choices", List.of(Map.of("message", Map.of("content", "ok"))));
            if ("gpt-alias".equals(model)) output.put("model", "gpt-6.1-sol");
            byte[] body = JSON.toJSONString(output).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(failed ? 400 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties = new AiAgentLlmProperties();
        properties.setEnabled(true);
        properties.setMaxRetry(0);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("gpt-alias");
        AiAgentLlmProperties.Provider deepseek = new AiAgentLlmProperties.Provider();
        deepseek.setName("deepseek");
        deepseek.setBaseUrl(properties.getBaseUrl());
        deepseek.setModel("unavailable-model");
        properties.setProviders(List.of(deepseek));
        client = new AiAgentLlmClient(properties, new AiAgentRequestCancellationService());
    }

    @AfterEach
    void stopModelServer() {
        server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "text", "image"})
    void attributesFallbackToSuccessfulProviderAndReturnedModel(String mode) {
        AiAgentChatResponse response = new AiAgentChatResponse();
        try (AiAgentLlmUsage usage = AiAgentLlmUsage.begin()) {
            String answer = switch (mode) {
                case "json" -> client.chat("system", "question", "deepseek");
                case "image" -> client.analyzeImage("system", "question", new byte[]{1}, "image/png", "deepseek");
                default -> client.chatText("system", "question", "deepseek");
            };
            assertThat(answer).isEqualTo("ok");
            usage.applyTo(response);
        }
        assertThat(response.getLlmProvider()).isEqualTo("primary");
        assertThat(response.getLlmModel()).isEqualTo("gpt-6.1-sol");
    }

    @Test
    void freezesConfiguredModelWhenServerOmitsItAndDoesNotLeakIntoNextTurn() {
        properties.getProviders().getFirst().setModel("deepseek-flash");
        AiAgentChatResponse first = new AiAgentChatResponse();
        try (AiAgentLlmUsage usage = AiAgentLlmUsage.begin()) {
            client.chat("system", "question", "deepseek");
            usage.applyTo(first);
        }
        properties.getProviders().getFirst().setModel("future-model");
        AiAgentChatResponse second = new AiAgentChatResponse();
        try (AiAgentLlmUsage usage = AiAgentLlmUsage.begin()) {
            client.chatText("system", "question", "primary");
            usage.applyTo(second);
        }
        AiAgentChatResponse local = new AiAgentChatResponse();
        try (AiAgentLlmUsage usage = AiAgentLlmUsage.begin()) {
            usage.applyTo(local);
        }
        assertThat(first.getLlmProvider()).isEqualTo("deepseek");
        assertThat(first.getLlmModel()).isEqualTo("deepseek-flash");
        assertThat(second.getLlmModel()).isEqualTo("gpt-6.1-sol");
        assertThat(local.getLlmProvider()).isEqualTo("none");
        assertThat(local.getLlmModel()).isNull();
    }

    @Test
    void failedTurnDoesNotLeaveAttributionOnReusedWorkerThread() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertThatThrownBy(() -> {
                    try (AiAgentLlmUsage usage = AiAgentLlmUsage.begin()) {
                        client.chatText("system", "question", "primary");
                        throw new AiAgentRequestCancelledException();
                    }
                }).isInstanceOf(AiAgentRequestCancelledException.class);
            }).get();
            AiAgentChatResponse next = executor.submit(() -> {
                properties.setModel("unavailable-model");
                AiAgentChatResponse response = new AiAgentChatResponse();
                try (AiAgentLlmUsage usage = AiAgentLlmUsage.begin()) {
                    assertThatThrownBy(() -> client.chatText("system", "question", "primary"))
                            .isInstanceOf(RuntimeException.class);
                    usage.applyTo(response);
                }
                return response;
            }).get();
            assertThat(next.getLlmProvider()).isEqualTo("none");
            assertThat(next.getLlmModel()).isNull();
        }
    }

    @Test
    void parallelTurnsKeepTheirOwnModelAttribution() throws Exception {
        properties.getProviders().getFirst().setModel("deepseek-flash");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deepseek = executor.submit(() -> ask("deepseek"));
            var gpt = executor.submit(() -> ask("primary"));
            assertThat(deepseek.get().getLlmModel()).isEqualTo("deepseek-flash");
            assertThat(gpt.get().getLlmModel()).isEqualTo("gpt-6.1-sol");
        }
    }

    private AiAgentChatResponse ask(String provider) {
        try (AiAgentLlmUsage usage = AiAgentLlmUsage.begin()) {
            AiAgentChatResponse response = new AiAgentChatResponse();
            client.chatText("system", "question", provider);
            usage.applyTo(response);
            return response;
        }
    }
}
