package cn.cordys.crm.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "crm.ai-agent.llm")
public class AiAgentLlmProperties {
    private boolean enabled = false;
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeoutSeconds = 10;
    private double minConfidence = 0.75;
    private int maxInputLength = 1000;
    private int maxRetry = 1;
    private List<Provider> providers = new ArrayList<>();

    @Data
    public static class Provider {
        private boolean enabled = true;
        private String name;
        private String baseUrl;
        private String chatCompletionsPath;
        private String apiKey;
        private boolean apiKeyRequired = false;
        private String model;
        private Integer timeoutSeconds;
        private Integer maxRetry;
    }
}
