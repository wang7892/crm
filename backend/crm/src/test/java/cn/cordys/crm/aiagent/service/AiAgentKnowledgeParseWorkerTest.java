package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.domain.AiKnowledgeChunk;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.domain.AiKnowledgeParseJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentKnowledgeParseWorkerTest {

    private AiAgentKnowledgeParsePersistenceService persistenceService;
    private AiAgentKnowledgeService knowledgeService;
    private RLock lock;
    private AiAgentKnowledgeParseWorker worker;

    @BeforeEach
    void setUp() {
        persistenceService = mock(AiAgentKnowledgeParsePersistenceService.class);
        knowledgeService = mock(AiAgentKnowledgeService.class);
        Redisson redisson = mock(Redisson.class);
        lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        worker = new AiAgentKnowledgeParseWorker();
        ReflectionTestUtils.setField(worker, "persistenceService", persistenceService);
        ReflectionTestUtils.setField(worker, "knowledgeService", knowledgeService);
        ReflectionTestUtils.setField(worker, "redisson", redisson);
    }

    @Test
    void shouldParseAndCompleteClaimedJob() throws Exception {
        AiKnowledgeParseJob job = job();
        AiKnowledgeDocument document = document();
        AiKnowledgeChunk chunk = chunk();
        when(persistenceService.findJob("job-1")).thenReturn(job);
        when(persistenceService.claim("job-1")).thenReturn(
                new AiAgentKnowledgeParsePersistenceService.ClaimedParseJob(job, document));
        when(knowledgeService.prepareParse(document, "org-1", "user-1"))
                .thenReturn(new AiAgentKnowledgeService.PreparedParse(List.of(chunk)));

        worker.process("job-1");

        verify(persistenceService).completeAndActivate("job-1", List.of(chunk));
        verify(lock).unlock();
    }

    @Test
    void shouldIgnoreDuplicateDeliveryWhenClaimFails() throws Exception {
        when(persistenceService.findJob("job-1")).thenReturn(job());
        when(persistenceService.claim("job-1")).thenReturn(null);

        worker.process("job-1");

        verify(knowledgeService, never()).prepareParse(
                org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        verify(persistenceService, never()).completeAndActivate(
                anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void shouldDelegateFailureToRetryStateMachine() throws Exception {
        AiKnowledgeParseJob job = job();
        AiKnowledgeDocument document = document();
        when(persistenceService.findJob("job-1")).thenReturn(job);
        when(persistenceService.claim("job-1")).thenReturn(
                new AiAgentKnowledgeParsePersistenceService.ClaimedParseJob(job, document));
        when(knowledgeService.prepareParse(document, "org-1", "user-1"))
                .thenThrow(new IllegalStateException("temporary failure"));

        worker.process("job-1");

        verify(persistenceService).retryOrFail(
                eq("job-1"), eq("temporary failure"), org.mockito.ArgumentMatchers.contains("temporary failure"));
        verify(persistenceService, never()).completeAndActivate(
                anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    private AiKnowledgeParseJob job() {
        AiKnowledgeParseJob job = new AiKnowledgeParseJob();
        job.setId("job-1");
        job.setDocumentId("doc-1");
        job.setOrganizationId("org-1");
        job.setCreateUser("user-1");
        job.setUpdateUser("user-1");
        return job;
    }

    private AiKnowledgeDocument document() {
        AiKnowledgeDocument document = new AiKnowledgeDocument();
        document.setId("doc-1");
        document.setOrganizationId("org-1");
        return document;
    }

    private AiKnowledgeChunk chunk() {
        AiKnowledgeChunk chunk = new AiKnowledgeChunk();
        chunk.setId("chunk-1");
        chunk.setDocumentId("doc-1");
        chunk.setOrganizationId("org-1");
        return chunk;
    }
}
