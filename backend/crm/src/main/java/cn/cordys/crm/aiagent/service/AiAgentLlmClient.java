package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
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
                String content = chatWithProvider(provider, systemPrompt, userPrompt);
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
        return chatPlainContent(systemPrompt, userPrompt, preferredProvider);
    }

    public String analyzeImage(String systemPrompt, String userPrompt, byte[] imageBytes,
                               String mediaType, String preferredProvider) {
        String dataUrl = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        List<Map<String, Object>> content = List.of(
                Map.of("type", "text", "text", userPrompt),
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
        );
        return chatPlainContent(systemPrompt, content, preferredProvider);
    }

    private String chatPlainContent(String systemPrompt, Object userContent, String preferredProvider) {
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
                String content = chatPlainWithProvider(provider, systemPrompt, userContent);
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

    private String chatWithProvider(LlmProvider provider, String systemPrompt, String userPrompt) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, provider.timeoutSeconds)))
                .build();
        int attempts = Math.max(1, provider.maxRetry + 1);
        RuntimeException lastException = null;

        for (boolean jsonFormat : List.of(true, false)) {
            for (int index = 0; index < attempts; index++) {
                aiAgentRequestCancellationService.throwIfCancellationRequested();
                try {
                    HttpRequest request = buildRequest(provider, systemPrompt, userPrompt, jsonFormat);
                    log.debug("AI agent LLM request: provider={}, model={}, jsonFormat={}, attempt={}",
                            provider.name, provider.model, jsonFormat, index + 1);
                    HttpResponse<String> response = send(client, request);
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        lastException = new IllegalStateException("LLM request failed, provider=" + provider.name
                                + ", status=" + response.statusCode() + ", jsonFormat=" + jsonFormat);
                        continue;
                    }
                    return extractMessageContent(response.body());
                } catch (AiAgentRequestCancelledException e) {
                    throw e;
                } catch (Exception e) {
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

    private String chatPlainWithProvider(LlmProvider provider, String systemPrompt, Object userContent) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, provider.timeoutSeconds)))
                .build();
        int attempts = Math.max(1, provider.maxRetry + 1);
        RuntimeException lastException = null;

        for (int index = 0; index < attempts; index++) {
            aiAgentRequestCancellationService.throwIfCancellationRequested();
            try {
                HttpRequest request = buildRequest(provider, systemPrompt, userContent, false);
                log.debug("AI agent LLM plain request: provider={}, model={}, attempt={}",
                        provider.name, provider.model, index + 1);
                HttpResponse<String> response = send(client, request);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    lastException = new IllegalStateException("LLM plain request failed, provider=" + provider.name
                            + ", status=" + response.statusCode());
                    continue;
                }
                return extractMessageContent(response.body());
            } catch (AiAgentRequestCancelledException e) {
                throw e;
            } catch (Exception e) {
                lastException = new RuntimeException("LLM plain request failed, provider=" + provider.name, e);
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return null;
    }

    private HttpRequest buildRequest(LlmProvider provider, String systemPrompt, Object userContent, boolean jsonFormat) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", provider.model);
        requestBody.put("temperature", 0);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        ));
        if (jsonFormat) {
            requestBody.put("response_format", Map.of("type", "json_object"));
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatCompletionsUrl(provider.baseUrl, provider.chatCompletionsPath)))
                .timeout(Duration.ofSeconds(Math.max(1, provider.timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(requestBody)));
        if (StringUtils.isNotBlank(provider.apiKey)) {
            requestBuilder.header("Authorization", "Bearer " + provider.apiKey);
        }
        return requestBuilder.build();
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
                properties.getModel(), properties.getTimeoutSeconds(), properties.getMaxRetry()));
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
                provider.getMaxRetry() == null ? properties.getMaxRetry() : provider.getMaxRetry()
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

    private String extractMessageContent(String responseBody) {
        Map<String, Object> body = JSON.parseToMap(responseBody);
        Object choicesObject = body.get("choices");
        if (!(choicesObject instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }
        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choiceMap)) {
            return null;
        }
        Object messageObject = choiceMap.get("message");
        if (messageObject instanceof Map<?, ?> messageMap) {
            return StringUtils.defaultString((String) messageMap.get("content"));
        }
        Object text = choiceMap.get("text");
        return text == null ? null : String.valueOf(text);
    }

    private static class LlmProvider {
        private final String name;
        private final String baseUrl;
        private final String chatCompletionsPath;
        private final String apiKey;
        private final String model;
        private final int timeoutSeconds;
        private final int maxRetry;

        private LlmProvider(String name, String baseUrl, String chatCompletionsPath, String apiKey, String model,
                            int timeoutSeconds, int maxRetry) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.chatCompletionsPath = chatCompletionsPath;
            this.apiKey = apiKey;
            this.model = model;
            this.timeoutSeconds = timeoutSeconds;
            this.maxRetry = maxRetry;
        }
    }
}
