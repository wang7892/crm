package cn.cordys.crm.aiagent.service;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.*;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessDetail;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentTableSelectionServiceTest {
    private Table table(String name,String comment) {
        var t=new Table(); t.setName(name); t.setComment(comment);
        var c=new Column(); c.setName("amount"); c.setComment("金额"); c.setType("DECIMAL"); t.setColumns(List.of(c)); return t;
    }
    @Test void keywordScoresUseSqlBotsCoverageFormula() {
        var t=table("sales_order","销售订单");
        assertThat(AiAgentTableSelectionService.keywordScore("sales_order,金额",t)).isEqualTo(1);
        assertThat(AiAgentTableSelectionService.keywordScore("销售订单,金额",t)).isEqualTo(0.9);
        assertThat(AiAgentTableSelectionService.keywordScore("sales,other",t)).isEqualTo(0.19);
        assertThat(AiAgentTableSelectionService.keywordScore("金额,other",t)).isEqualTo(0.25);
    }
    @Test void exactTablesStaySelectedAndTenRecallSlotsAreFilledWithoutRepeatingNamedTables() {
        var llm=mock(AiAgentLlmClient.class); when(llm.chatTextQuiet(anyString(),anyString(),any(),anyList())).thenReturn("table_11,金额");
        var service=new AiAgentTableSelectionService(llm,new AiAgentSemanticSchemaService());
        var context=new AiAgentContext(); context.setDatasourceId("source");
        var tables=new ArrayList<Table>(); for(int i=0;i<12;i++)tables.add(table("table_"+i,"业务数据"));
        context.setDatasourceSchema(tables); context.setSqlHistory(List.of(new AiAgentConversationMessage("user","上月金额"),new AiAgentConversationMessage("assistant","SELECT 1 AS amount")));
        service.select("table_11金额",context);
        assertThat(context.getSelectedQueryTables()).hasSize(10).contains("table_11");
        verify(llm).chatTextQuiet(anyString(),eq("table_11金额"),any(),argThat(h -> h.size()==1 && h.getFirst().role().equals("user")));
        verify(llm,never()).chat(anyString(),anyString(),any());
    }
    @Test void batchesMissingStructureVectorsAndReusesCache() {
        var llm=mock(AiAgentLlmClient.class); when(llm.chatTextQuiet(anyString(),anyString(),any(),anyList())).thenReturn("金额");
        var embedding=mock(AiAgentQueryKnowledgeEmbedding.class); when(embedding.key(any())).thenReturn("model-v1");
        when(embedding.embed(anyString(),any())).thenReturn(List.of(1d,0d));
        when(embedding.embedBatch(anyList(),any())).thenAnswer(i -> Collections.nCopies(((List<?>)i.getArgument(0)).size(),List.of(1d,0d)));
        var service=new AiAgentTableSelectionService(llm,new AiAgentSemanticSchemaService());
        ReflectionTestUtils.setField(service,"embedding",embedding);
        var context=new AiAgentContext(); context.setOrganizationId("org"); context.setDatasourceId("source");
        context.setDatasourceSchema(List.of(table("sales","销售"),table("cost","成本")));
        service.select("金额",context); service.select("金额",context);
        verify(embedding,times(1)).embedBatch(argThat(t -> t.size()==2),eq("org"));
    }
    @Test void relationExpansionKeepsBridgeTable() {
        var relation1=new Relation(); relation1.setSourceTable("a"); relation1.setTargetTable("bridge");
        var relation2=new Relation(); relation2.setSourceTable("bridge"); relation2.setTargetTable("b");
        var selected=new LinkedHashSet<>(List.of("a","b"));
        AiAgentTableSelectionService.expandPaths(selected,List.of(relation1,relation2),Set.of("a","b","bridge"));
        assertThat(selected).contains("a","b","bridge");
    }
    @Test void preservesOriginalKeywordsForTermRetrievalAndUsesExpansionOnlyForTableSelection() {
        var llm=mock(AiAgentLlmClient.class);
        when(llm.chatTextQuiet(anyString(),anyString(),any(),anyList())).thenReturn("区域,埃及");
        var knowledge=mock(AiAgentQueryKnowledgeService.class);
        when(knowledge.expandKeywords("区域,埃及","org")).thenReturn("区域,埃及,客户地区");
        var embedding=mock(AiAgentQueryKnowledgeEmbedding.class);
        when(embedding.embed(anyString(),any())).thenReturn(List.of());
        var service=new AiAgentTableSelectionService(llm,new AiAgentSemanticSchemaService());
        ReflectionTestUtils.setField(service,"knowledge",knowledge);
        ReflectionTestUtils.setField(service,"embedding",embedding);
        var context=new AiAgentContext(); context.setOrganizationId("org"); context.setDatasourceId("source");
        context.setDatasourceSchema(List.of(table("customers","客户地区")));
        try (var progress = AiAgentProgress.captureIfAbsent()) {
            service.select("埃及有哪些客户",context);
            var extraction = AiAgentProgress.steps().stream().filter(s -> "extract_keywords".equals(s.name())).findFirst().orElseThrow();
            assertThat(extraction.details()).containsExactly(new ProcessDetail("text", "提取结果", "区域,埃及"));
            assertThat(extraction.status()).isEqualTo("SUCCESS");
            assertThat(extraction.durationMs()).isNotNegative();
            var response = new AiAgentChatResponse(); response.setProcessSteps(AiAgentProgress.finish(false));
            var saved = JSON.parseObject(JSON.toJSONString(response), AiAgentChatResponse.class);
            assertThat(saved.getProcessSteps().getFirst().details()).isEqualTo(extraction.details());
            assertThat(saved.getProcessSteps().getLast().details()).noneMatch(d -> "提取关键词".equals(d.title()));
        }
        assertThat(context.getExtractedKeywords()).isEqualTo("区域,埃及");
        verify(embedding).embed("区域,埃及,客户地区","org");
        verify(knowledge,never()).retrieve(any(),any(),any(),any());
    }
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"EMPTY", "ERROR: invalid query", "  "})
    void unavailableExtractionRecordsActualFallbackInputInsteadOfAnEmptyResult(String output) {
        var llm = mock(AiAgentLlmClient.class);
        when(llm.chatTextQuiet(anyString(), anyString(), any(), anyList())).thenReturn(output);
        assertExtractionFallback(llm);
    }
    @Test void extractionExceptionStillRecordsTheOriginalQuestionUsedForRetrieval() {
        var llm = mock(AiAgentLlmClient.class);
        when(llm.chatTextQuiet(anyString(), anyString(), any(), anyList())).thenThrow(new IllegalStateException("unavailable"));
        assertExtractionFallback(llm);
    }
    private void assertExtractionFallback(AiAgentLlmClient llm) {
        var service = new AiAgentTableSelectionService(llm, new AiAgentSemanticSchemaService());
        var context = new AiAgentContext(); context.setDatasourceId("source");
        context.setDatasourceSchema(List.of(table("customers", "客户")));
        try (var progress = AiAgentProgress.captureIfAbsent()) {
            service.select("埃及有哪些客户", context);
            var extraction = AiAgentProgress.steps().getFirst();
            assertThat(extraction.name()).isEqualTo("extract_keywords");
            assertThat(extraction.summary()).contains("使用原问题检索");
            assertThat(extraction.details()).containsExactly(new ProcessDetail("text", "检索输入（使用原问题）", "埃及有哪些客户"));
            assertThat(extraction.status()).isEqualTo("FAILED");
            assertThat(context.getExtractedKeywords()).isEqualTo("埃及有哪些客户");
            assertThat(context.getSelectedQueryTables()).contains("customers");
        }
    }
    @Test void unavailableExtractionAndEmbeddingDoNotBlockSqlGeneration() {
        var service=new AiAgentTableSelectionService(mock(AiAgentLlmClient.class),new AiAgentSemanticSchemaService());
        var context=new AiAgentContext(); context.setDatasourceId("source"); context.setDatasourceSchema(List.of(table("a","A"),table("b","B")));
        service.select("unrelated",context); assertThat(context.getSelectedQueryTables()).containsExactly("a","b");
    }
}
