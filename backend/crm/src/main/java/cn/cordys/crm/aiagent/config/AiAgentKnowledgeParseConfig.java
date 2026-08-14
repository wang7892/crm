package cn.cordys.crm.aiagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiAgentKnowledgeParseProperties.class)
public class AiAgentKnowledgeParseConfig {

    public static final String TASK_EXECUTOR = "aiKnowledgeParseTaskExecutor";

    @Bean(name = TASK_EXECUTOR)
    public ThreadPoolTaskExecutor aiKnowledgeParseTaskExecutor(AiAgentKnowledgeParseProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, properties.getCorePoolSize()));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(), properties.getMaxPoolSize()));
        executor.setQueueCapacity(Math.max(1, properties.getQueueCapacity()));
        executor.setThreadNamePrefix("knowledge-parse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
