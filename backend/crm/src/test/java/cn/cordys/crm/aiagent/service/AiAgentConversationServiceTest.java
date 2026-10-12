package cn.cordys.crm.aiagent.service;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentConversationProperties;
import cn.cordys.crm.aiagent.domain.AiAgentMessage;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.response.*;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentConversationServiceTest {
    private AiAgentInternalMapper mapper;
    private AiAgentConversationService service;
    private AiAgentContext context;
    private List<AiAgentMessage> stored;
    @BeforeEach void setup() {
        mapper=mock(AiAgentInternalMapper.class);
        service=new AiAgentConversationService(mapper,mock(AiAgentLlmClient.class),new AiAgentConversationProperties(),new AiAgentIntentValidator());
        context=new AiAgentContext(); context.setUserId("user"); context.setOrganizationId("org");
        stored=new ArrayList<>();
        when(mapper.listRecentConversationMessages(eq("session"),eq("org"),eq("user"),anyInt())).thenAnswer(i -> stored.reversed());
    }
    private AiAgentMessage record(String id,String question,String sql,String error,String mode) {
        var response=new AiAgentChatResponse(); response.setQueryQuestion(question); response.setQueryError(error);
        if(sql!=null) response.setSqlQueries(List.of(sql)); response.setAnswer("answer");
        var outcome=service.complete(question,mode,context,response,null);
        var msg=new AiAgentMessage(); msg.setId(id); msg.setRole("assistant"); msg.setEvidenceJson(JSON.toJSONString(outcome.evidenceSnapshot())); return msg;
    }
    @Test void passesLastThreeQuestionsAndSqlWithoutRewritingFollowup() {
        for(int i=0;i<5;i++) stored.add(record("m"+i,"question-"+i,"SELECT "+i+" AS n",null,"QUERY"));
        service.prepareSqlHistory("session",context,null);
        assertThat(context.getSqlHistory()).hasSize(6);
        assertThat(context.getSqlHistory().getFirst().content()).isEqualTo("question-2");
        assertThat(context.getSqlHistory().getLast().content()).contains("SELECT 4 AS n","success");
    }
    @Test void regenerationHistoryStopsBeforeReferencedQuery() {
        stored.add(record("a","first","SELECT 1 AS n",null,"QUERY"));
        stored.add(record("b","second","SELECT 2 AS n","SQLState=42000","QUERY"));
        stored.add(record("c","later","SELECT 3 AS n",null,"QUERY"));
        service.prepareSqlHistory("session",context,"b");
        assertThat(context.getSqlHistory()).hasSize(2);
        assertThat(context.getSqlHistory().getLast().content()).contains("SELECT 1").doesNotContain("SELECT 2","SELECT 3");
    }
    @Test void failedSqlAndFeedbackAreIncludedForNextQuestion() {
        stored.add(record("a","failed question","SELECT bad AS n","unknown field","QUERY"));
        service.prepareSqlHistory("session",context,null);
        assertThat(context.getSqlHistory().getLast().content()).contains("unknown field","SELECT bad","false");
        assertThat(context.getQueryError()).isEqualTo("unknown field");
    }
    @Test void excludesAnalysisAttachmentAndOtherDatasourceRecords() {
        stored.add(record("a","old","SELECT 1 AS n",null,"ANALYSIS"));
        var other=record("b","different source","SELECT 2 AS n",null,"QUERY");
        context.setDatasourceId("other-source");
        service.prepareSqlHistory("session",context,null);
        stored.add(other); service.prepareSqlHistory("session",context,null);
        assertThat(context.getSqlHistory()).isEmpty();
        verify(mapper,atLeastOnce()).listRecentConversationMessages(eq("session"),eq("org"),eq("user"),anyInt());
    }
    @Test void sameDatasourceRevisionChangeInvalidatesHistory() {
        context.setDatasourceId("source"); context.setDatasourceRevision(1);
        stored.add(record("a","query","SELECT 1 AS n",null,"QUERY"));
        context.setDatasourceRevision(2); service.prepareSqlHistory("session",context,null);
        assertThat(context.getSqlHistory()).isEmpty();
    }
    @Test void queryRunnerReceivesOriginalFollowupOnceWithoutPlannerOrSupplementaryQuery() {
        var llm=mock(AiAgentLlmClient.class);
        service=new AiAgentConversationService(mapper,llm,new AiAgentConversationProperties(),new AiAgentIntentValidator());
        var evidence=new AiAgentQueryEvidence(); evidence.setResponse(new AiAgentChatResponse());
        var questions=new ArrayList<String>();
        service.respond("session","只看DAISY呢",context,q -> { questions.add(q); return evidence; });
        assertThat(questions).containsExactly("只看DAISY呢"); verifyNoInteractions(llm);
    }
    @Test void missingReferenceDoesNotUseLaterHistory() {
        stored.add(record("a","later","SELECT 1 AS n",null,"QUERY"));
        service.prepareSqlHistory("session",context,"not-in-window");
        assertThat(context.getSqlHistory()).isEmpty();
    }
}
