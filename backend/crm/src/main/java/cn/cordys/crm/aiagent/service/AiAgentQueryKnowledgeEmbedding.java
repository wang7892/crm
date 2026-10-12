package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentQueryKnowledgeProperties;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@Service
public class AiAgentQueryKnowledgeEmbedding {
    private final AiAgentQueryKnowledgeProperties properties;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AiAgentQueryKnowledgeEmbedding(AiAgentQueryKnowledgeProperties properties) { this.properties = properties; }

    public String key() {
        if (!properties.isEmbeddingEnabled() || properties.getEmbeddingUrl() == null || properties.getEmbeddingUrl().isBlank()
                || properties.getEmbeddingModel() == null || properties.getEmbeddingModel().isBlank()) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (properties.getEmbeddingUrl() + "\n" + properties.getEmbeddingModel()).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public String key(String org) { return key(); }
    public double threshold(String org) { return properties.getSimilarity(); }
    public int topCount(String org) { return properties.getTopCount(); }
    public List<Double> embed(String text, String org) { return embed(text); }

    public List<Double> embed(String text) {
        var values = embedBatch(List.of(text), null);
        return values.isEmpty() ? List.of() : values.getFirst();
    }

    public List<List<Double>> embedBatch(List<String> texts, String org) {
        if (key() == null) return List.of();
        try {
            URI uri = URI.create(properties.getEmbeddingUrl());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                return List.of();
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of(
                            "model", properties.getEmbeddingModel(), "input", texts))));
            if (properties.getEmbeddingApiKey() != null && !properties.getEmbeddingApiKey().isBlank())
                builder.header("Authorization", "Bearer " + properties.getEmbeddingApiKey());
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 4_000_000) return List.of();
            var root = JSON.parseObject(response.body(), Map.class);
            var data = (List<?>) root.get("data");
            if (data.size() != texts.size()) return List.of();
            List<List<Double>> result = new ArrayList<>(Collections.nCopies(texts.size(), List.of()));
            for (int i = 0; i < data.size(); i++) {
                var item = (Map<?, ?>) data.get(i);
                int index = item.get("index") instanceof Number n ? n.intValue() : i;
                var values = (List<?>) item.get("embedding");
                if (index < 0 || index >= texts.size() || values.isEmpty() || values.size() > 4096) return List.of();
                List<Double> vector = values.stream().map(v -> ((Number) v).doubleValue()).toList();
                if (vector.stream().anyMatch(v -> !Double.isFinite(v))) return List.of();
                result.set(index, vector);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            // Matching remains available without sending provider errors or credentials to clients.
            return List.of();
        }
    }

    static double similarity(List<Double> a, List<Double> b) {
        if (a.isEmpty() || a.size() != b.size()) return -1;
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i); aa += a.get(i) * a.get(i); bb += b.get(i) * b.get(i);
        }
        return aa == 0 || bb == 0 ? -1 : dot / Math.sqrt(aa * bb);
    }
}
