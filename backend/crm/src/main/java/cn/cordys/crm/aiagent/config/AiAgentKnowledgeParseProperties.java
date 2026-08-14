package cn.cordys.crm.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crm.ai-agent.knowledge.parse")
public class AiAgentKnowledgeParseProperties {

    private int maxRetries = 3;
    private long staleTimeoutMs = 10 * 60 * 1000L;
    private int recoveryBatchSize = 20;
    private int corePoolSize = 2;
    private int maxPoolSize = 4;
    private int queueCapacity = 100;
}
