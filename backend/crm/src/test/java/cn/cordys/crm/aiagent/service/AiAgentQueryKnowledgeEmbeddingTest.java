package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentQueryKnowledgeProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class AiAgentQueryKnowledgeEmbeddingTest {
    @Test void backendPropertiesControlSemanticRetrievalForEveryOrganization() {
        var source = new MapConfigurationPropertySource(Map.of(
                "crm.ai-agent.query-knowledge.embedding-enabled", "true",
                "crm.ai-agent.query-knowledge.embedding-url", "http://localhost:11434/v1/embeddings",
                "crm.ai-agent.query-knowledge.embedding-model", "bge-m3",
                "crm.ai-agent.query-knowledge.similarity", "0.6",
                "crm.ai-agent.query-knowledge.top-count", "3"));
        var config = new Binder(source).bind("crm.ai-agent.query-knowledge", AiAgentQueryKnowledgeProperties.class).get();
        var embedding = new AiAgentQueryKnowledgeEmbedding(config);
        assertThat(embedding.key("org-a")).isNotNull().isEqualTo(embedding.key("org-b"));
        assertThat(embedding.threshold("org-a")).isEqualTo(0.6);
        assertThat(embedding.topCount("org-b")).isEqualTo(3);
        config.setEmbeddingEnabled(false);
        assertThat(embedding.key("org-a")).isNull();
        assertThat(embedding.embed("印度有哪些客户", "org-a")).isEmpty();
    }

    @Test void disabledOrIncompleteConfigurationDoesNotProduceVectors() {
        var config = new AiAgentQueryKnowledgeProperties(); var embedding = new AiAgentQueryKnowledgeEmbedding(config);
        assertThat(embedding.key()).isNull(); assertThat(embedding.embed("品种")).isEmpty();
        config.setEmbeddingEnabled(true); config.setEmbeddingUrl(""); config.setEmbeddingModel("test");
        assertThat(embedding.key()).isNull();
    }

    @Test void acceptsOpenAiEmbeddingProtocolWithoutStoringCredentialsInFingerprint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var request = new AtomicReference<String>(); var auth = new AtomicReference<String>();
        server.createContext("/embeddings", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try {
            var config = new AiAgentQueryKnowledgeProperties(); config.setEmbeddingEnabled(true);
            config.setEmbeddingUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings");
            config.setEmbeddingModel("test-model"); config.setEmbeddingApiKey("local-test-key");
            var embedding = new AiAgentQueryKnowledgeEmbedding(config); String key = embedding.key();
            assertThat(embedding.embed("品种")).containsExactly(0.1, 0.2, 0.3);
            assertThat(request.get()).contains("test-model", "品种"); assertThat(auth.get()).isEqualTo("Bearer local-test-key");
            config.setEmbeddingApiKey("other-test-key"); assertThat(embedding.key()).isEqualTo(key);
            config.setEmbeddingModel("other-model"); assertThat(embedding.key()).isNotEqualTo(key);
        } finally { server.stop(0); }
    }

    @Test void failuresAndInvalidVectorsFallBackWithoutThrowing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/failure", e -> { e.sendResponseHeaders(401, -1); e.close(); });
        server.createContext("/invalid", e -> {
            byte[] body = "{\"data\":[{\"embedding\":[\"not-a-number\"]}]}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        }); server.start();
        try {
            var config = new AiAgentQueryKnowledgeProperties(); config.setEmbeddingEnabled(true); config.setEmbeddingModel("test");
            var embedding = new AiAgentQueryKnowledgeEmbedding(config);
            for (String path : List.of("failure", "invalid")) {
                config.setEmbeddingUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/" + path);
                assertThat(embedding.embed("品种")).isEmpty();
            }
        } finally { server.stop(0); }
    }

    @Test void cosineSimilarityHandlesEmptyZeroAndMismatchedVectors() {
        assertThat(AiAgentQueryKnowledgeEmbedding.similarity(List.of(1.0, 0.0), List.of(1.0, 0.0))).isEqualTo(1.0);
        assertThat(AiAgentQueryKnowledgeEmbedding.similarity(List.of(1.0), List.of(-1.0))).isEqualTo(-1.0);
        assertThat(AiAgentQueryKnowledgeEmbedding.similarity(List.of(0.0), List.of(1.0))).isEqualTo(-1.0);
        assertThat(AiAgentQueryKnowledgeEmbedding.similarity(List.of(1.0), List.of(1.0, 0.0))).isEqualTo(-1.0);
    }
}
