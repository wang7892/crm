package cn.cordys.crm.aiagent.service;
import cn.cordys.crm.aiagent.config.*;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.request.AiAgentChatRequest;
import cn.cordys.crm.aiagent.dto.response.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentTextToSqlChatTest {
    private AiAgentChatService chat;
    private AiAgentSqlQueryService sql;
    private LlmAiAgentQuestionParser parser;
    private AiAgentContext context;
    @BeforeEach void setup() {
        chat=new AiAgentChatService(); sql=mock(AiAgentSqlQueryService.class); parser=mock(LlmAiAgentQuestionParser.class);
        var p=new AiAgentLlmProperties(); p.setEnabled(true); p.setMinConfidence(0.99);
        ReflectionTestUtils.setField(chat,"aiAgentSqlQueryService",sql); ReflectionTestUtils.setField(chat,"llmAiAgentQuestionParser",parser);
        ReflectionTestUtils.setField(chat,"aiAgentLlmProperties",p); when(sql.isTextToSqlEnabled()).thenReturn(true);
        context=new AiAgentContext(); context.setUserId("user"); context.setOrganizationId("org"); context.setQueryQuestion("客户有哪些");
    }
    private ParsedAiAgentQuestion parsed(String query) {
        var p=new ParsedAiAgentQuestion(); p.setCandidateSql(query); p.setSource("LLM"); p.setChartType("pie"); p.setConfidence(0.1);
        when(parser.parse(eq(context.getQueryQuestion()),any(),same(context))).thenReturn(p); return p;
    }
    private AiAgentChatResponse route() { return ReflectionTestUtils.invokeMethod(chat,"route",context.getQueryQuestion(),context); }
    @Test void nativeModelSqlGoesDirectlyToExecutorWithoutConfidenceGate() {
        var p=parsed("SELECT name AS customer_name FROM customer");
        var expected=new AiAgentChatResponse(); when(sql.answer(p.getCandidateSql(),context)).thenReturn(expected);
        assertThat(route()).isSameAs(expected); assertThat(context.getRequestedChartType()).isEqualTo("pie");
        verify(parser,times(1)).parse(eq(context.getQueryQuestion()),any(),same(context));
    }
    @Test void failureIsPersistableAndDoesNotAutomaticallyGenerateOrExecuteAnotherSql() {
        var p=parsed("SELECT bad AS n FROM customer"); when(sql.answer(p.getCandidateSql(),context)).thenThrow(new IllegalArgumentException("unknown field"));
        var response=route(); assertThat(response.getQueryError()).isEqualTo("unknown field");
        assertThat(response.getSqlQueries()).containsExactly(p.getCandidateSql());
        assertThat(response.getQueryQuestion()).isEqualTo(context.getQueryQuestion());
        verify(parser,times(1)).parse(anyString(),any(),any()); verify(sql,times(1)).answer(anyString(),any());
    }
    @Test void unavailableOrRefusedGenerationNeverExecutesSql() {
        assertThat(route().getQueryError()).contains("未能生成");
        var p=parsed(null); p.setNeedClarification(true); p.setClarificationQuestion("表结构不支持");
        assertThat(route().getAnswer()).isEqualTo("表结构不支持"); verify(sql,never()).answer(anyString(),any());
    }
    @Test void customDatasourceDoesNotResolveCrmPermissions() {
        var sources=mock(AiAgentDatasourceService.class); ReflectionTestUtils.setField(chat,"datasourceService",sources);
        doAnswer(i -> { ((AiAgentContext)i.getArgument(1)).setDatasourceId("source"); return null; }).when(sources).populateContext(eq("source"),any());
        var request=new AiAgentChatRequest(); request.setQuestion("商品金额"); request.setDatasourceId("source"); request.setLimitRows(false);
        var c=chat.createContext("user","org",request); assertThat(c.getDatasourceId()).isEqualTo("source");
        assertThat(c.isLimitRows()).isFalse(); assertThat(c.getCustomerDataPermission()).isNull();
    }
    @Test void continuationReusesStoredSqlAndCurrentPermissionsWithoutModel() {
        var audit=mock(AiAgentAuditService.class); ReflectionTestUtils.setField(chat,"aiAgentAuditService",audit);
        var cursor=new AiAgentQueryContinuation(); cursor.setGeneratedSql("SELECT name AS n FROM customer"); cursor.setQuestion("客户有哪些");
        cursor.setPageSize(30); cursor.setNextOffset(30);
        when(audit.loadContinuation("message","session",context)).thenReturn(cursor);
        when(sql.answer(cursor.getGeneratedSql(),context,30,30)).thenReturn(new AiAgentChatResponse());
        var request=new AiAgentChatRequest(); request.setQuestion("继续查看"); request.setContinueFromMessageId("message");
        ReflectionTestUtils.invokeMethod(chat,"continueQuery",request,"session",context);
        verify(sql).answer(cursor.getGeneratedSql(),context,30,30); verifyNoInteractions(parser);
    }
    @Test void nativeSqlPromptContainsSamplesAndReplaysOriginalSqlHistory() {
        var props=new AiAgentLlmProperties(); props.setEnabled(true); var llm=mock(AiAgentLlmClient.class);
        var validator=new AiAgentIntentValidator();
        var generator=new LlmAiAgentQuestionParser(props,llm,validator,new AiAgentSemanticSchemaService(),new AiAgentParsedQuestionNormalizer(validator));
        context.setSampleData("[{\"name\":\"实际客户\"}]");
        context.setSqlHistory(List.of(new AiAgentConversationMessage("user","今年订单金额"),new AiAgentConversationMessage("assistant","{\"sql\":\"SELECT SUM(amount) AS n FROM contract_info\"}")));
        when(llm.chat(anyString(),anyString(),any(),eq(context.getSqlHistory()))).thenReturn("{\"success\":true,\"sql\":\"SELECT 1 AS n\",\"chart-type\":\"table\",\"brief\":\"查询\"}");
        var answer=generator.parse("上月呢",null,context);
        assertThat(answer.getCandidateSql()).isEqualTo("SELECT 1 AS n");
        verify(llm).chat(argThat(s -> s.contains("实际客户") && s.contains("customer")),argThat(s -> s.contains("上月呢")),isNull(),eq(context.getSqlHistory()));
    }
}
