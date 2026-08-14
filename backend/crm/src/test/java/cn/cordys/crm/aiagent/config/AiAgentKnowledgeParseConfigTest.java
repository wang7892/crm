package cn.cordys.crm.aiagent.config;

import cn.cordys.config.AsyncConfig;
import cn.cordys.config.ScheduleConfig;
import cn.cordys.crm.aiagent.service.AiAgentKnowledgeParseDispatcher;
import cn.cordys.crm.aiagent.service.AiAgentKnowledgeParseWorker;
import cn.cordys.crm.aiagent.service.AiKnowledgeParseRequestedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentKnowledgeParseConfigTest {

    @Test
    void shouldEnableAsyncSchedulingAndAfterCommitDispatch() throws Exception {
        assertThat(AsyncConfig.class).hasAnnotation(EnableAsync.class);
        assertThat(ScheduleConfig.class).hasAnnotation(EnableScheduling.class);

        Async async = AiAgentKnowledgeParseWorker.class
                .getMethod("processAsync", String.class)
                .getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo(AiAgentKnowledgeParseConfig.TASK_EXECUTOR);

        TransactionalEventListener listener = AiAgentKnowledgeParseDispatcher.class
                .getMethod("onParseRequested", AiKnowledgeParseRequestedEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void shouldCreateDedicatedBoundedExecutor() {
        AiAgentKnowledgeParseProperties properties = new AiAgentKnowledgeParseProperties();
        ThreadPoolTaskExecutor executor = new AiAgentKnowledgeParseConfig()
                .aiKnowledgeParseTaskExecutor(properties);
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("knowledge-parse-");
        } finally {
            executor.shutdown();
        }
    }
}
