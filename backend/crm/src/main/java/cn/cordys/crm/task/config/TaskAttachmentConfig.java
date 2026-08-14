package cn.cordys.crm.task.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TaskAttachmentProperties.class)
public class TaskAttachmentConfig {
}
