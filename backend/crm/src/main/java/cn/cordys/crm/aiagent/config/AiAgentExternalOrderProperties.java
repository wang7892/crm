package cn.cordys.crm.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crm.ai-agent.external-order")
public class AiAgentExternalOrderProperties {
    private boolean enabled = false;
    private String url;
    private String username;
    private String password;
    private int maximumPoolSize = 3;
    private long connectionTimeout = 5000;
}
