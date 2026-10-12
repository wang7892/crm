package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge;
import cn.cordys.crm.aiagent.dto.query.*;
import cn.cordys.crm.aiagent.dto.response.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentSqlBotResultServiceTest {
    private AiAgentLlmClient llm;
    private AiAgentSqlBotResultService service;
    private AiAgentChatResponse previous;
    private AiAgentContext context;
    @BeforeEach void setup() {
        llm=mock(AiAgentLlmClient.class); var parser=mock(LlmAiAgentQuestionParser.class);
        when(parser.schema(any())).thenReturn("sales(month,amount)");
        var knowledge=mock(AiAgentQueryKnowledgeService.class);
        when(knowledge.retrieve(any(),any(),any(),any())).thenReturn(AiAgentQueryKnowledge.Matches.empty());
        when(knowledge.prompt(any(),anyBoolean())).thenReturn("");
        service=new AiAgentSqlBotResultService(llm,new AiAgentSqlBotPrompts(),new AiAgentChartService(),parser,knowledge);
        context=new AiAgentContext(); context.setOrganizationId("org"); context.setUserId("user"); context.setQueryQuestion("每月收入折线图"); context.setRequestedChartType("line");
        previous=new AiAgentChatResponse(); previous.setQueryQuestion(context.getQueryQuestion()); previous.setSqlQueries(List.of("SELECT month AS month, amount AS amount FROM sales"));
        var table=new AiAgentResultTable(); table.setColumns(List.of(new AiAgentResultTable.Column("month","月份","string"),new AiAgentResultTable.Column("amount","收入","number")));
        table.setRows(List.of(Map.of("month","2026-01","amount",100),Map.of("month","2026-02","amount",200))); previous.setResultTable(table);
        previous.setChartConfig(Map.of("type","line","axis",Map.of("x",Map.of("name","月份","value","month"),"y",List.of(Map.of("name","收入","value","amount")))));
    }
    @Test void modelConfigMapsActualQueryColumnsAndRecommendationsArePersistable() {
        when(llm.chat(anyString(),anyString(),any())).thenReturn("```json\n" + JSON.toJSONString(previous.getChartConfig()) + "\n```");
        when(llm.chatTextQuiet(anyString(),anyString(),any(),anyList())).thenReturn("[\"按季度统计收入\",\"收入最高的月份\",\"每月收入折线图\"]");
        service.decorate(previous,context);
        assertThat(previous.getCharts().getFirst().getSeries().getFirst().values()).containsExactly(100d,200d);
        assertThat(previous.getRecommendedQuestions()).containsExactly("按季度统计收入","收入最高的月份");
        var restored=JSON.parseObject(JSON.toJSONString(previous),AiAgentChatResponse.class);
        assertThat(restored.getResultTable().getRows()).hasSize(2); assertThat(restored.getChartConfig()).containsEntry("type","line");
        verify(llm).chat(anyString(),argThat(p -> p.contains("SELECT month") && p.contains("每月收入折线图")),any());
    }
    @Test void analysisUsesReferencedRealDatasetWithoutSupplementaryQuery() {
        when(llm.chatText(anyString(),anyString(),any())).thenReturn("二月收入比一月增加100。");
        var response=service.analyze(previous,context,false);
        assertThat(response.getIntent()).isEqualTo("CRM_DATA_ANALYSIS");
        assertThat(response.getAnswer()).contains("增加100");
        verify(llm).chatText(argThat(p -> p.contains("根据给定的数据分析")),argThat(p -> p.contains("2026-01") && p.contains("200")),any());
        verify(llm,never()).chat(anyString(),anyString(),any());
    }
    @Test void predictionSavesSeparateTypedRowsAndDrawsOriginalAndFutureValues() {
        when(llm.chatTextQuiet(anyString(),anyString(),any(),anyList())).thenReturn("```json\n[{\"month\":\"2026-03\",\"amount\":300},{\"month\":\"2026-04\",\"amount\":400}]\n```");
        var response=service.analyze(previous,context,true);
        assertThat(response.getPredictionTable().getRows()).hasSize(2);
        assertThat(response.getPredictionTable().getRows().getFirst()).containsEntry("month","2026-03");
        assertThat(response.getCharts().getFirst().getSeries().getFirst().values()).containsExactly(100d,200d,300d,400d);
        assertThat(response.getCharts().getFirst().getPredictionStart()).isEqualTo(2);
        assertThat(previous.getResultTable().getRows()).hasSize(2);
        assertThat(JSON.parseObject(JSON.toJSONString(response),AiAgentChatResponse.class).getPredictionTable().getRows()).hasSize(2);
    }
    @Test void refusedOrWrongShapePredictionIsTextOnly() {
        when(llm.chatTextQuiet(anyString(),anyString(),any(),anyList())).thenReturn("抱歉，该数据无法进行预测。");
        assertThat(service.analyze(previous,context,true).getPredictionTable()).isNull();
        when(llm.chatTextQuiet(anyString(),anyString(),any(),anyList())).thenReturn("[{\"secret\":1},{\"secret\":2}]");
        assertThat(service.analyze(previous,context,true).getPredictionTable()).isNull();
    }
    @Test void optionalChartFailureKeepsResultAndSuggestions() {
        when(llm.chat(anyString(),anyString(),any())).thenThrow(new IllegalStateException("model unavailable"));
        when(llm.chatTextQuiet(anyString(),anyString(),any(),anyList())).thenReturn("[]");
        service.decorate(previous,context); assertThat(previous.getResultTable().getRows()).hasSize(2);
        assertThat(previous.getWarnings()).contains("图表配置生成失败，查询数据仍可查看。");
    }
    @Test void tableKeepsNumbersNullsAndColonNewlineTextWithoutTimestampGuessing() {
        var plan=new AiAgentQueryPlan(); plan.setQueryType("LIST"); plan.setLimit(1000); plan.setEntity("datasource");
        var result=new AiAgentQueryResult(); result.setPlan(plan); result.setEntityLabel("收入"); result.setEvidence("test");
        result.setSelectedFields(List.of("amount","name","empty")); result.setFieldLabels(Map.of("amount","金额","name","名称"));
        var row=new LinkedHashMap<String,Object>(); row.put("amount",150000000000L); row.put("name","a：b\nsecond line"); row.put("empty",null); result.setRows(List.of(row));
        var table=new AiAgentAnswerRenderer().render(result).getResultTable();
        assertThat(table.getRows().getFirst()).containsEntry("amount",150000000000L).containsEntry("empty",null).containsEntry("name","a：b\nsecond line");
        assertThat(table.getColumns().getFirst().type()).isEqualTo("number");
    }
    @Test void excelWritesModelTextAsLiteralCellsAndPreservesLongNumericIdentifiers() throws Exception {
        var table=previous.getResultTable(); table.setRows(List.of(Map.of("month","=HYPERLINK(\"example\")","amount",1234567890123456789L)));
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            ReflectionTestUtils.invokeMethod(new AiAgentChatService(),"writeSheet",workbook,"查询结果",table);
            var row=workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("1234567890123456789");
        }
    }
    @Test void nativeTemplateRenderingNeverInterpolatesUserBracesAgain() {
        assertThat(AiAgentSqlBotPrompts.format("{{\"q\":\"{question}\"}}",Map.of("question","{secret} $VALUE"))).isEqualTo("{\"q\":\"{secret} $VALUE\"}");
        context.setDatasourceType("FILE"); assertThat(AiAgentSqlBotPrompts.engine(context)).contains("H2");
        String prompt=new AiAgentSqlBotPrompts().sqlSystem(context,"sales(month,amount)","");
        assertThat(prompt).doesNotContain("{schema}","{engine}","{example_answer_1}");
    }
}
