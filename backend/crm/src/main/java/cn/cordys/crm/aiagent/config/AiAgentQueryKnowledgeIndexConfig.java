package cn.cordys.crm.aiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class AiAgentQueryKnowledgeIndexConfig {
    public static final String SCHEDULER = "aiAgentQueryKnowledgeIndexScheduler";

    @Bean(name = SCHEDULER)
    public ThreadPoolTaskScheduler queryKnowledgeIndexScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("query-knowledge-index-");
        return scheduler;
    }
}
