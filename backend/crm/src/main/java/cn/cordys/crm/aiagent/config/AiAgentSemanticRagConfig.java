package cn.cordys.crm.aiagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiAgentSemanticRagProperties.class)
public class AiAgentSemanticRagConfig {
}
