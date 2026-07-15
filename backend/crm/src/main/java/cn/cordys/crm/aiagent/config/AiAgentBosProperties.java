package cn.cordys.crm.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crm.ai-agent.bos")
public class AiAgentBosProperties {
    private String endpoint = "https://bj.bcebos.com";
    private String bucket;
    private String accessKeyId;
    private String secretAccessKey;
    private String objectPrefix = "crm-audio/";
    private int signedUrlExpireMinutes = 1440;
    private int uploadTimeoutSeconds = 300;
}
