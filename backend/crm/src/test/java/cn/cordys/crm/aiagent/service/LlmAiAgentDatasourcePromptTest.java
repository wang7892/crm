package cn.cordys.crm.aiagent.service;
import cn.cordys.crm.aiagent.config.*;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class LlmAiAgentDatasourcePromptTest {
    @Test void retrievesTerminologyUsingOriginalKeywordsAfterTableSelection() {
        var props=new AiAgentLlmProperties(); props.setEnabled(true); var llm=mock(AiAgentLlmClient.class);
        var schema=mock(AiAgentSemanticSchemaService.class);
        var parser=new LlmAiAgentQuestionParser(props,llm,mock(AiAgentIntentValidator.class),schema,mock(AiAgentParsedQuestionNormalizer.class));
        var c=AiAgentDatasourceTest.queryContext("MYSQL"); c.setOrganizationId("org"); c.setUserId("user");
        var knowledge=mock(AiAgentQueryKnowledgeService.class);
        when(llm.chatTextQuiet(anyString(),eq("埃及有哪些客户"),any(),anyList())).thenReturn("客户,埃及");
        when(knowledge.expandKeywords("客户,埃及","org")).thenReturn("客户,埃及,客户地区");
        when(knowledge.retrieve(anyString(),any(),any(),any())).thenReturn(AiAgentQueryKnowledge.Matches.empty());
        var selection=new AiAgentTableSelectionService(llm,schema);
        ReflectionTestUtils.setField(selection,"knowledge",knowledge);
        ReflectionTestUtils.setField(parser,"tableSelectionService",selection);
        ReflectionTestUtils.setField(parser,"queryKnowledgeService",knowledge);
        when(llm.chat(anyString(),anyString(),eq("primary"),anyList())).thenReturn("{\"success\":true,\"sql\":\"SELECT amount FROM products\",\"chart-type\":\"table\"}");
        assertThat(parser.parse("埃及有哪些客户","primary",c).getCandidateSql()).contains("SELECT amount");
        verify(knowledge).retrieve(eq("客户,埃及"),eq(c.getDatasourceId()),eq("org"),eq("user"));
        verify(knowledge,never()).retrieve(eq("客户,埃及,客户地区"),any(),any(),any());
    }
    @ParameterizedTest @CsvSource({"MYSQL,MySQL","POSTGRESQL,PostgreSQL","ORACLE,Oracle","SQLSERVER,Microsoft SQL Server 2012+","FILE,H2"})
    void usesNativeTemplateForChosenDialectSchemaAndKnowledge(String type,String dialect) {
        var props=new AiAgentLlmProperties(); props.setEnabled(true); var llm=mock(AiAgentLlmClient.class);
        var schema=mock(AiAgentSemanticSchemaService.class); var normalizer=mock(AiAgentParsedQuestionNormalizer.class);
        var parser=new LlmAiAgentQuestionParser(props,llm,mock(AiAgentIntentValidator.class),schema,normalizer);
        var c=AiAgentDatasourceTest.queryContext(type);
        var knowledge=mock(AiAgentQueryKnowledgeService.class);
        when(knowledge.retrieve(anyString(),any(),any(),any())).thenReturn(AiAgentQueryKnowledge.Matches.empty());
        when(knowledge.prompt(any(),eq(false))).thenReturn("营业额对应 products.amount 的合计");
        ReflectionTestUtils.setField(parser,"queryKnowledgeService",knowledge);
        when(llm.chat(anyString(),anyString(),eq("primary"),anyList())).thenReturn("{\"success\":true,\"sql\":\"SELECT SUM(amount) AS total FROM products\",\"chart-type\":\"table\",\"brief\":\"营业额统计\"}");
        var result=parser.parse("营业额是多少","primary",c);
        assertThat(result.getCandidateSql()).contains("SUM(amount)"); assertThat(result.getQueryPlan()).isNull();
        verify(llm).chat(argThat(p -> p.contains(dialect) && p.contains("products") && p.contains("营业额对应")),argThat(p -> p.contains("营业额是多少")),eq("primary"),eq(List.of()));
        verifyNoInteractions(schema,normalizer);
    }
}
