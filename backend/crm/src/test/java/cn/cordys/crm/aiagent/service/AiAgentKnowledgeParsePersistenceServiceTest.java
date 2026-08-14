package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentKnowledgeParseProperties;
import cn.cordys.crm.aiagent.domain.AiKnowledgeChunk;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.domain.AiKnowledgeParseJob;
import cn.cordys.crm.aiagent.mapper.AiAgentKnowledgeMapper;
import cn.cordys.mybatis.BaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentKnowledgeParsePersistenceServiceTest {

    private BaseMapper<AiKnowledgeChunk> chunkMapper;
    private BaseMapper<AiKnowledgeParseJob> parseJobMapper;
    private AiAgentKnowledgeMapper knowledgeMapper;
    private AiAgentSemanticRuleService semanticRuleService;
    private AiAgentKnowledgeParsePersistenceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chunkMapper = mock(BaseMapper.class);
        parseJobMapper = mock(BaseMapper.class);
        knowledgeMapper = mock(AiAgentKnowledgeMapper.class);
        semanticRuleService = mock(AiAgentSemanticRuleService.class);
        AiAgentKnowledgeParseProperties properties = new AiAgentKnowledgeParseProperties();
        properties.setMaxRetries(3);

        service = new AiAgentKnowledgeParsePersistenceService();
        ReflectionTestUtils.setField(service, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(service, "parseJobMapper", parseJobMapper);
        ReflectionTestUtils.setField(service, "knowledgeMapper", knowledgeMapper);
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "semanticRuleService", semanticRuleService);
        when(knowledgeMapper.updateParseJobState(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        when(knowledgeMapper.updateKnowledgeDocumentParseState(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(Integer.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
    }

    @Test
    void shouldClaimLatestPendingJobAndMarkDocumentParsing() {
        AiKnowledgeParseJob job = runningJob("job-1", "RETRY_0");
        AiKnowledgeDocument document = document(false);
        when(knowledgeMapper.claimParseJob(eq("job-1"), anyLong())).thenReturn(1);
        when(knowledgeMapper.getParseJobForUpdate("job-1")).thenReturn(job);
        when(knowledgeMapper.getLatestParseJobForUpdate("doc-1", "org-1")).thenReturn(job);
        when(knowledgeMapper.getKnowledgeDocumentForUpdate("doc-1", "org-1")).thenReturn(document);

        AiAgentKnowledgeParsePersistenceService.ClaimedParseJob claimed = service.claim("job-1");

        assertThat(claimed).isNotNull();
        assertThat(claimed.document().getParseStatus()).isEqualTo("PARSING");
        assertThat(claimed.document().getParseError()).isNull();
    }

    @Test
    void shouldSupersedeJobThatIsNoLongerLatest() {
        AiKnowledgeParseJob job = runningJob("job-1", "RETRY_0");
        AiKnowledgeParseJob latest = runningJob("job-2", "RETRY_0");
        when(knowledgeMapper.claimParseJob(eq("job-1"), anyLong())).thenReturn(1);
        when(knowledgeMapper.getParseJobForUpdate("job-1")).thenReturn(job);
        when(knowledgeMapper.getLatestParseJobForUpdate("doc-1", "org-1")).thenReturn(latest);

        assertThat(service.claim("job-1")).isNull();
        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getStep()).isEqualTo("SUPERSEDED");
        verify(knowledgeMapper, never()).getKnowledgeDocumentForUpdate(any(), any());
    }

    @Test
    void shouldAtomicallyReplaceChunksForLatestRunningJob() {
        AiKnowledgeParseJob job = runningJob("job-1", "RETRY_0");
        AiKnowledgeDocument document = document(false);
        AiKnowledgeChunk chunk = chunk();
        when(knowledgeMapper.getParseJobForUpdate("job-1")).thenReturn(job);
        when(knowledgeMapper.getLatestParseJobForUpdate("doc-1", "org-1")).thenReturn(job);
        when(knowledgeMapper.getKnowledgeDocumentForUpdate("doc-1", "org-1")).thenReturn(document);
        when(chunkMapper.insert(chunk)).thenReturn(1);

        AiAgentKnowledgeParsePersistenceService.CompletionResult result = service.complete("job-1", List.of(chunk));

        assertThat(result).isEqualTo(AiAgentKnowledgeParsePersistenceService.CompletionResult.SUCCESS);
        verify(knowledgeMapper).deleteChunksByDocumentId("doc-1", "org-1");
        verify(chunkMapper).insert(chunk);
        assertThat(document.getParseStatus()).isEqualTo("PARSED");
        assertThat(document.getChunkCount()).isEqualTo(1);
        assertThat(job.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldReplacePreviouslyActiveSemanticDocumentDuringReparse() {
        AiKnowledgeParseJob job = runningJob("job-1", "RETRY_0");
        AiKnowledgeDocument document = document(true);
        when(knowledgeMapper.getParseJobForUpdate("job-1")).thenReturn(job);
        when(knowledgeMapper.getLatestParseJobForUpdate("doc-1", "org-1")).thenReturn(job);
        when(knowledgeMapper.getKnowledgeDocumentForUpdate("doc-1", "org-1")).thenReturn(document);
        when(chunkMapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        AiAgentKnowledgeParsePersistenceService.CompletionResult result = service.complete("job-1", List.of(chunk()));

        assertThat(result).isEqualTo(AiAgentKnowledgeParsePersistenceService.CompletionResult.SUCCESS);
        verify(knowledgeMapper).deleteChunksByDocumentId("doc-1", "org-1");
        verify(chunkMapper).insert(any());
        assertThat(document.getParseStatus()).isEqualTo("PARSED");
    }

    @Test
    void shouldAutomaticallyActivateSemanticRulesAfterSuccessfulCompletion() {
        AiKnowledgeParseJob job = runningJob("job-1", "RETRY_0");
        AiKnowledgeDocument document = document(true);
        AiKnowledgeChunk chunk = chunk();
        when(knowledgeMapper.getParseJobForUpdate("job-1")).thenReturn(job);
        when(knowledgeMapper.getLatestParseJobForUpdate("doc-1", "org-1")).thenReturn(job);
        when(knowledgeMapper.getKnowledgeDocumentForUpdate("doc-1", "org-1")).thenReturn(document);
        when(chunkMapper.insert(chunk)).thenReturn(1);
        when(semanticRuleService.isSemanticDocument(document)).thenReturn(true);

        AiAgentKnowledgeParsePersistenceService.CompletionResult result =
                service.completeAndActivate("job-1", List.of(chunk));

        assertThat(result).isEqualTo(AiAgentKnowledgeParsePersistenceService.CompletionResult.SUCCESS);
        verify(semanticRuleService).publish("doc-1", "org-1", "user-1");
    }

    @Test
    void shouldRequeueFailureWithoutDeletingExistingChunks() {
        AiKnowledgeParseJob job = runningJob("job-1", "RETRY_0");
        AiKnowledgeDocument document = document(false);
        document.setChunkCount(4);
        when(knowledgeMapper.getParseJobForUpdate("job-1")).thenReturn(job);
        when(knowledgeMapper.getLatestParseJobForUpdate("doc-1", "org-1")).thenReturn(job);
        when(knowledgeMapper.getKnowledgeDocumentForUpdate("doc-1", "org-1")).thenReturn(document);

        AiAgentKnowledgeParsePersistenceService.RetryResult result =
                service.retryOrFail("job-1", "temporary failure", "stack");

        assertThat(result).isEqualTo(AiAgentKnowledgeParsePersistenceService.RetryResult.REQUEUED);
        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getStep()).isEqualTo("RETRY_1");
        assertThat(document.getParseStatus()).isEqualTo("UPLOADED");
        assertThat(document.getChunkCount()).isEqualTo(4);
        verify(knowledgeMapper, never()).deleteChunksByDocumentId(any(), any());
    }

    @Test
    void shouldFailAfterMaximumRetryWithoutDeletingExistingChunks() {
        AiKnowledgeParseJob job = runningJob("job-1", "RETRY_3");
        AiKnowledgeDocument document = document(false);
        document.setChunkCount(4);
        when(knowledgeMapper.getParseJobForUpdate("job-1")).thenReturn(job);
        when(knowledgeMapper.getLatestParseJobForUpdate("doc-1", "org-1")).thenReturn(job);
        when(knowledgeMapper.getKnowledgeDocumentForUpdate("doc-1", "org-1")).thenReturn(document);

        AiAgentKnowledgeParsePersistenceService.RetryResult result =
                service.retryOrFail("job-1", "permanent failure", "stack");

        assertThat(result).isEqualTo(AiAgentKnowledgeParsePersistenceService.RetryResult.FAILED);
        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(document.getParseStatus()).isEqualTo("FAILED");
        assertThat(document.getChunkCount()).isEqualTo(4);
        verify(knowledgeMapper, never()).deleteChunksByDocumentId(any(), any());
    }

    @Test
    void shouldRecoverStaleRunningJobAsNextRetry() {
        AiKnowledgeParseJob job = runningJob("job-1", "RETRY_1");
        job.setStartTime(100L);
        AiKnowledgeDocument document = document(false);
        when(knowledgeMapper.getParseJobForUpdate("job-1")).thenReturn(job);
        when(knowledgeMapper.getLatestParseJobForUpdate("doc-1", "org-1")).thenReturn(job);
        when(knowledgeMapper.getKnowledgeDocumentForUpdate("doc-1", "org-1")).thenReturn(document);

        AiAgentKnowledgeParsePersistenceService.RetryResult result = service.recoverStaleJob("job-1", 200L);

        assertThat(result).isEqualTo(AiAgentKnowledgeParsePersistenceService.RetryResult.REQUEUED);
        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getStep()).isEqualTo("RETRY_2");
        assertThat(job.getStartTime()).isNull();
        assertThat(document.getParseStatus()).isEqualTo("UPLOADED");
    }

    private AiKnowledgeParseJob runningJob(String id, String step) {
        AiKnowledgeParseJob job = new AiKnowledgeParseJob();
        job.setId(id);
        job.setOrganizationId("org-1");
        job.setDocumentId("doc-1");
        job.setStatus("RUNNING");
        job.setStep(step);
        job.setCreateUser("user-1");
        job.setUpdateUser("user-1");
        job.setCreateTime(1L);
        job.setUpdateTime(1L);
        return job;
    }

    private AiKnowledgeDocument document(boolean publishedSemantic) {
        AiKnowledgeDocument document = new AiKnowledgeDocument();
        document.setId("doc-1");
        document.setOrganizationId("org-1");
        document.setCategory(publishedSemantic ? "SEMANTIC_RULE" : "PRODUCT");
        document.setParseStatus("PARSED");
        document.setParseError("old failure");
        document.setChunkCount(2);
        document.setEnabled(publishedSemantic ? 1 : 0);
        document.setCreateTime(1L);
        document.setUpdateTime(1L);
        return document;
    }

    private AiKnowledgeChunk chunk() {
        AiKnowledgeChunk chunk = new AiKnowledgeChunk();
        chunk.setId("chunk-1");
        chunk.setOrganizationId("org-1");
        chunk.setDocumentId("doc-1");
        chunk.setChunkIndex(0);
        chunk.setContent("content");
        return chunk;
    }
}
