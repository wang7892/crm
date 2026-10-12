package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.dto.AiAgentConversationMessage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Service
public class AiAgentLlmClient {

    private static final Logger log = LoggerFactory.getLogger(AiAgentLlmClient.class);

    private final AiAgentLlmProperties properties;
    private final AiAgentRequestCancellationService aiAgentRequestCancellationService;

    public AiAgentLlmClient(AiAgentLlmProperties properties,
                            AiAgentRequestCancellationService aiAgentRequestCancellationService) {
        this.properties = properties;
        this.aiAgentRequestCancellationService = aiAgentRequestCancellationService;
    }

    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, null);
    }

    public String chat(String systemPrompt, String userPrompt, String preferredProvider) {
        return chat(systemPrompt, userPrompt, preferredProvider, List.of());
    }

    public String chat(String systemPrompt, String userPrompt, String preferredProvider,
                       List<AiAgentConversationMessage> history) {
        if (!properties.isEnabled()) {
            return null;
        }
        List<LlmProvider> providers = resolveProviders(preferredProvider);
        if (providers.isEmpty()) {
            return null;
        }

        RuntimeException lastException = null;
        for (LlmProvider provider : providers) {
            aiAgentRequestCancellationService.throwIfCancellationRequested();
            try {
                String content = chatWithProvider(provider, systemPrompt, userPrompt, history);
                if (StringUtils.isNotBlank(content)) {
                    return content;
                }
            } catch (RuntimeException e) {
                if (e instanceof AiAgentRequestCancelledException) {
                    throw e;
                }
                lastException = e;
                log.warn("AI agent LLM provider failed, provider={}, error={}", provider.name, e.toString());
                log.debug("AI agent LLM provider failure detail", e);
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return null;
    }

    public String chatText(String systemPrompt, String userPrompt, String preferredProvider) {
        return chatText(systemPrompt, userPrompt, preferredProvider, List.of());
    }

    public String chatText(String systemPrompt, String userPrompt, String preferredProvider,
                           List<AiAgentConversationMessage> history) {
        return chatPlainContent(systemPrompt, userPrompt, preferredProvider, history);
    }

    public String chatTextQuiet(String systemPrompt, String userPrompt, String provider, List<AiAgentConversationMessage> history) {
        return chatPlainContent(systemPrompt, userPrompt, provider, history, false);
    }

    public String analyzeImage(String systemPrompt, String userPrompt, byte[] imageBytes,
                               String mediaType, String preferredProvider) {
        String dataUrl = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        List<Map<String, Object>> content = List.of(
                Map.of("type", "text", "text", userPrompt),
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
        );
        return chatPlainContent(systemPrompt, content, preferredProvider, List.of());
    }

    private String chatPlainContent(String systemPrompt, Object userContent, String preferredProvider,
                                    List<AiAgentConversationMessage> history) {
        return chatPlainContent(systemPrompt, userContent, preferredProvider, history, true);
    }

    private String chatPlainContent(String systemPrompt, Object userContent, String preferredProvider,
                                    List<AiAgentConversationMessage> history, boolean allowStream) {
        if (!properties.isEnabled()) {
            return null;
        }
        List<LlmProvider> providers = resolveProviders(preferredProvider);
        if (providers.isEmpty()) {
            return null;
        }

        RuntimeException lastException = null;
        for (LlmProvider provider : providers) {
            aiAgentRequestCancellationService.throwIfCancellationRequested();
            try {
                String content = (allowStream || AiAgentLlmUsage.reasoningEnabled()) && AiAgentProgress.streaming() && userContent instanceof String
                        ? chatStreamWithProvider(provider, systemPrompt, userContent, history, false, allowStream)
                        : chatPlainWithProvider(provider, systemPrompt, userContent, history);
                if (StringUtils.isNotBlank(content)) {
                    return content;
                }
            } catch (RuntimeException e) {
                if (e instanceof AiAgentRequestCancelledException) {
                    throw e;
                }
                lastException = e;
                log.warn("AI agent LLM plain request failed, provider={}, error={}", provider.name, e.toString());
                log.debug("AI agent LLM plain request failure detail", e);
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return null;
    }

    private String chatWithProvider(LlmProvider provider, String systemPrompt, String userPrompt,
                                    List<AiAgentConversationMessage> history) {
        if (AiAgentProgress.streaming() && AiAgentLlmUsage.reasoningEnabled())
            return chatStreamWithProvider(provider, systemPrompt, userPrompt, history, true, false);
        return chatJsonWithProvider(provider, systemPrompt, userPrompt, history);
    }

    private String chatJsonWithProvider(LlmProvider provider, String systemPrompt, String userPrompt,
                                        List<AiAgentConversationMessage> history) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, provider.timeoutSeconds)))
                .build();
        int attempts = Math.max(1, provider.maxRetry + 1);
        RuntimeException lastException = null;

        for (boolean jsonFormat : List.of(true, false)) {
            for (int index = 0; index < attempts; index++) {
                aiAgentRequestCancellationService.throwIfCancellationRequested();
                try {
                    HttpRequest request = buildRequest(provider, systemPrompt, userPrompt, jsonFormat, history);
                    log.debug("AI agent LLM request: provider={}, model={}, jsonFormat={}, attempt={}",
                            provider.name, provider.model, jsonFormat, index + 1);
                    HttpResponse<String> response = send(client, request);
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        AiAgentLlmUsage.failed(provider.name, provider.model);
                        lastException = new IllegalStateException("LLM request failed, provider=" + provider.name
                                + ", status=" + response.statusCode() + ", jsonFormat=" + jsonFormat
                                + ", body=" + StringUtils.abbreviate(StringUtils.defaultString(response.body()), 1000));
                        continue;
                    }
                    return extractMessageContent(provider, response.body());
                } catch (AiAgentRequestCancelledException e) {
                    throw e;
                } catch (Exception e) {
                    AiAgentLlmUsage.failed(provider.name, provider.model);
                    lastException = new RuntimeException("LLM request failed, provider=" + provider.name
                            + ", jsonFormat=" + jsonFormat, e);
                }
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return null;
    }

    private String chatPlainWithProvider(LlmProvider provider, String systemPrompt, Object userContent,
                                         List<AiAgentConversationMessage> history) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, provider.timeoutSeconds)))
                .build();
        int attempts = Math.max(1, provider.maxRetry + 1);
        RuntimeException lastException = null;

        for (int index = 0; index < attempts; index++) {
            aiAgentRequestCancellationService.throwIfCancellationRequested();
            try {
                HttpRequest request = buildRequest(provider, systemPrompt, userContent, false, history);
                log.debug("AI agent LLM plain request: provider={}, model={}, attempt={}",
                        provider.name, provider.model, index + 1);
                HttpResponse<String> response = send(client, request);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    AiAgentLlmUsage.failed(provider.name, provider.model);
                    lastException = new IllegalStateException("LLM plain request failed, provider=" + provider.name
                            + ", status=" + response.statusCode()
                            + ", body=" + StringUtils.abbreviate(StringUtils.defaultString(response.body()), 1000));
                    continue;
                }
                return extractMessageContent(provider, response.body());
            } catch (AiAgentRequestCancelledException e) {
                throw e;
            } catch (Exception e) {
                AiAgentLlmUsage.failed(provider.name, provider.model);
                lastException = new RuntimeException("LLM plain request failed, provider=" + provider.name, e);
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return null;
    }

    private HttpRequest buildRequest(LlmProvider provider, String systemPrompt, Object userContent, boolean jsonFormat,
                                     List<AiAgentConversationMessage> history) {
        return buildRequest(provider, systemPrompt, userContent, jsonFormat, history, false);
    }

    private HttpRequest buildRequest(LlmProvider provider, String systemPrompt, Object userContent, boolean jsonFormat,
                                     List<AiAgentConversationMessage> history, boolean stream) {
        AiAgentLlmUsage.startCall();
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", provider.model);
        if (!provider.responses()) requestBody.put("temperature", 0);
        if (stream) {
            requestBody.put("stream", true);
            if (!provider.responses()) requestBody.put("stream_options", Map.of("include_usage", true));
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            for (AiAgentConversationMessage message : history) {
                if (message != null && ("user".equals(message.role()) || "assistant".equals(message.role()))
                        && StringUtils.isNotBlank(message.content())) {
                    messages.add(Map.of("role", message.role(), "content", message.content()));
                }
            }
        }
        messages.add(Map.of("role", "user", "content", provider.responses() ? responsesInput(userContent) : userContent));
        requestBody.put(provider.responses() ? "input" : "messages", messages);
        if (jsonFormat) {
            if (provider.responses()) requestBody.put("text", Map.of("format", Map.of("type", "json_object")));
            else requestBody.put("response_format", Map.of("type", "json_object"));
        }
        if (provider.responses()) {
            requestBody.put("store", false);
            if (AiAgentLlmUsage.reasoningEnabled())
                requestBody.put("reasoning", Map.of("effort", "medium", "summary", "auto"));
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(provider.responses() ? resolveResponsesUrl(provider.baseUrl)
                        : resolveChatCompletionsUrl(provider.baseUrl, provider.chatCompletionsPath)))
                .timeout(Duration.ofSeconds(Math.max(1, provider.timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(requestBody)));
        if (StringUtils.isNotBlank(provider.apiKey)) {
            requestBuilder.header("Authorization", "Bearer " + provider.apiKey);
        }
        return requestBuilder.build();
    }

    private Object responsesInput(Object content) {
        if (!(content instanceof List<?> parts)) return content;
        var converted = new ArrayList<Map<String, Object>>();
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> value)) continue;
            if ("text".equals(value.get("type")))
                converted.add(Map.of("type", "input_text", "text", value.get("text")));
            if ("image_url".equals(value.get("type")) && value.get("image_url") instanceof Map<?, ?> image)
                converted.add(Map.of("type", "input_image", "image_url", image.get("url")));
        }
        return converted;
    }

    private String chatStreamWithProvider(LlmProvider provider, String systemPrompt, Object userContent,
                                          List<AiAgentConversationMessage> history, boolean jsonFormat, boolean emitAnswer) {
        if (emitAnswer) AiAgentProgress.send("reset", Map.of());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, provider.timeoutSeconds))).build();
        var pending = aiAgentRequestCancellationService.trackLlmRequest(client.sendAsync(
                buildRequest(provider, systemPrompt, userContent, jsonFormat, history, true), HttpResponse.BodyHandlers.ofInputStream()));
        java.io.InputStream body = null;
        try {
            var response = pending.join(); body = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Stream provider unavailable");
            aiAgentRequestCancellationService.trackStream(body);
            var done = new java.util.concurrent.atomic.AtomicBoolean();
            final var input = body;
            CompletableFuture.delayedExecutor(Math.max(1, provider.timeoutSeconds), java.util.concurrent.TimeUnit.SECONDS)
                    .execute(() -> { if (!done.get()) try { input.close(); } catch (java.io.IOException ignored) { } });
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
                if (!StringUtils.containsIgnoreCase(response.headers().firstValue("Content-Type").orElse(""), "text/event-stream")) {
                    String json = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                    String content = extractMessageContent(provider, json);
                    if (emitAnswer && content != null) AiAgentProgress.delta(content);
                    return content;
                }
                var content = new AiAgentLlmContent(text -> { if (emitAnswer) AiAgentProgress.delta(text); }, AiAgentLlmUsage::reasoning);
                var responses = new AiAgentResponsesContent(content);
                StringBuilder event = new StringBuilder();
                String model = provider.model;
                Object tokens = null;
                boolean completed = false;
                for (String line; (line = reader.readLine()) != null;) {
                    aiAgentRequestCancellationService.throwIfCancellationRequested();
                    if (line.length() > 200_000 || event.length() > 200_000)
                        throw new IllegalStateException("Stream content too large");
                    if (line.startsWith("data:")) {
                        if (!event.isEmpty()) event.append('\n');
                        event.append(line.substring(5).stripLeading());
                    } else if (line.isEmpty() && !event.isEmpty()) {
                        String data = event.toString(); event.setLength(0);
                        if ("[DONE]".equals(data)) { completed = true; break; }
                        Map<String, Object> root = JSON.parseToMap(data);
                        if (provider.responses()) {
                            responses.accept(root);
                            if (responses.completed()) break;
                            continue;
                        }
                        if (root.get("usage") != null) tokens = root.get("usage");
                        if (root.get("model") instanceof String actual && !actual.isBlank()) model = actual;
                        if (root.get("choices") instanceof List<?> choices && !choices.isEmpty()
                                && choices.getFirst() instanceof Map<?, ?> choice) {
                            if (choice.get("finish_reason") != null) completed = true;
                            if (choice.get("delta") instanceof Map<?, ?> delta)
                                content.accept(delta.get("content"), delta.get("reasoning_content"));
                        }
                    }
                }
                content.finish();
                if (!(provider.responses() ? responses.completed() : completed) || content.answer().isBlank())
                    throw new IllegalStateException("Incomplete model stream");
                if (provider.responses()) { model = responses.model(provider.model); tokens = responses.usage(); }
                AiAgentLlmUsage.record(provider.name, model, tokens);
                return content.answer();
            } finally { done.set(true); }
        } catch (CancellationException e) { throw new AiAgentRequestCancelledException();
        } catch (AiAgentRequestCancelledException e) { throw e;
        } catch (Exception e) {
            AiAgentLlmUsage.failed(provider.name, provider.model);
            aiAgentRequestCancellationService.throwIfCancellationRequested();
            if (emitAnswer) AiAgentProgress.send("reset", Map.of());
            // Providers without SSE support retain the existing plain-text request path.
            return jsonFormat ? chatJsonWithProvider(provider, systemPrompt, (String) userContent, history)
                    : chatPlainWithProvider(provider, systemPrompt, userContent, history);
        } finally {
            aiAgentRequestCancellationService.clearLlmRequest(pending);
            aiAgentRequestCancellationService.clearStream(body);
            if (body != null) try { body.close(); } catch (java.io.IOException ignored) { }
        }
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) {
        CompletableFuture<HttpResponse<String>> llmRequest = aiAgentRequestCancellationService.trackLlmRequest(
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        try {
            return llmRequest.join();
        } catch (CancellationException e) {
            throw new AiAgentRequestCancelledException();
        } catch (CompletionException e) {
            if (e.getCause() instanceof CancellationException) {
                throw new AiAgentRequestCancelledException();
            }
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e.getCause());
        } finally {
            aiAgentRequestCancellationService.clearLlmRequest(llmRequest);
        }
    }

    private List<LlmProvider> resolveProviders(String preferredProvider) {
        List<LlmProvider> providers = new ArrayList<>();
        addLegacyProvider(providers);
        if (properties.getProviders() != null) {
            for (AiAgentLlmProperties.Provider provider : properties.getProviders()) {
                addConfiguredProvider(providers, provider);
            }
        }
        prioritizeProvider(providers, preferredProvider);
        return providers;
    }

    private void prioritizeProvider(List<LlmProvider> providers, String preferredProvider) {
        String preferredName = StringUtils.trimToNull(preferredProvider);
        if (preferredName == null || providers.size() <= 1) {
            return;
        }
        for (int index = 0; index < providers.size(); index++) {
            if (StringUtils.equalsIgnoreCase(providers.get(index).name, preferredName)) {
                providers.add(0, providers.remove(index));
                return;
            }
        }
    }

    private void addLegacyProvider(List<LlmProvider> providers) {
        if (StringUtils.isAnyBlank(properties.getBaseUrl(), properties.getModel())) {
            return;
        }
        providers.add(new LlmProvider("primary", properties.getBaseUrl(), null, properties.getApiKey(),
                properties.getModel(), properties.getTimeoutSeconds(), properties.getMaxRetry(), properties.getApiType()));
    }

    private void addConfiguredProvider(List<LlmProvider> providers, AiAgentLlmProperties.Provider provider) {
        if (provider == null || !provider.isEnabled()) {
            return;
        }
        String name = StringUtils.defaultIfBlank(provider.getName(), "provider-" + providers.size());
        if (StringUtils.isAnyBlank(provider.getBaseUrl(), provider.getModel())) {
            log.warn("AI agent LLM provider skipped because baseUrl or model is blank, provider={}", name);
            return;
        }
        if (provider.isApiKeyRequired() && StringUtils.isBlank(provider.getApiKey())) {
            log.warn("AI agent LLM provider skipped because apiKey is blank, provider={}", name);
            return;
        }
        providers.add(new LlmProvider(
                name,
                provider.getBaseUrl(),
                provider.getChatCompletionsPath(),
                provider.getApiKey(),
                provider.getModel(),
                provider.getTimeoutSeconds() == null ? properties.getTimeoutSeconds() : provider.getTimeoutSeconds(),
                provider.getMaxRetry() == null ? properties.getMaxRetry() : provider.getMaxRetry(),
                provider.getApiType()
        ));
    }

    private String resolveChatCompletionsUrl(String baseUrl, String chatCompletionsPath) {
        String url = StringUtils.removeEnd(StringUtils.defaultString(baseUrl).trim(), "/");
        String path = StringUtils.trimToNull(chatCompletionsPath);
        if (path != null) {
            return url + (path.startsWith("/") ? path : "/" + path);
        }
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        if (url.endsWith("/v1")) {
            return url + "/chat/completions";
        }
        return url + "/v1/chat/completions";
    }

    private String resolveResponsesUrl(String baseUrl) {
        String url = StringUtils.removeEnd(StringUtils.defaultString(baseUrl).trim(), "/");
        if (url.endsWith("/responses")) return url;
        url = StringUtils.removeEnd(url, "/chat/completions");
        return url + (url.endsWith("/v1") ? "/responses" : "/v1/responses");
    }

    private String extractMessageContent(LlmProvider provider, String responseBody) {
        Map<String, Object> body = JSON.parseToMap(responseBody);
        if (provider.responses()) {
            var reasoning = new StringBuilder();
            var content = new AiAgentLlmContent(text -> {}, reasoning::append);
            var responses = new AiAgentResponsesContent(content);
            responses.accept(body);
            content.finish();
            if (!responses.completed()) throw new IllegalStateException("Invalid model Responses response");
            if (!content.answer().isBlank()) {
                AiAgentLlmUsage.reasoning(reasoning.toString());
                AiAgentLlmUsage.record(provider.name, responses.model(provider.model), responses.usage());
            }
            return content.answer();
        }
        Object choicesObject = body.get("choices");
        if (!(choicesObject instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }
        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choiceMap)) {
            return null;
        }
        Object messageObject = choiceMap.get("message");
        var reasoning = new StringBuilder();
        var parsed = new AiAgentLlmContent(text -> {}, reasoning::append);
        if (messageObject instanceof Map<?, ?> messageMap) {
            parsed.accept(messageMap.get("content"), messageMap.get("reasoning_content"));
        } else {
            Object text = choiceMap.get("text");
            parsed.accept(text == null ? null : String.valueOf(text), null);
        }
        parsed.finish();
        String content = parsed.answer();
        if (StringUtils.isNotBlank(content)) {
            String model = body.get("model") instanceof String returnedModel
                    ? StringUtils.defaultIfBlank(returnedModel.trim(), provider.model) : provider.model;
            AiAgentLlmUsage.reasoning(reasoning.toString());
            AiAgentLlmUsage.record(provider.name, model, body.get("usage"));
        }
        return content;
    }

    private static class LlmProvider {
        private final String name;
        private final String baseUrl;
        private final String chatCompletionsPath;
        private final String apiKey;
        private final String model;
        private final int timeoutSeconds;
        private final int maxRetry;
        private final String apiType;

        private LlmProvider(String name, String baseUrl, String chatCompletionsPath, String apiKey, String model,
                            int timeoutSeconds, int maxRetry, String apiType) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.chatCompletionsPath = chatCompletionsPath;
            this.apiKey = apiKey;
            this.model = model;
            this.timeoutSeconds = timeoutSeconds;
            this.maxRetry = maxRetry;
            this.apiType = apiType;
        }
        private boolean responses() { return "responses".equalsIgnoreCase(apiType); }
    }
}
