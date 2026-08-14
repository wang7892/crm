package cn.cordys.crm.aiagent.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.common.uid.IDGenerator;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.domain.AiKnowledgeParseJob;
import cn.cordys.crm.aiagent.mapper.AiAgentKnowledgeMapper;
import cn.cordys.mybatis.BaseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentKnowledgeServiceTest {

    private final AiAgentKnowledgeService service = new AiAgentKnowledgeService();

    @TempDir
    private Path tempDir;

    @Test
    void shouldExtractChineseBusinessTermsForDocumentRetrieval() {
        List<String> keywords = service.extractKeywords("2026年五月份新增的公司客户有哪些？");

        assertThat(keywords).contains("公司", "客户", "公司客户");
        assertThat(keywords).doesNotContain("哪些");
    }

    @Test
    void shouldRenderPdfPagesAsMarkdownSections() {
        AiKnowledgeDocument document = document("产品手册.pdf", "pdf");
        List<AiAgentKnowledgeService.ParsedSection> sections = List.of(
                new AiAgentKnowledgeService.ParsedSection("第 1 页", null, 1, "第一页内容"),
                new AiAgentKnowledgeService.ParsedSection("第 2 页", null, 2, "第二页内容")
        );

        String markdown = service.renderMarkdown(document, sections);

        assertThat(markdown).isEqualTo("""
                # 产品手册

                ## 第 1 页

                第一页内容

                ## 第 2 页

                第二页内容

                """);
    }

    @Test
    void shouldRenderDocxBodyAsMarkdownSection() {
        AiKnowledgeDocument document = document("销售规则.docx", "docx");
        List<AiAgentKnowledgeService.ParsedSection> sections = List.of(
                new AiAgentKnowledgeService.ParsedSection("Word 文档", "正文", null, "规则正文")
        );

        String markdown = service.renderMarkdown(document, sections);

        assertThat(markdown).isEqualTo("""
                # 销售规则

                ## 正文

                规则正文

                """);
    }

    @Test
    void shouldRenderTxtWithoutDuplicatingTheFileName() {
        AiKnowledgeDocument document = document("常见问题.txt", "txt");
        List<AiAgentKnowledgeService.ParsedSection> sections = List.of(
                new AiAgentKnowledgeService.ParsedSection("常见问题.txt", null, null, "问题与答案")
        );

        String markdown = service.renderMarkdown(document, sections);

        assertThat(markdown).isEqualTo("""
                # 常见问题

                问题与答案

                """);
    }

    @Test
    void shouldWriteNormalizedMarkdownNextToTheOriginalFile() throws IOException {
        AiKnowledgeDocument document = document("产品手册.pdf", "pdf");
        document.setStoragePath(tempDir.resolve("original.pdf").toString());
        List<AiAgentKnowledgeService.ParsedSection> sections = List.of(
                new AiAgentKnowledgeService.ParsedSection("第 1 页", null, 1, "产品内容")
        );

        service.writeMarkdownFile(document, sections);

        Path markdownFile = tempDir.resolve("normalized.md");
        assertThat(markdownFile).exists();
        assertThat(Files.readString(markdownFile, StandardCharsets.UTF_8)).isEqualTo("""
                # 产品手册

                ## 第 1 页

                产品内容

                """);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldQueueReparseWithoutDeletingExistingChunks() {
        AiAgentKnowledgeService queueService = new AiAgentKnowledgeService();
        BaseMapper<AiKnowledgeDocument> documentMapper = mock(BaseMapper.class);
        BaseMapper<AiKnowledgeParseJob> parseJobMapper = mock(BaseMapper.class);
        AiAgentKnowledgeMapper knowledgeMapper = mock(AiAgentKnowledgeMapper.class);
        AiAgentSemanticRuleService semanticRuleService = mock(AiAgentSemanticRuleService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        AiKnowledgeDocument document = document("常见问题.txt", "txt");
        document.setId("doc-1");
        document.setOrganizationId("org-1");
        document.setParseStatus("PARSED");
        document.setChunkCount(7);
        document.setEnabled(1);
        document.setCreateTime(1L);
        document.setUpdateTime(1L);

        when(knowledgeMapper.getKnowledgeDocumentForUpdate("doc-1", "org-1")).thenReturn(document);
        when(knowledgeMapper.updateKnowledgeDocumentParseState(
                eq("doc-1"), eq("org-1"), eq("UPLOADED"), isNull(), isNull(), eq("user-1"),
                anyLong())).thenReturn(1);
        when(parseJobMapper.insert(any(AiKnowledgeParseJob.class))).thenReturn(1);
        when(semanticRuleService.isSemanticDocument(document)).thenReturn(false);
        ReflectionTestUtils.setField(queueService, "documentMapper", documentMapper);
        ReflectionTestUtils.setField(queueService, "parseJobMapper", parseJobMapper);
        ReflectionTestUtils.setField(queueService, "aiAgentKnowledgeMapper", knowledgeMapper);
        ReflectionTestUtils.setField(queueService, "semanticRuleService", semanticRuleService);
        ReflectionTestUtils.setField(queueService, "eventPublisher", eventPublisher);

        try (MockedStatic<IDGenerator> idGenerator = mockStatic(IDGenerator.class)) {
            idGenerator.when(IDGenerator::nextStr).thenReturn("job-1");
            queueService.reparseDocument("doc-1", "org-1", "user-1");
        }

        ArgumentCaptor<AiKnowledgeParseJob> jobCaptor = ArgumentCaptor.forClass(AiKnowledgeParseJob.class);
        verify(parseJobMapper).insert(jobCaptor.capture());
        AiKnowledgeParseJob job = jobCaptor.getValue();
        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getStep()).isEqualTo("RETRY_0");
        assertThat(document.getParseStatus()).isEqualTo("UPLOADED");
        assertThat(document.getChunkCount()).isEqualTo(7);
        verify(knowledgeMapper, never()).deleteChunksByDocumentId(any(), any());
        verify(eventPublisher).publishEvent(any(AiKnowledgeParseRequestedEvent.class));
    }

    @Test
    void shouldKeepNormalizedMarkdownWhenSemanticExtractionFails() throws Exception {
        AiAgentKnowledgeService parseService = new AiAgentKnowledgeService();
        AiAgentSemanticRuleService semanticRuleService = mock(AiAgentSemanticRuleService.class);
        AiAgentSemanticRuleExtractionService extractionService = mock(AiAgentSemanticRuleExtractionService.class);
        AiKnowledgeDocument document = document("业务口径.txt", "txt");
        document.setId("doc-1");
        document.setOrganizationId("org-1");
        document.setCategory("SEMANTIC_RULE");
        document.setStoragePath(tempDir.resolve("original.txt").toString());
        Files.writeString(Path.of(document.getStoragePath()), "品种表示外部合同中的产品名称。", StandardCharsets.UTF_8);
        when(semanticRuleService.isSemanticDocument(document)).thenReturn(true);
        when(extractionService.extract(eq(document), eq("org-1"), anyString(), anyList()))
                .thenThrow(new GenericException("model unavailable"));
        ReflectionTestUtils.setField(parseService, "semanticRuleService", semanticRuleService);
        ReflectionTestUtils.setField(parseService, "semanticRuleExtractionService", extractionService);

        assertThatThrownBy(() -> parseService.prepareParse(document, "org-1", "user-1"))
                .isInstanceOf(GenericException.class)
                .hasMessageContaining("model unavailable");

        assertThat(tempDir.resolve("normalized.md")).exists();
        assertThat(Files.readString(tempDir.resolve("normalized.md"), StandardCharsets.UTF_8))
                .contains("品种表示外部合同中的产品名称。");
    }

    private AiKnowledgeDocument document(String originalName, String fileType) {
        AiKnowledgeDocument document = new AiKnowledgeDocument();
        document.setOriginalName(originalName);
        document.setFileType(fileType);
        return document;
    }
}
