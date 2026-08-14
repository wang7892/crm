package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentKnowledgeParseConfig;
import cn.cordys.crm.aiagent.domain.AiKnowledgeParseJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;

@Service
@Slf4j
public class AiAgentKnowledgeParseWorker {

    private static final String LOCK_PREFIX = "ai-agent:knowledge-parse:";

    @Resource
    private AiAgentKnowledgeParsePersistenceService persistenceService;
    @Resource
    private AiAgentKnowledgeService knowledgeService;
    @Autowired(required = false)
    private Redisson redisson;

    @Async(AiAgentKnowledgeParseConfig.TASK_EXECUTOR)
    public void processAsync(String jobId) {
        process(jobId);
    }

    public void process(String jobId) {
        AiKnowledgeParseJob queuedJob = persistenceService.findJob(jobId);
        if (queuedJob == null || StringUtils.isBlank(queuedJob.getDocumentId())) {
            return;
        }
        if (redisson == null) {
            log.warn("知识文档解析锁服务不可用，任务保留等待恢复: jobId={}", jobId);
            return;
        }

        RLock lock;
        try {
            lock = redisson.getLock(LOCK_PREFIX + queuedJob.getDocumentId());
            if (!lock.tryLock()) {
                return;
            }
        } catch (RuntimeException e) {
            log.warn("获取知识文档解析锁失败，任务保留等待恢复: jobId={}, message={}", jobId, e.getMessage());
            return;
        }

        try {
            AiAgentKnowledgeParsePersistenceService.ClaimedParseJob claimed = persistenceService.claim(jobId);
            if (claimed == null) {
                return;
            }
            AiAgentKnowledgeService.PreparedParse prepared = knowledgeService.prepareParse(
                    claimed.document(),
                    claimed.job().getOrganizationId(),
                    StringUtils.defaultIfBlank(claimed.job().getUpdateUser(), claimed.job().getCreateUser()));
            persistenceService.completeAndActivate(jobId, prepared.chunks());
        } catch (Exception e) {
            log.warn("知识文档解析失败，任务将按重试策略处理: jobId={}, message={}", jobId, e.getMessage());
            try {
                persistenceService.retryOrFail(
                        jobId,
                        StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()),
                        stackTrace(e));
            } catch (RuntimeException transitionError) {
                log.error("更新知识文档解析失败状态异常: jobId={}", jobId, transitionError);
            }
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException e) {
                log.warn("释放知识文档解析锁失败: jobId={}, message={}", jobId, e.getMessage());
            }
        }
    }

    private String stackTrace(Exception e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
