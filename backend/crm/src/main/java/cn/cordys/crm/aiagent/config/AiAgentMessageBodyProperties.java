package cn.cordys.crm.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "crm.ai-agent.message-body")
public class AiAgentMessageBodyProperties {
    private boolean enabled = false;
    private List<String> allowedRoles = List.of("admin", "boss", "sales_manager");
    private int maxRows = 20;
    private boolean maskSensitive = true;
    private boolean auditEnabled = true;
}
