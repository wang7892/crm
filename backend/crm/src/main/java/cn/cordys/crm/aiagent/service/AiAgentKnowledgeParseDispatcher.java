package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentKnowledgeParseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class AiAgentKnowledgeParseDispatcher {

    private final AiAgentKnowledgeParseWorker worker;
    private final AiAgentKnowledgeParsePersistenceService persistenceService;
    private final AiAgentKnowledgeParseProperties properties;
    private final AtomicBoolean scanning = new AtomicBoolean();

    public AiAgentKnowledgeParseDispatcher(AiAgentKnowledgeParseWorker worker,
                                           AiAgentKnowledgeParsePersistenceService persistenceService,
                                           AiAgentKnowledgeParseProperties properties) {
        this.worker = worker;
        this.persistenceService = persistenceService;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParseRequested(AiKnowledgeParseRequestedEvent event) {
        dispatch(event.jobId());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        recoverAndDispatch();
    }

    @Scheduled(
            initialDelayString = "${crm.ai-agent.knowledge.parse.initial-delay-ms:10000}",
            fixedDelayString = "${crm.ai-agent.knowledge.parse.scan-delay-ms:30000}")
    public void recoverAndDispatch() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            long staleBefore = System.currentTimeMillis() - Math.max(1000L, properties.getStaleTimeoutMs());
            for (String jobId : persistenceService.listStaleRunningJobIds(staleBefore)) {
                try {
                    persistenceService.recoverStaleJob(jobId, staleBefore);
                } catch (RuntimeException e) {
                    log.error("恢复超时知识文档解析任务失败: jobId={}", jobId, e);
                }
            }
            for (String jobId : persistenceService.listPendingJobIds()) {
                dispatch(jobId);
            }
        } catch (RuntimeException e) {
            log.error("扫描知识文档解析任务失败", e);
        } finally {
            scanning.set(false);
        }
    }

    private void dispatch(String jobId) {
        try {
            worker.processAsync(jobId);
        } catch (TaskRejectedException e) {
            log.warn("知识文档解析线程池已满，任务保留等待恢复: jobId={}", jobId);
        } catch (RuntimeException e) {
            log.error("投递知识文档解析任务失败，任务保留等待恢复: jobId={}", jobId, e);
        }
    }
}
