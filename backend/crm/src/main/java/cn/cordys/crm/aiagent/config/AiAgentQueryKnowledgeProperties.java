package cn.cordys.crm.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crm.ai-agent.query-knowledge")
public class AiAgentQueryKnowledgeProperties {
    private boolean embeddingEnabled;
    private String embeddingUrl;
    private String embeddingModel;
    private String embeddingApiKey;
    private double similarity = 0.4;
    private int topCount = 5;
    private int maxContextChars = 24000;
    private int indexBatchSize = 10;
}
