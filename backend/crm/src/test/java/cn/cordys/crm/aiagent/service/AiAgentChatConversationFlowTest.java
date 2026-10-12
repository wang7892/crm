package cn.cordys.crm.aiagent.service;
import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.common.service.DataScopeService;
import cn.cordys.common.util.JSON;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.aiagent.config.*;
import cn.cordys.crm.aiagent.domain.*;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.request.AiAgentChatRequest;
import cn.cordys.crm.aiagent.dto.response.*;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import cn.cordys.security.SessionUtils;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentChatConversationFlowTest {
    private AiAgentChatService chat;
    private AiAgentSqlQueryService sql;
    private LlmAiAgentQuestionParser parser;
    private AiAgentSqlBotResultService results;
    private AiAgentAuditService audit;
    private List<AiAgentMessage> messages;
    private MockedStatic<SessionUtils> user;
    private MockedStatic<OrganizationContext> org;
    @BeforeEach void setup() {
        user=mockStatic(SessionUtils.class); org=mockStatic(OrganizationContext.class);
        user.when(SessionUtils::getUserId).thenReturn("user"); org.when(OrganizationContext::getOrganizationId).thenReturn("org");
        chat=new AiAgentChatService(); sql=mock(AiAgentSqlQueryService.class); parser=mock(LlmAiAgentQuestionParser.class);
        results=mock(AiAgentSqlBotResultService.class); audit=mock(AiAgentAuditService.class);
        var mapper=mock(AiAgentInternalMapper.class); messages=new ArrayList<>();
        when(mapper.listRecentConversationMessages(eq("session"),eq("org"),eq("user"),anyInt())).thenAnswer(i -> messages.reversed());
        var conv=new AiAgentConversationService(mapper,mock(AiAgentLlmClient.class),new AiAgentConversationProperties(),new AiAgentIntentValidator());
        var scopes=mock(DataScopeService.class); var all=new DeptDataPermissionDTO(); all.setAll(true);
        when(scopes.getDeptDataPermission(anyString(),anyString(),anyString(),anyString())).thenReturn(all);
        var props=new AiAgentLlmProperties(); props.setEnabled(true);
        ReflectionTestUtils.setField(chat,"conversationService",conv); ReflectionTestUtils.setField(chat,"aiAgentAuditService",audit);
        ReflectionTestUtils.setField(chat,"dataScopeService",scopes); ReflectionTestUtils.setField(chat,"aiAgentLlmProperties",props);
        ReflectionTestUtils.setField(chat,"aiAgentSqlQueryService",sql); ReflectionTestUtils.setField(chat,"llmAiAgentQuestionParser",parser);
        ReflectionTestUtils.setField(chat,"sqlBotResults",results); ReflectionTestUtils.setField(chat,"aiAgentRequestCancellationService",new AiAgentRequestCancellationService());
        var session=new AiAgentSession(); session.setId("session");
        when(audit.ensureSession(any(),anyString(),eq("user"),eq("org"))).thenReturn(session);
        when(audit.saveMessage(anyString(),anyString(),any(),any(),any(),anyString())).thenAnswer(i -> {
            var m=new AiAgentMessage(); m.setId("m"+messages.size()); m.setRole(i.getArgument(1));
            m.setContent(i.getArgument(2)); m.setEvidenceJson(i.getArgument(4)==null ? null : JSON.toJSONString(i.getArgument(4)));
            messages.add(m); return m;
        });
        when(audit.loadQueryResponse(anyString(),eq("session"),any())).thenAnswer(i -> {
            var m=messages.stream().filter(v -> v.getId().equals(i.getArgument(0))).findFirst().orElseThrow();
            return JSON.parseObject(JSON.toJSONString(JSON.parseToMap(m.getEvidenceJson()).get("response")),AiAgentChatResponse.class);
        });
        when(sql.isTextToSqlEnabled()).thenReturn(true);
        when(parser.parse(anyString(),any(),any())).thenAnswer(i -> {
            var p=new ParsedAiAgentQuestion(); p.setCandidateSql("SELECT 1 AS amount"); p.setChartType("table"); return p;
        });
        when(sql.answer(anyString(),any())).thenAnswer(i -> {
            var c=(AiAgentContext)i.getArgument(1); var r=new AiAgentChatResponse(); r.setIntent("CRM_DATABASE_QUERY");
            r.setAnswer("完成"); r.setQueryQuestion(c.getQueryQuestion()); r.setSqlQueries(List.of(i.<String>getArgument(0)));
            var t=new AiAgentResultTable(); t.setColumns(List.of(new AiAgentResultTable.Column("amount","金额","number"))); t.setRows(List.of(Map.of("amount",1)));
            r.setResultTable(t); return r;
        });
    }
    @AfterEach void close() { org.close(); user.close(); }
    private AiAgentChatRequest request(String q) { var r=new AiAgentChatRequest(); r.setSessionId("session"); r.setQuestion(q); return r; }
    @Test void followupArrivesUnchangedWithSavedUserAndSqlHistory() {
        chat.chat(request("今年金额")); chat.chat(request("上月呢"));
        verify(parser).parse(eq("上月呢"),any(),argThat(c -> c.getSqlHistory().size()==2
                && c.getSqlHistory().getFirst().content().equals("今年金额") && c.getSqlHistory().getLast().content().contains("SELECT 1")));
        assertThat(messages).hasSize(4);
    }
    @Test void answerStatisticsArePersistedAndAReferencedAnalysisStartsFreshStatistics() {
        when(parser.parse(anyString(), any(), any())).thenAnswer(i -> {
            AiAgentProgress.stage("sql_generation", "生成 SQL");
            AiAgentLlmUsage.startCall();
            AiAgentLlmUsage.reasoning("用户需要今年金额，按订单金额汇总。");
            AiAgentLlmUsage.record("primary", "model", Map.of("total_tokens", 123));
            var parsed = new ParsedAiAgentQuestion(); parsed.setCandidateSql("SELECT 1 AS amount"); return parsed;
        });
        var queried = chat.chat(request("今年金额"));
        assertThat(queried.getTotalTokens()).isEqualTo(123L);
        assertThat(queried.getDurationMs()).isNotNegative();
        var saved = JSON.parseObject(JSON.toJSONString(JSON.parseToMap(messages.getLast().getEvidenceJson()).get("response")), AiAgentChatResponse.class);
        assertThat(saved.getTotalTokens()).isEqualTo(queried.getTotalTokens());
        assertThat(saved.getDurationMs()).isEqualTo(queried.getDurationMs());
        assertThat(saved.getProcessSteps()).isEqualTo(queried.getProcessSteps());
        assertThat(saved.getReasoningContent()).isEqualTo("用户需要今年金额，按订单金额汇总。");
        var answer = new AiAgentChatResponse(); answer.setAnswer("分析完成");
        when(results.analyze(any(), any(), eq(false))).thenAnswer(i -> {
            AiAgentProgress.stage("analysis", "分析数据");
            AiAgentLlmUsage.startCall();
            AiAgentLlmUsage.reasoning("根据本次结果分析金额分布。");
            AiAgentLlmUsage.record("primary", "model", Map.of("total_tokens", 10));
            return answer;
        });
        var request = request("分析查询结果"); request.setAction("ANALYSIS"); request.setReferenceMessageId(queried.getMessageId());
        var analyzed = chat.chat(request);
        assertThat(analyzed.getTotalTokens()).isEqualTo(10L);
        assertThat(analyzed.getReasoningContent()).isEqualTo("根据本次结果分析金额分布。");
        assertThat(analyzed.getProcessSteps()).extracting(AiAgentChatResponse.ProcessStep::name).doesNotContain("sql_generation");
    }
    @Test void explicitRegenerateUsesOriginalQuestionAndSavedErrorAndExcludesReferencedSqlFromHistory() {
        doThrow(new IllegalArgumentException("unknown field")).when(sql).answer(anyString(),any());
        var failed=chat.chat(request("每月金额"));
        reset(sql); when(sql.isTextToSqlEnabled()).thenReturn(true);
        when(sql.answer(anyString(),any())).thenReturn(new AiAgentChatResponse());
        var r=request("重新生成"); r.setAction("REGENERATE"); r.setReferenceMessageId(failed.getMessageId());
        chat.chat(r);
        verify(parser,times(2)).parse(eq("每月金额"),any(),any());
        verify(parser).parse(eq("每月金额"),any(),argThat(c -> "unknown field".equals(c.getQueryError()) && c.getSqlHistory().isEmpty()));
    }
    @Test void explicitAnalysisReferencesStoredResultWithoutExecutingAnotherQuery() {
        var queried=chat.chat(request("今年金额"));
        var answer=new AiAgentChatResponse(); answer.setAnswer("分析完成"); answer.setIntent("CRM_DATA_ANALYSIS"); answer.setQueryQuestion("今年金额");
        when(results.analyze(any(),any(),eq(false))).thenReturn(answer);
        var r=request("分析查询结果"); r.setAction("ANALYSIS"); r.setReferenceMessageId(queried.getMessageId());
        assertThat(chat.chat(r).getAnswer()).isEqualTo("分析完成");
        verify(sql,times(1)).answer(anyString(),any());
        verify(results).analyze(argThat(p -> p.getResultTable().getRows().size()==1),any(),eq(false));
    }
}
