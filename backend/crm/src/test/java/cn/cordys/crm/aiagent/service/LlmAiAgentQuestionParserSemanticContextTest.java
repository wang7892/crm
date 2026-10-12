package cn.cordys.crm.aiagent.service;
import cn.cordys.crm.aiagent.config.*;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeSearchTestResponse;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmAiAgentQuestionParserSemanticContextTest {
    private AiAgentLlmClient llm; private LlmAiAgentQuestionParser parser; private AiAgentContext context;
    @BeforeEach void setup() {
        var props=new AiAgentLlmProperties(); props.setEnabled(true); llm=mock(AiAgentLlmClient.class);
        var validator=new AiAgentIntentValidator();
        parser=new LlmAiAgentQuestionParser(props,llm,validator,new AiAgentSemanticSchemaService(),new AiAgentParsedQuestionNormalizer(validator));
        context=new AiAgentContext(); context.setOrganizationId("org"); context.setUserId("user");
        when(llm.chat(anyString(),anyString(),any(),anyList())).thenReturn("{\"success\":true,\"sql\":\"SELECT 1 AS n\",\"chart-type\":\"table\"}");
    }
    @Test void ignoresRetiredDocumentRewriteAndUsesOriginalQuestion() {
        var retired=new AiKnowledgeSearchTestResponse(); retired.setAnswerPreview("retired document"); retired.setRewriteQuestion("rewritten query");
        context.setKnowledgeSearch(retired); parser.parse("每个品种数量","provider",context);
        verify(llm).chat(argThat(p -> !p.contains("retired document")),argThat(p -> p.contains("每个品种数量") && !p.contains("rewritten query")),eq("provider"),anyList());
    }
    @Test void retrievesKnowledgeAfterKeywordTableSelectionAndSamplesBeforeGeneratingSql() {
        var tables=mock(AiAgentTableSelectionService.class); var knowledge=mock(AiAgentQueryKnowledgeService.class); var samples=mock(AiAgentSampleDataService.class);
        ReflectionTestUtils.setField(parser,"tableSelectionService",tables); ReflectionTestUtils.setField(parser,"queryKnowledgeService",knowledge); ReflectionTestUtils.setField(parser,"sampleDataService",samples);
        doAnswer(i -> { context.setExtractedKeywords("品种,数量"); context.setSelectedQueryTables(List.of("contract_info")); return null; }).when(tables).select(anyString(),same(context));
        when(knowledge.retrieve(eq("品种,数量"),any(),any(),any())).thenReturn(AiAgentQueryKnowledge.Matches.empty());
        when(knowledge.prompt(any(),eq(false))).thenReturn("品种数量统计术语及示例 SQL");
        when(samples.read(context)).thenReturn("[{\"product_name\":\"样例品种\"}]");
        parser.parse("每个品种数量","provider",context);
        var order=inOrder(tables,knowledge,samples,llm);
        order.verify(tables).select("每个品种数量",context);
        order.verify(knowledge).retrieve("品种,数量",null,"org","user");
        order.verify(samples).read(context);
        order.verify(knowledge).prompt(any(),eq(false));
        order.verify(llm).chat(argThat(p -> p.contains("品种数量统计术语") && p.contains("样例品种")),anyString(),eq("provider"),anyList());
    }
    @Test void noGlobalPurchaseBusinessRulesAreInjectedWithoutTerminology() {
        parser.parse("客户采购数量","provider",context);
        verify(llm).chat(argThat(p -> !p.contains("默认以 delivery_date") && !p.contains("必须追加非空过滤") && !p.contains("全局业务口径")),anyString(),eq("provider"),anyList());
    }
    @Test void sqlGenerationDisabledDoesNotRestoreLegacyPlanRoute() {
        var props=new AiAgentSqlProperties(); props.setTextToSqlEnabled(false); ReflectionTestUtils.setField(parser,"sqlProperties",props);
        assertThat(parser.parse("品种数量","provider",context)).isNull(); verifyNoInteractions(llm);
    }
}
