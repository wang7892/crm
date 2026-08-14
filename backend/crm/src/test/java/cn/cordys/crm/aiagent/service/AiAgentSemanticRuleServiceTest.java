package cn.cordys.crm.aiagent.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.crm.aiagent.domain.AiKnowledgeChunk;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleVersionSwitchRequest;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRule;
import cn.cordys.crm.aiagent.mapper.AiAgentKnowledgeMapper;
import cn.cordys.mybatis.BaseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentSemanticRuleServiceTest {

    private AiAgentKnowledgeMapper knowledgeMapper;
    private BaseMapper<AiKnowledgeDocument> documentMapper;
    private AiAgentSemanticRuleValidationService validationService;
    private ApplicationEventPublisher eventPublisher;
    private Redisson redisson;
    private RLock lock;
    private AiAgentSemanticRuleService service;

    @TempDir
    private Path tempDir;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        knowledgeMapper = mock(AiAgentKnowledgeMapper.class);
        documentMapper = mock(BaseMapper.class);
        validationService = new AiAgentSemanticRuleValidationService(new AiAgentSemanticSchemaService());
        eventPublisher = mock(ApplicationEventPublisher.class);
        redisson = mock(Redisson.class);
        lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        service = new AiAgentSemanticRuleService();
        ReflectionTestUtils.setField(service, "knowledgeMapper", knowledgeMapper);
        ReflectionTestUtils.setField(service, "documentMapper", documentMapper);
        ReflectionTestUtils.setField(service, "validationService", validationService);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(service, "redisson", redisson);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldAssignVersionAndEnableOnlyApprovedRuleWhenPublishing() throws Exception {
        String quote = "品种是外部合同表的一个字段，不要理解为订单中的字段。";
        Path source = tempDir.resolve("rules.md");
        Files.writeString(source, "# 外部合同口径\n\n" + quote, StandardCharsets.UTF_8);
        AiKnowledgeDocument document = document(source);
        AiKnowledgeChunk chunk = chunk(document, approvedRule(document.getId(), quote));
        when(knowledgeMapper.getSemanticDocumentForUpdate("doc-1", "org-1")).thenReturn(document);
        when(knowledgeMapper.listSemanticRuleChunksByDocument("doc-1", "org-1")).thenReturn(List.of(chunk));
        when(knowledgeMapper.listPublishedSemanticRuleChunks("org-1")).thenReturn(List.of());
        when(knowledgeMapper.maxSemanticRuleVersion(anyString(), eq("org-1"))).thenReturn(0);
        when(knowledgeMapper.updateSemanticRuleChunk(
                anyString(), anyString(), anyString(), anyString(), anyString(), eq(1), anyString(), anyLong()))
                .thenReturn(1);
        when(knowledgeMapper.updateSemanticDocumentEnabled(
                eq("doc-1"), eq("org-1"), eq(1), eq("reviewer"), anyLong())).thenReturn(1);

        service.publish("doc-1", "org-1", "reviewer");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(knowledgeMapper).updateSemanticRuleChunk(
                eq("chunk-1"), eq("org-1"), eq("品种"), content.capture(), anyString(),
                eq(1), eq("reviewer"), anyLong());
        AiAgentSemanticRule published = validationService.deserialize(content.getValue());
        assertThat(published.getVersion()).isEqualTo(1);
        assertThat(published.getReview().getStatus()).isEqualTo("APPROVED");
        verify(knowledgeMapper).updateSemanticDocumentEnabled(
                eq("doc-1"), eq("org-1"), eq(1), eq("reviewer"), anyLong());
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(AiAgentSemanticRulesChangedEvent.class));
    }

    @Test
    void shouldRejectDocumentContainingPendingRuleWithoutPartialEnable() throws Exception {
        String quote = "品种是外部合同表字段。";
        Path source = tempDir.resolve("rules.md");
        Files.writeString(source, quote, StandardCharsets.UTF_8);
        AiKnowledgeDocument document = document(source);
        AiAgentSemanticRule rule = approvedRule(document.getId(), quote);
        rule.getReview().setStatus("PENDING");
        AiKnowledgeChunk chunk = chunk(document, rule);
        when(knowledgeMapper.getSemanticDocumentForUpdate("doc-1", "org-1")).thenReturn(document);
        when(knowledgeMapper.listSemanticRuleChunksByDocument("doc-1", "org-1")).thenReturn(List.of(chunk));

        assertThatThrownBy(() -> service.publish("doc-1", "org-1", "reviewer"))
                .isInstanceOf(GenericException.class)
                .hasMessageContaining("未通过自动校验");

        verify(knowledgeMapper, never()).updateSemanticDocumentEnabled(
                anyString(), anyString(), eq(1), anyString(), anyLong());
    }

    @Test
    void shouldRejectVersionSwitchWhenSourceDocumentParsingFailed() throws Exception {
        String quote = "品种是外部合同表字段。";
        Path source = tempDir.resolve("rules.md");
        Files.writeString(source, quote, StandardCharsets.UTF_8);
        AiKnowledgeDocument document = document(source);
        document.setParseStatus("FAILED");
        AiAgentSemanticRule rule = approvedRule(document.getId(), quote);
        rule.setVersion(1);
        AiKnowledgeChunk target = chunk(document, rule);
        AiSemanticRuleVersionSwitchRequest request = new AiSemanticRuleVersionSwitchRequest();
        request.setRuleId(rule.getRuleId());
        request.setTargetVersion(1);

        when(knowledgeMapper.listActiveSemanticRuleVersions(rule.getRuleId(), "org-1")).thenReturn(List.of());
        when(knowledgeMapper.getSemanticRuleVersion(rule.getRuleId(), 1, "org-1")).thenReturn(target);
        when(knowledgeMapper.getSemanticDocumentForUpdate(document.getId(), "org-1")).thenReturn(document);

        assertThatThrownBy(() -> service.switchVersion(request, "org-1", "reviewer"))
                .isInstanceOf(GenericException.class)
                .hasMessageContaining("未解析成功");

        verify(knowledgeMapper, never()).updateSemanticRuleChunkEnabled(
                anyString(), anyString(), eq(1), anyString(), anyLong());
        verify(knowledgeMapper, never()).updateSemanticDocumentEnabled(
                anyString(), anyString(), eq(1), anyString(), anyLong());
    }

    @Test
    void shouldRejectVersionSwitchWhenTargetChunkCannotBeEnabled() throws Exception {
        VersionSwitchFixture fixture = versionSwitchFixture();
        when(knowledgeMapper.updateSemanticRuleChunkEnabled(
                eq(fixture.target().getId()), eq("org-1"), eq(1), eq("reviewer"), anyLong()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.switchVersion(fixture.request(), "org-1", "reviewer"))
                .isInstanceOf(GenericException.class)
                .hasMessageContaining("目标规则版本启用失败");

        verify(knowledgeMapper, never()).updateSemanticDocumentEnabled(
                anyString(), anyString(), eq(1), anyString(), anyLong());
        verify(eventPublisher, never()).publishEvent(
                org.mockito.ArgumentMatchers.any(AiAgentSemanticRulesChangedEvent.class));
    }

    @Test
    void shouldRejectVersionSwitchWhenSourceDocumentCannotBeEnabled() throws Exception {
        VersionSwitchFixture fixture = versionSwitchFixture();
        when(knowledgeMapper.updateSemanticRuleChunkEnabled(
                eq(fixture.target().getId()), eq("org-1"), eq(1), eq("reviewer"), anyLong()))
                .thenReturn(1);
        when(knowledgeMapper.updateSemanticDocumentEnabled(
                eq(fixture.document().getId()), eq("org-1"), eq(1), eq("reviewer"), anyLong()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.switchVersion(fixture.request(), "org-1", "reviewer"))
                .isInstanceOf(GenericException.class)
                .hasMessageContaining("目标规则来源文档启用失败");

        verify(eventPublisher, never()).publishEvent(
                org.mockito.ArgumentMatchers.any(AiAgentSemanticRulesChangedEvent.class));
    }

    private VersionSwitchFixture versionSwitchFixture() throws Exception {
        String quote = "品种是外部合同表字段。";
        Path source = tempDir.resolve("rules.md");
        Files.writeString(source, quote, StandardCharsets.UTF_8);
        AiKnowledgeDocument document = document(source);
        AiAgentSemanticRule rule = approvedRule(document.getId(), quote);
        rule.setVersion(1);
        AiKnowledgeChunk target = chunk(document, rule);
        AiSemanticRuleVersionSwitchRequest request = new AiSemanticRuleVersionSwitchRequest();
        request.setRuleId(rule.getRuleId());
        request.setTargetVersion(1);

        when(knowledgeMapper.listActiveSemanticRuleVersions(rule.getRuleId(), "org-1")).thenReturn(List.of());
        when(knowledgeMapper.getSemanticRuleVersion(rule.getRuleId(), 1, "org-1")).thenReturn(target);
        when(knowledgeMapper.getSemanticDocumentForUpdate(document.getId(), "org-1")).thenReturn(document);
        when(knowledgeMapper.listPublishedSemanticRuleChunks("org-1")).thenReturn(List.of());
        return new VersionSwitchFixture(request, target, document);
    }

    private AiKnowledgeDocument document(Path source) {
        AiKnowledgeDocument document = new AiKnowledgeDocument();
        document.setId("doc-1");
        document.setOrganizationId("org-1");
        document.setCategory("SEMANTIC_RULE");
        document.setParseStatus("PARSED");
        document.setEnabled(0);
        document.setFileType("md");
        document.setStoragePath(source.toString());
        document.setName("业务口径.md");
        return document;
    }

    private AiKnowledgeChunk chunk(AiKnowledgeDocument document, AiAgentSemanticRule rule) {
        AiKnowledgeChunk chunk = new AiKnowledgeChunk();
        chunk.setId("chunk-1");
        chunk.setDocumentId(document.getId());
        chunk.setOrganizationId(document.getOrganizationId());
        chunk.setTitle(rule.getCanonicalTerm());
        chunk.setContent(validationService.serialize(rule));
        chunk.setContentHash(validationService.semanticPayloadHash(rule));
        chunk.setEnabled(0);
        chunk.setUpdateTime(1L);
        return chunk;
    }

    private AiAgentSemanticRule approvedRule(String documentId, String quote) {
        AiAgentSemanticRule rule = new AiAgentSemanticRule();
        rule.setSchemaVersion("1.0");
        rule.setRuleId(validationService.generateRuleId("org-1", "CRM_DATABASE_QUERY", "品种"));
        rule.setVersion(0);
        rule.setType("TERM_MAPPING");
        rule.setCanonicalTerm("品种");
        rule.setDefinition("品种表示外部合同明细中的产品名称。");
        rule.setScope("CRM_DATABASE_QUERY");
        rule.setPriority(100);
        AiAgentSemanticRule.Mapping mapping = new AiAgentSemanticRule.Mapping();
        mapping.setEntity("contract_info");
        mapping.setField("product_name");
        mapping.setDataSource("EXTERNAL_CONTRACT");
        rule.setMapping(mapping);
        AiAgentSemanticRule.ForbiddenMapping forbidden = new AiAgentSemanticRule.ForbiddenMapping();
        forbidden.setEntity("sales_order");
        rule.setForbiddenMappings(List.of(forbidden));
        AiAgentSemanticRule.Source source = new AiAgentSemanticRule.Source();
        source.setDocumentId(documentId);
        source.setQuote(quote);
        source.setSectionPath("品种");
        rule.setSource(source);
        AiAgentSemanticRule.Review review = new AiAgentSemanticRule.Review();
        review.setStatus("APPROVED");
        review.setReviewerId("reviewer");
        review.setReviewedAt(2L);
        rule.setReview(review);
        return rule;
    }

    private record VersionSwitchFixture(AiSemanticRuleVersionSwitchRequest request,
                                        AiKnowledgeChunk target,
                                        AiKnowledgeDocument document) {
    }
}
