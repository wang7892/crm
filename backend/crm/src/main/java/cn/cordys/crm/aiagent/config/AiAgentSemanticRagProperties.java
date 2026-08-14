package cn.cordys.crm.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crm.ai-agent.rag.semantic")
public class AiAgentSemanticRagProperties {

    private boolean enabled = true;
    private boolean shadowEnabled = false;
    private int maxRules = 3;
    private int maxContextChars = 4000;
}
