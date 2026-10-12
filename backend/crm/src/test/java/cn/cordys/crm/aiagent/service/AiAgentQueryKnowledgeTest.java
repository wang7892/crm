package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentQueryKnowledgeProperties;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.View;
import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.*;
import cn.cordys.crm.aiagent.mapper.AiAgentQueryKnowledgeMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentQueryKnowledgeTest {
    private AiAgentQueryKnowledgeMapper mapper;
    private AiAgentDatasourceService sources;
    private AiAgentQueryKnowledgeEmbedding embedding;
    private AiAgentQueryKnowledgeProperties properties;
    private AiAgentQueryKnowledgeService service;
    private AiAgentQueryKnowledgeFiles files;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:knowledge_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var input = getClass().getClassLoader().getResourceAsStream("migration/1.6.0/ddl/V1.6.0_54__ai_agent_query_knowledge.sql");
             var db = ds.getConnection(); var sql = db.createStatement()) {
            assertThat(input).isNotNull();
            sql.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        Configuration config = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true); config.addMapper(AiAgentQueryKnowledgeMapper.class);
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(AiAgentQueryKnowledgeMapper.class);
        sources = mock(AiAgentDatasourceService.class); embedding = mock(AiAgentQueryKnowledgeEmbedding.class);
        properties = new AiAgentQueryKnowledgeProperties();
        when(embedding.embed(anyString())).thenReturn(List.of());
        when(embedding.embed(anyString(), anyString())).thenAnswer(i -> embedding.embed(i.getArgument(0)));
        when(embedding.embedBatch(anyList(), anyString())).thenAnswer(i -> {
            List<String> texts = i.getArgument(0); String org = i.getArgument(1);
            return texts.stream().map(text -> embedding.embed(text, org)).toList();
        });
        when(embedding.key(anyString())).thenAnswer(i -> embedding.key());
        when(embedding.threshold(anyString())).thenAnswer(i -> properties.getSimilarity());
        when(embedding.topCount(anyString())).thenAnswer(i -> properties.getTopCount());
        View source = new View(); source.setId("db"); source.setName("测试数据源");
        when(sources.list("org", "user")).thenReturn(List.of(source));
        when(sources.list("org-copy", "user")).thenReturn(List.of(source));
        when(sources.list("org", "other")).thenReturn(List.of());
        when(sources.get(eq("private"), anyString(), anyString())).thenThrow(new IllegalArgumentException("无权访问"));
        var tx = new DataSourceTransactionManager(ds);
        service = transactional(new AiAgentQueryKnowledgeService(mapper, sources, embedding, properties), tx);
        files = transactional(new AiAgentQueryKnowledgeFiles(service, sources), tx);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, DataSourceTransactionManager manager) {
        var factory = new TransactionProxyFactoryBean();
        factory.setTarget(target); factory.setTransactionManager(manager); factory.setProxyTargetClass(true);
        var attributes = new Properties(); attributes.setProperty("*", "PROPAGATION_REQUIRED");
        factory.setTransactionAttributes(attributes); factory.afterPropertiesSet();
        return (T) factory.getObject();
    }

    private cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Item term(String name, boolean all, String... ids) {
        var item = new cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Item();
        item.setKind("TERM"); item.setName(name); item.setContent("product_name 表示产品品种，不是原料");
        item.setAllDatasources(all); item.setDatasourceIds(List.of(ids)); item.setEnabled(true);
        return item;
    }

    private cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Item example(String question, String sql, String source) {
        var item = term(question, false, source); item.setKind("SQL_EXAMPLE"); item.setContent(sql); return item;
    }

    @Test void reindexAdvancesPastFailedBatchesAndKeepsOrganizationIsolation() {
        for (int i = 0; i < 12; i++) service.save(term("术语" + i, true), "org", "user");
        service.save(term("其他组织", true), "org-copy", "user");
        doReturn("model-v2").when(embedding).key("org");
        doReturn(List.of()).when(embedding).embed(anyString(), eq("org"));
        var first = service.reindex("org", "user", List.of());
        assertThat(first.failed()).isEqualTo(10); assertThat(first.remaining()).isEqualTo(2);
        assertThat(first.processedIds()).hasSize(10);
        doReturn(List.of(1d, 0d)).when(embedding).embed(anyString(), eq("org"));
        var second = service.reindex("org", "user", first.processedIds());
        assertThat(second.success()).isEqualTo(2); assertThat(second.remaining()).isZero();
        assertThat(second.processedIds()).doesNotContainAnyElementsOf(first.processedIds());
        assertThat(service.list("TERM", "org-copy", "user").getFirst().getEmbeddingStatus()).isEqualTo("DISABLED");
        assertThat(service.reindex("org", "user", List.of()).success()).isEqualTo(10);
        assertThat(service.list("TERM", "org", "user")).allMatch(i -> "READY".equals(i.getEmbeddingStatus()));
    }

    @Test void automaticallyIndexesExistingKnowledgeAndRecallsItWithoutMatchingWords() {
        var region = term("客户地区", true);
        region.setContent("地区为空时从客户名称识别印度、香港或义乌");
        var saved = service.save(region, "org", "user");
        service.save(example("地区客户清单", "SELECT customer_name FROM customer_info", "CRM"), "org", "user");
        var other = service.save(term("其他组织地区", true), "org-copy", "user");
        var disabled = service.save(term("停用地区规则", true), "org", "user");
        service.enable(disabled.getId(), new Change(1, false), "org", "user");
        when(embedding.key()).thenReturn("backend-model-v1");
        when(embedding.embed(anyString())).thenReturn(List.of(1d, 0d));
        var worker = new AiAgentQueryKnowledgeIndexWorker(mapper, embedding, properties);
        worker.rebuildPendingIndexes();
        assertThat(mapper.get("org", saved.getId()).getEmbeddingKey()).isEqualTo(AiAgentQueryKnowledgeIndex.key("TERM", "backend-model-v1"));
        assertThat(mapper.get("org-copy", other.getId()).getEmbeddingKey()).isEqualTo(AiAgentQueryKnowledgeIndex.key("TERM", "backend-model-v1"));
        assertThat(mapper.get("org", disabled.getId()).getEmbeddingJson()).isNull();
        var matches = service.retrieve("印度有哪些客户", "CRM", "org", "user");
        assertThat(matches.terms()).extracting(Item::getName).containsExactly("客户地区");
        assertThat(matches.examples()).extracting(Item::getName).containsExactly("地区客户清单");
        assertThat(service.prompt(matches, false)).contains("地区为空时从客户名称识别", "customer_info");
        when(embedding.key()).thenReturn("backend-model-v2");
        worker.rebuildPendingIndexes();
        assertThat(mapper.get("org", saved.getId()).getEmbeddingKey()).isEqualTo(AiAgentQueryKnowledgeIndex.key("TERM", "backend-model-v2"));
    }

    @Test void backgroundIndexingMovesPastFailuresAndRetriesAfterTheScan() {
        for (int i = 0; i < 3; i++) service.save(term("规则" + i, true), "org", "user");
        when(embedding.key()).thenReturn("backend-model");
        properties.setIndexBatchSize(1);
        var first = mapper.pendingIndexes("backend-model", AiAgentQueryKnowledgeIndex.key("TERM", "backend-model"), "", 1).getFirst();
        when(embedding.embed(anyString())).thenReturn(List.of(1d, 0d));
        doReturn(List.of()).when(embedding).embed(startsWith(first.getName()), eq("org"));
        var worker = new AiAgentQueryKnowledgeIndexWorker(mapper, embedding, properties);
        for (int i = 0; i < 3; i++) worker.rebuildPendingIndexes();
        assertThat(service.list("TERM", "org", "user")).filteredOn(i -> "READY".equals(i.getEmbeddingStatus())).hasSize(2);
        doReturn(List.of(1d, 0d)).when(embedding).embed(startsWith(first.getName()), eq("org"));
        worker.rebuildPendingIndexes();
        assertThat(service.list("TERM", "org", "user")).allMatch(i -> "READY".equals(i.getEmbeddingStatus()));
    }

    @Test void backgroundIndexingDoesNotWriteVectorsAfterConcurrentEdits() {
        var saved = service.save(term("客户地区", true), "org", "user");
        when(embedding.key()).thenReturn("backend-model");
        doAnswer(i -> {
            service.enable(saved.getId(), new Change(1, false), "org", "user");
            return List.of(1d, 0d);
        }).when(embedding).embed(anyString(), eq("org"));
        new AiAgentQueryKnowledgeIndexWorker(mapper, embedding, properties).rebuildPendingIndexes();
        var changed = mapper.get("org", saved.getId());
        assertThat(changed.getRevision()).isEqualTo(2);
        assertThat(changed.getEmbeddingJson()).isNull();
    }

    @Test void storesCrudWithOrganizationIsolationAndRevisionChecks() {
        var saved = service.save(term("品种", true), "org", "user");
        assertThat(saved.getRevision()).isEqualTo(1);
        assertThat(service.list("TERM", "org", "user")).hasSize(1);
        assertThat(service.list("TERM", "different-org", "user")).isEmpty();
        assertThatThrownBy(() -> service.enable(saved.getId(), new Change(1, false), "different-org", "user"))
                .isInstanceOf(IllegalArgumentException.class);
        var changed = service.enable(saved.getId(), new Change(1, false), "org", "user");
        assertThat(changed.isEnabled()).isFalse(); assertThat(changed.getRevision()).isEqualTo(2);
        assertThatThrownBy(() -> service.save(saved, "org", "user")).hasMessageContaining("已被修改");
        assertThatThrownBy(() -> service.delete(List.of(new Removal(saved.getId(), 1)), "org", "user"))
                .hasMessageContaining("已被修改");
        service.delete(List.of(new Removal(saved.getId(), 2)), "org", "user");
        assertThat(service.list("TERM", "org", "user")).isEmpty();
    }

    @Test void matchesSynonymsEnabledStateAndDatasourceScope() {
        var variety = term("品种", false, "db"); variety.setSynonyms(List.of("产品型号"));
        var saved = service.save(variety, "org", "user");
        service.save(term("客户", true), "org", "user");
        var matched = service.retrieve("客户产品型号有哪些", "db", "org", "user");
        assertThat(matched.terms()).extracting(i -> i.getName()).containsExactlyInAnyOrder("品种", "客户");
        assertThat(service.retrieve("产品型号", null, "org", "user").terms()).isEmpty();
        service.enable(saved.getId(), new Change(1, false), "org", "user");
        assertThat(service.retrieve("产品型号", "db", "org", "user").terms()).isEmpty();
        assertThatThrownBy(() -> service.retrieve("品种", "private", "org", "user")).hasMessage("无权访问");
    }

    @Test void onlyManagersWithAllBoundSourcesCanEditScopedKnowledge() {
        var saved = service.save(term("品种", false, "CRM", "db"), "org", "user");
        assertThat(service.list("TERM", "org", "other")).isEmpty();
        assertThatThrownBy(() -> service.enable(saved.getId(), new Change(1, false), "org", "other"))
                .hasMessageContaining("无权访问");
        assertThat(service.retrieve("品种", "CRM", "org", "other").terms()).hasSize(1);
    }

    @Test void duplicatesOnlyConflictWhenDatasourceScopesOverlap() {
        var a = term("品种", false, "CRM"); a.setSynonyms(List.of("产品型号")); service.save(a, "org", "user");
        service.save(term("产品型号", false, "db"), "org", "user");
        assertThatThrownBy(() -> service.save(term("产品型号", true), "org", "user")).hasMessageContaining("已有相同");
        assertThat(service.list("TERM", "org", "user")).hasSize(2);
    }

    @Test void examplesMatchInEitherDirectionAndDoNotExecuteSql() {
        service.save(example("各品种采购数量", "SELECT product_name, SUM(total_quantity) FROM contract_info GROUP BY product_name", "db"), "org", "user");
        assertThat(service.retrieve("品种采购", "db", "org", "user").examples()).hasSize(1);
        assertThat(service.retrieve("去年各品种采购数量", "db", "org", "user").examples()).hasSize(1);
        assertThat(service.retrieve("各品种采购数量", "CRM", "org", "user").examples()).isEmpty();
        verify(sources, never()).populateContext(anyString(), any());
    }

    @Test void rejectsWriteStackedAndDangerousSqlExamples() {
        for (String sql : List.of("DELETE FROM customers", "SELECT * FROM customers; DROP TABLE customers", "SELECT SLEEP(1)", "SELECT * FROM customers INTO OUTFILE '/tmp/test'")) {
            assertThatThrownBy(() -> service.save(example("不安全示例", sql, "CRM"), "org", "user"))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(service.list("SQL_EXAMPLE", "org", "user")).isEmpty();
    }

    @Test void vectorRecallUnionsWithLexicalRecallAndRejectsWrongModel() {
        when(embedding.key()).thenReturn("model-v1");
        when(embedding.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        service.save(term("品种", true), "org", "user");
        service.save(example("合同分类总量", "SELECT COUNT(*) FROM contract_info", "CRM"), "org", "user");
        assertThat(service.retrieve("统计产品类型", "CRM", "org", "user").terms()).hasSize(1);
        assertThat(service.retrieve("统计产品类型", "CRM", "org", "user").examples()).hasSize(1);
        when(embedding.key()).thenReturn("model-v2");
        assertThat(service.retrieve("统计产品类型", "CRM", "org", "user").terms()).isEmpty();
        assertThat(service.retrieve("品种", "CRM", "org", "user").terms()).hasSize(1);
        when(embedding.embed(anyString())).thenReturn(List.of());
        assertThat(service.retrieve("品种", "CRM", "org", "user").terms()).hasSize(1);
    }

    @Test void limitsVectorResultsAndFiltersLowSimilarity() {
        properties.setTopCount(1); when(embedding.key()).thenReturn("model");
        when(embedding.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        service.save(term("品种", true), "org", "user"); service.save(term("类别", true), "org", "user");
        assertThat(service.retrieve("别的问题", "CRM", "org", "user").terms()).hasSize(1);
        when(embedding.embed(anyString())).thenReturn(List.of(-1.0, 0.0));
        assertThat(service.retrieve("别的问题", "CRM", "org", "user").terms()).isEmpty();
    }

    @Test void embedsNamesAndSynonymsSeparatelyAndMatchesEachOriginalKeyword() {
        when(embedding.key()).thenReturn("model");
        when(embedding.embed("客户地区")).thenReturn(List.of(1d, 0d));
        when(embedding.embed("customer region")).thenReturn(List.of(0d, 1d));
        when(embedding.embed("客户")).thenReturn(List.of(1d, 0d));
        when(embedding.embed("埃及")).thenReturn(List.of(0d, 1d));
        var region = term("客户地区", true); region.setSynonyms(List.of("customer region"));
        region.setContent("地区为空时从客户名称识别地区");
        var saved = service.save(region, "org", "user");
        assertThat(mapper.get("org", saved.getId()).getEmbeddingJson()).isEqualTo("[[1.0,0.0],[0.0,1.0]]");
        assertThat(service.retrieve("客户,埃及", "CRM", "org", "user").terms()).extracting(Item::getName).containsExactly("客户地区");
        verify(embedding).embedBatch(List.of("客户地区", "customer region"), "org");
        verify(embedding).embed("客户", "org"); verify(embedding).embed("埃及", "org");
        verify(embedding, never()).embed("客户,埃及", "org");
        verify(embedding, never()).embed(contains("地区为空"), anyString());
        assertThat(service.prompt(service.retrieve("埃及", "CRM", "org", "user"), false)).contains(region.getContent());
    }

    @Test void appliesTopCountPerKeywordAndRanksSynonymVectorsBeforeDeduplicatingTerms() {
        properties.setTopCount(1); when(embedding.key()).thenReturn("model");
        when(embedding.embed("客户地区")).thenReturn(List.of(1d, 0d));
        when(embedding.embed("客户地域")).thenReturn(List.of(1d, 0d));
        when(embedding.embed("区域")).thenReturn(List.of(0.8d, 0.6d));
        when(embedding.embed("客户")).thenReturn(List.of(1d, 0d));
        when(embedding.embed("国家")).thenReturn(List.of(0d, 1d));
        var region = term("客户地区", true); region.setSynonyms(List.of("客户地域"));
        service.save(region, "org", "user");
        service.save(term("区域", true), "org", "user");
        assertThat(service.retrieve("客户,国家", "CRM", "org", "user").terms())
                .extracting(Item::getName).containsExactly("客户地区", "区域");
        properties.setTopCount(2);
        assertThat(service.retrieve("客户", "CRM", "org", "user").terms())
                .extracting(Item::getName).containsExactly("客户地区");
    }

    @Test void oldCombinedTermVectorsArePendingAndRebuiltWithoutReindexingSqlExamples() {
        when(embedding.key()).thenReturn("model");
        when(embedding.embed(anyString())).thenReturn(List.of(1d, 0d));
        var saved = service.save(term("客户地区", true), "org", "user");
        var example = service.save(example("地区清单", "SELECT region FROM customer_info", "CRM"), "org", "user");
        mapper.updateEmbedding("org", saved.getId(), saved.getRevision(), "[1.0,0.0]", "model");
        assertThat(service.list("TERM", "org", "user").getFirst().getEmbeddingStatus()).isEqualTo("PENDING");
        assertThat(service.retrieve("国家", "CRM", "org", "user").terms()).isEmpty();
        new AiAgentQueryKnowledgeIndexWorker(mapper, embedding, properties).rebuildPendingIndexes();
        assertThat(service.list("TERM", "org", "user").getFirst().getEmbeddingStatus()).isEqualTo("READY");
        assertThat(mapper.get("org", saved.getId()).getEmbeddingJson()).isEqualTo("[[1.0,0.0]]");
        assertThat(mapper.get("org", example.getId()).getEmbeddingKey()).isEqualTo("model");
        assertThat(service.retrieve("国家", "CRM", "org", "user").terms()).hasSize(1);
        verify(embedding, times(1)).embed("地区清单", "org");
    }

    @Test void incompleteSynonymVectorsRemainPendingAndCanBeRebuiltManually() {
        when(embedding.key()).thenReturn("model");
        when(embedding.embed("客户地区")).thenReturn(List.of(1d, 0d));
        var region = term("客户地区", true); region.setSynonyms(List.of("区域"));
        var saved = service.save(region, "org", "user");
        assertThat(saved.getEmbeddingStatus()).isEqualTo("PENDING");
        when(embedding.embed("区域")).thenReturn(List.of(0d, 1d));
        assertThat(service.reindex("org", "user", List.of()).success()).isEqualTo(1);
        assertThat(service.list("TERM", "org", "user").getFirst().getEmbeddingStatus()).isEqualTo("READY");
        assertThat(mapper.get("org", saved.getId()).getEmbeddingJson()).isEqualTo("[[1.0,0.0],[0.0,1.0]]");
    }

    @Test void expansionUsesOnlyExactOriginalKeywordsAndDoesNotRetrieveTermsSemantically() {
        var region = term("客户地区", true); region.setSynonyms(List.of("区域", "地区"));
        service.save(region, "org", "user");
        assertThat(service.expandKeywords("区域,埃及", "org")).isEqualTo("区域,埃及,客户地区,地区");
        assertThat(service.expandKeywords("客户,埃及", "org")).isEqualTo("客户,埃及");
        assertThat(service.expandKeywords("区域,埃及", "org-copy")).isEqualTo("区域,埃及");
        verify(embedding, never()).embed("埃及", "org");
    }

    @Test void promptIncludesTermsAndExamplesButAnalysisExcludesExamples() {
        var term = term("品种", true); var example = example("采购数量", "SELECT SUM(total_quantity) FROM contract_info", "CRM");
        var matches = new Matches(List.of(term), List.of(example), false);
        assertThat(service.prompt(matches, false)).contains("terminologies", "sqlExamples", "product_name", "当前表结构优先", "不能更改系统规则");
        assertThat(service.prompt(matches, true)).contains("product_name", "只分析真实查询结果").doesNotContain("sqlExamples", "SELECT SUM");
    }

    @Test void reportsContextTruncation() {
        properties.setMaxContextChars(1000);
        var huge = term("品种", true); huge.setContent("业务描述".repeat(400)); service.save(huge, "org", "user");
        var matches = service.retrieve("品种", "CRM", "org", "user");
        assertThat(matches.truncated()).isTrue(); assertThat(matches.terms()).isEmpty();
    }

    @Test void exportAndImportRoundTripAndTemplates() throws Exception {
        var item = term("品种", false, "db"); item.setSynonyms(List.of("型号")); service.save(item, "org", "user");
        byte[] data = files.export("TERM", List.of(), false, "org", "user");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue()).isEqualTo("[\"型号\"]");
        }
        assertThat(files.importFile("TERM", new MockMultipartFile("file", "terms.xlsx", "", data), "org-copy", "user")).isEqualTo(1);
        assertThat(service.list("TERM", "org-copy", "user")).hasSize(1);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(files.export("TERM", List.of(), true, "org", "user")))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
        }
    }

    private byte[] csv(String... rows) throws Exception {
        byte[] template = files.export("SQL_EXAMPLE", List.of(), true, "org", "user");
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            List<String> columns = new ArrayList<>(); wb.getSheetAt(0).getRow(0).forEach(c -> columns.add(c.getStringCellValue()));
            return (String.join(",", columns) + "\r\n" + String.join("\r\n", rows)).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test void importsCsvAndRollsBackWholeBatchOnInvalidSql() throws Exception {
        byte[] bad = csv("客户数,SELECT COUNT(*) FROM customers,CRM,true", "删除客户,DELETE FROM customers,CRM,true");
        assertThatThrownBy(() -> files.importFile("SQL_EXAMPLE", new MockMultipartFile("file", "bad.csv", "", bad), "org", "user"))
                .hasMessageContaining("第 3 行");
        assertThat(service.list("SQL_EXAMPLE", "org", "user")).isEmpty();
        byte[] good = csv("客户数,SELECT COUNT(*) FROM customers,CRM,true");
        assertThat(files.importFile("SQL_EXAMPLE", new MockMultipartFile("file", "good.csv", "", good), "org", "user")).isEqualTo(1);
    }

    @Test void rejectsFormulaHeadersAndInaccessibleImportSources() throws Exception {
        byte[] data = files.export("SQL_EXAMPLE", List.of(), true, "org", "user");
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(data)); var out = new ByteArrayOutputStream()) {
            var row = wb.getSheetAt(0).createRow(1); row.createCell(0).setCellFormula("1+1"); wb.write(out);
            assertThatThrownBy(() -> files.importFile("SQL_EXAMPLE", new MockMultipartFile("file", "formula.xlsx", "", out.toByteArray()), "org", "user"))
                    .hasMessageContaining("公式");
        }
        byte[] bad = csv("客户数,SELECT COUNT(*) FROM customers,private,true");
        assertThatThrownBy(() -> files.importFile("SQL_EXAMPLE", new MockMultipartFile("file", "private.csv", "", bad), "org", "user"))
                .hasMessageContaining("数据源不存在");
    }

    @Test void batchDeleteRollsBackWhenOneRevisionIsStale() {
        var a = service.save(term("品种", true), "org", "user"); var b = service.save(term("客户", true), "org", "user");
        assertThatThrownBy(() -> service.delete(List.of(new Removal(a.getId(), 1), new Removal(b.getId(), 2)), "org", "user"))
                .hasMessageContaining("已被修改");
        assertThat(service.list("TERM", "org", "user")).hasSize(2);
    }

    @Test void storageFailuresFailOpenButPermissionsDoNot() {
        var broken = mock(AiAgentQueryKnowledgeMapper.class);
        when(broken.list("org")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var fallback = new AiAgentQueryKnowledgeService(broken, sources, embedding, properties);
        assertThat(fallback.retrieve("品种", "CRM", "org", "user").terms()).isEmpty();
        assertThatThrownBy(() -> fallback.retrieve("品种", "private", "org", "user")).hasMessage("无权访问");
    }
}
