package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentKnowledgeParseProperties;
import cn.cordys.crm.aiagent.domain.AiKnowledgeChunk;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.domain.AiKnowledgeParseJob;
import cn.cordys.crm.aiagent.mapper.AiAgentKnowledgeMapper;
import cn.cordys.mybatis.BaseMapper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class AiAgentKnowledgeParsePersistenceService {

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_FAILED = "FAILED";
    static final String STEP_RETRY_PREFIX = "RETRY_";

    @Resource
    private BaseMapper<AiKnowledgeChunk> chunkMapper;
    @Resource
    private BaseMapper<AiKnowledgeParseJob> parseJobMapper;
    @Resource
    private AiAgentKnowledgeMapper knowledgeMapper;
    @Resource
    private AiAgentKnowledgeParseProperties properties;
    @Resource
    private AiAgentSemanticRuleService semanticRuleService;

    @Transactional(readOnly = true)
    public AiKnowledgeParseJob findJob(String jobId) {
        return parseJobMapper.selectByPrimaryKey(jobId);
    }

    @Transactional(readOnly = true)
    public List<String> listPendingJobIds() {
        return knowledgeMapper.listPendingParseJobIds(batchSize());
    }

    @Transactional(readOnly = true)
    public List<String> listStaleRunningJobIds(long staleBefore) {
        return knowledgeMapper.listStaleRunningParseJobIds(staleBefore, batchSize());
    }

    @Transactional(rollbackFor = Exception.class)
    public ClaimedParseJob claim(String jobId) {
        long now = System.currentTimeMillis();
        if (knowledgeMapper.claimParseJob(jobId, now) != 1) {
            return null;
        }
        AiKnowledgeParseJob job = knowledgeMapper.getParseJobForUpdate(jobId);
        if (job == null) {
            return null;
        }
        AiKnowledgeParseJob latest = knowledgeMapper.getLatestParseJobForUpdate(
                job.getDocumentId(), job.getOrganizationId());
        if (!isSameJob(job, latest)) {
            failJob(job, "SUPERSEDED", "已有更新的解析任务，本任务已作废", null, now);
            return null;
        }
        AiKnowledgeDocument document = knowledgeMapper.getKnowledgeDocumentForUpdate(
                job.getDocumentId(), job.getOrganizationId());
        if (document == null) {
            failJob(job, "DOCUMENT_MISSING", "文档不存在或已被删除", null, now);
            return null;
        }
        document.setParseStatus("PARSING");
        document.setParseError(null);
        document.setUpdateUser(operator(job));
        document.setUpdateTime(now);
        persistDocumentParseState(document, null, "更新文档解析状态失败");
        return new ClaimedParseJob(job, document);
    }

    @Transactional(rollbackFor = Exception.class)
    public CompletionResult completeAndActivate(String jobId, List<AiKnowledgeChunk> chunks) {
        CompletionResult result = complete(jobId, chunks);
        if (result != CompletionResult.SUCCESS) {
            return result;
        }
        AiKnowledgeParseJob job = knowledgeMapper.getParseJobForUpdate(jobId);
        if (job == null) {
            throw new IllegalStateException("解析任务不存在，无法自动启用知识规则");
        }
        AiKnowledgeDocument document = knowledgeMapper.getKnowledgeDocumentForUpdate(
                job.getDocumentId(), job.getOrganizationId());
        if (semanticRuleService.isSemanticDocument(document)) {
            semanticRuleService.publish(
                    document.getId(), document.getOrganizationId(), operator(job));
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public CompletionResult complete(String jobId, List<AiKnowledgeChunk> chunks) {
        long now = System.currentTimeMillis();
        AiKnowledgeParseJob job = knowledgeMapper.getParseJobForUpdate(jobId);
        if (job == null || !StringUtils.equals(job.getStatus(), STATUS_RUNNING)) {
            return CompletionResult.LOST_OWNERSHIP;
        }
        AiKnowledgeParseJob latest = knowledgeMapper.getLatestParseJobForUpdate(
                job.getDocumentId(), job.getOrganizationId());
        if (!isSameJob(job, latest)) {
            failJob(job, "SUPERSEDED", "已有更新的解析任务，本任务结果未写入", null, now);
            return CompletionResult.SUPERSEDED;
        }
        AiKnowledgeDocument document = knowledgeMapper.getKnowledgeDocumentForUpdate(
                job.getDocumentId(), job.getOrganizationId());
        if (document == null) {
            failJob(job, "DOCUMENT_MISSING", "文档不存在或已被删除", null, now);
            return CompletionResult.DOCUMENT_MISSING;
        }
        validateChunks(chunks, document);

        knowledgeMapper.deleteChunksByDocumentId(document.getId(), document.getOrganizationId());
        for (AiKnowledgeChunk chunk : chunks) {
            requireOne(chunkMapper.insert(chunk), "写入知识片段失败");
        }

        document.setParseStatus("PARSED");
        document.setParseError(null);
        document.setChunkCount(chunks.size());
        document.setUpdateUser(operator(job));
        document.setUpdateTime(now);
        persistDocumentParseState(document, chunks.size(), "更新文档解析结果失败");

        job.setStatus(STATUS_SUCCESS);
        job.setStep("DONE");
        job.setMessage("解析成功，共生成 " + chunks.size() + " 个知识片段");
        job.setErrorStack(null);
        job.setFinishTime(now);
        job.setUpdateTime(now);
        persistJobState(job, "更新解析任务结果失败");
        return CompletionResult.SUCCESS;
    }

    @Transactional(rollbackFor = Exception.class)
    public RetryResult retryOrFail(String jobId, String message, String errorStack) {
        AiKnowledgeParseJob job = knowledgeMapper.getParseJobForUpdate(jobId);
        if (job == null || !StringUtils.equals(job.getStatus(), STATUS_RUNNING)) {
            return RetryResult.IGNORED;
        }
        return transitionFailure(job, message, errorStack, System.currentTimeMillis());
    }

    @Transactional(rollbackFor = Exception.class)
    public RetryResult recoverStaleJob(String jobId, long staleBefore) {
        AiKnowledgeParseJob job = knowledgeMapper.getParseJobForUpdate(jobId);
        if (job == null || !StringUtils.equals(job.getStatus(), STATUS_RUNNING)) {
            return RetryResult.IGNORED;
        }
        long activityTime = job.getStartTime() != null
                ? job.getStartTime()
                : Objects.requireNonNullElse(job.getUpdateTime(),
                        Objects.requireNonNullElse(job.getCreateTime(), 0L));
        if (activityTime > staleBefore) {
            return RetryResult.IGNORED;
        }
        return transitionFailure(job, "解析任务运行超时，已进入恢复流程", null, System.currentTimeMillis());
    }

    private RetryResult transitionFailure(AiKnowledgeParseJob job,
                                          String message,
                                          String errorStack,
                                          long now) {
        AiKnowledgeParseJob latest = knowledgeMapper.getLatestParseJobForUpdate(
                job.getDocumentId(), job.getOrganizationId());
        if (!isSameJob(job, latest)) {
            failJob(job, "SUPERSEDED", "已有更新的解析任务，本任务已作废", errorStack, now);
            return RetryResult.SUPERSEDED;
        }
        AiKnowledgeDocument document = knowledgeMapper.getKnowledgeDocumentForUpdate(
                job.getDocumentId(), job.getOrganizationId());
        if (document == null) {
            failJob(job, "DOCUMENT_MISSING", "文档不存在或已被删除", errorStack, now);
            return RetryResult.FAILED;
        }
        int currentRetry = retryCount(job.getStep());
        int nextRetry = currentRetry + 1;
        String safeMessage = StringUtils.left(StringUtils.defaultIfBlank(message, "文档解析失败"), 1000);
        if (nextRetry <= Math.max(0, properties.getMaxRetries())) {
            job.setStatus(STATUS_PENDING);
            job.setStep(STEP_RETRY_PREFIX + nextRetry);
            job.setMessage(StringUtils.left(
                    "解析失败，等待第 " + nextRetry + " 次重试：" + safeMessage, 1000));
            job.setErrorStack(StringUtils.left(errorStack, 6000));
            job.setStartTime(null);
            job.setFinishTime(null);
            job.setUpdateTime(now);
            persistJobState(job, "重置解析任务失败");

            document.setParseStatus("UPLOADED");
            document.setParseError(null);
            document.setUpdateUser(operator(job));
            document.setUpdateTime(now);
            persistDocumentParseState(document, null, "重置文档解析状态失败");
            return RetryResult.REQUEUED;
        }

        failJob(job, "FAILED", safeMessage, errorStack, now);
        document.setParseStatus("FAILED");
        document.setParseError(StringUtils.left(safeMessage, 2000));
        document.setUpdateUser(operator(job));
        document.setUpdateTime(now);
        persistDocumentParseState(document, null, "更新文档失败状态失败");
        return RetryResult.FAILED;
    }

    private void validateChunks(List<AiKnowledgeChunk> chunks, AiKnowledgeDocument document) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException("文档未生成有效知识片段");
        }
        boolean invalid = chunks.stream().anyMatch(chunk ->
                !Objects.equals(chunk.getDocumentId(), document.getId())
                        || !Objects.equals(chunk.getOrganizationId(), document.getOrganizationId()));
        if (invalid) {
            throw new IllegalStateException("知识片段与解析任务所属文档不一致");
        }
    }

    private void failJob(AiKnowledgeParseJob job,
                         String step,
                         String message,
                         String errorStack,
                         long now) {
        job.setStatus(STATUS_FAILED);
        job.setStep(step);
        job.setMessage(StringUtils.left(message, 1000));
        job.setErrorStack(StringUtils.left(errorStack, 6000));
        job.setFinishTime(now);
        job.setUpdateTime(now);
        persistJobState(job, "更新解析任务失败状态失败");
    }

    private boolean isSameJob(AiKnowledgeParseJob job, AiKnowledgeParseJob other) {
        return other != null && Objects.equals(job.getId(), other.getId());
    }

    private int retryCount(String step) {
        String value = StringUtils.substringAfter(StringUtils.defaultString(step), STEP_RETRY_PREFIX);
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int batchSize() {
        return Math.max(1, Math.min(properties.getRecoveryBatchSize(), 200));
    }

    private String operator(AiKnowledgeParseJob job) {
        return StringUtils.defaultIfBlank(job.getUpdateUser(), job.getCreateUser());
    }

    private void persistJobState(AiKnowledgeParseJob job, String errorMessage) {
        requireOne(knowledgeMapper.updateParseJobState(
                job.getId(),
                job.getOrganizationId(),
                job.getStatus(),
                job.getStep(),
                job.getMessage(),
                job.getErrorStack(),
                job.getStartTime(),
                job.getFinishTime(),
                job.getUpdateTime()), errorMessage);
    }

    private void persistDocumentParseState(AiKnowledgeDocument document,
                                           Integer chunkCount,
                                           String errorMessage) {
        requireOne(knowledgeMapper.updateKnowledgeDocumentParseState(
                document.getId(),
                document.getOrganizationId(),
                document.getParseStatus(),
                document.getParseError(),
                chunkCount,
                document.getUpdateUser(),
                document.getUpdateTime()), errorMessage);
    }

    private void requireOne(Integer affectedRows, String message) {
        if (affectedRows == null || affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }

    public record ClaimedParseJob(AiKnowledgeParseJob job, AiKnowledgeDocument document) {
    }

    public enum CompletionResult {
        SUCCESS,
        LOST_OWNERSHIP,
        SUPERSEDED,
        DOCUMENT_MISSING,
        PUBLISHED_SEMANTIC_DOCUMENT
    }

    public enum RetryResult {
        REQUEUED,
        FAILED,
        SUPERSEDED,
        IGNORED
    }
}
