package cn.cordys.crm.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crm.ai-agent.conversation")
public class AiAgentConversationProperties {
    private int maxHistoryMessages = 20;
    private int maxHistoryChars = 16000;
    private int maxDataChars = 48000;
    private int maxQueryRows = 100;
}
