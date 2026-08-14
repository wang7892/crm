package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeSearchTestResponse;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentChatServiceRagFallbackTest {

    @Test
    void shouldUseOriginalFallbackWhenRagEnhancedParsingCannotProduceAPlan() {
        AiAgentSemanticRuleRetrievalService retrievalService = mock(AiAgentSemanticRuleRetrievalService.class);
        AiAgentKnowledgeService knowledgeService = mock(AiAgentKnowledgeService.class);
        LlmAiAgentQuestionParser parser = mock(LlmAiAgentQuestionParser.class);
        AiAgentSemanticPlanGuard planGuard = mock(AiAgentSemanticPlanGuard.class);
        AiAgentMessageBodyAccessService messageBodyAccessService = mock(AiAgentMessageBodyAccessService.class);
        AiAgentChatService service = new AiAgentChatService();
        ReflectionTestUtils.setField(service, "semanticRuleRetrievalService", retrievalService);
        ReflectionTestUtils.setField(service, "aiAgentKnowledgeService", knowledgeService);
        ReflectionTestUtils.setField(service, "llmAiAgentQuestionParser", parser);
        ReflectionTestUtils.setField(service, "semanticPlanGuard", planGuard);
        ReflectionTestUtils.setField(service, "aiAgentMessageBodyAccessService", messageBodyAccessService);

        String question = "一个原有流程无法精确分类的问题";
        AiAgentContext context = new AiAgentContext();
        context.setOrganizationId("org-1");
        AiAgentSemanticRuleMatch match = new AiAgentSemanticRuleMatch();
        match.setRuleId("TERM_TEST");
        match.setVersion(1);
        when(retrievalService.retrieve(question, "org-1"))
                .thenReturn(new AiAgentSemanticRuleRetrievalService.RetrievalResult(
                        List.of(match), false, null));
        when(retrievalService.isEnabled()).thenReturn(true);
        AiKnowledgeSearchTestResponse knowledgeSearch = new AiKnowledgeSearchTestResponse();
        knowledgeSearch.setMatches(List.of());
        when(knowledgeService.searchTest(question, 5, "org-1")).thenReturn(knowledgeSearch);
        when(planGuard.isEnforced(any(AiAgentContext.class))).thenReturn(true);
        when(parser.parse(eq(question), any(), eq(context))).thenReturn(null);
        when(parser.repair(eq(question), any(), eq(context), any())).thenReturn(null);

        AiAgentChatResponse response = ReflectionTestUtils.invokeMethod(service, "route", question, context);

        assertThat(response).isNotNull();
        assertThat(response.getIntent()).isEqualTo("HELP");
        assertThat(response.getIntent()).isNotEqualTo("QUESTION_CLARIFICATION_REQUIRED");
        verify(parser).repair(eq(question), any(), eq(context), any());
    }

    @Test
    void shouldContinueOriginalFlowWhenDocumentKnowledgeRetrievalFails() {
        AiAgentSemanticRuleRetrievalService retrievalService = mock(AiAgentSemanticRuleRetrievalService.class);
        AiAgentKnowledgeService knowledgeService = mock(AiAgentKnowledgeService.class);
        LlmAiAgentQuestionParser parser = mock(LlmAiAgentQuestionParser.class);
        AiAgentSemanticPlanGuard planGuard = mock(AiAgentSemanticPlanGuard.class);
        AiAgentMessageBodyAccessService messageBodyAccessService = mock(AiAgentMessageBodyAccessService.class);
        AiAgentChatService service = new AiAgentChatService();
        ReflectionTestUtils.setField(service, "semanticRuleRetrievalService", retrievalService);
        ReflectionTestUtils.setField(service, "aiAgentKnowledgeService", knowledgeService);
        ReflectionTestUtils.setField(service, "llmAiAgentQuestionParser", parser);
        ReflectionTestUtils.setField(service, "semanticPlanGuard", planGuard);
        ReflectionTestUtils.setField(service, "aiAgentMessageBodyAccessService", messageBodyAccessService);

        String question = "一个原有流程无法精确分类的问题";
        AiAgentContext context = new AiAgentContext();
        context.setOrganizationId("org-1");
        when(retrievalService.retrieve(question, "org-1"))
                .thenReturn(new AiAgentSemanticRuleRetrievalService.RetrievalResult(List.of(), false, null));
        when(knowledgeService.searchTest(question, 5, "org-1"))
                .thenThrow(new IllegalStateException("knowledge database unavailable"));
        when(parser.parse(eq(question), any(), eq(context))).thenReturn(null);

        AiAgentChatResponse response = ReflectionTestUtils.invokeMethod(service, "route", question, context);

        assertThat(response).isNotNull();
        assertThat(response.getIntent()).isEqualTo("HELP");
        assertThat(context.getKnowledgeSearch()).isNotNull();
        assertThat(context.getKnowledgeSearch().getFallbackReason()).isEqualTo("DOCUMENT_RETRIEVAL_FAILED");
    }

    @Test
    void shouldUseOriginalExecutablePlanWhenKnowledgeRepairFails() {
        LlmAiAgentQuestionParser parser = mock(LlmAiAgentQuestionParser.class);
        AiAgentSemanticPlanGuard planGuard = mock(AiAgentSemanticPlanGuard.class);
        AiAgentDatabaseQueryService databaseQueryService = mock(AiAgentDatabaseQueryService.class);
        AiAgentChatService service = new AiAgentChatService();
        ReflectionTestUtils.setField(service, "llmAiAgentQuestionParser", parser);
        ReflectionTestUtils.setField(service, "semanticPlanGuard", planGuard);
        ReflectionTestUtils.setField(service, "aiAgentDatabaseQueryService", databaseQueryService);

        AiAgentContext context = new AiAgentContext();
        AiAgentQueryPlan queryPlan = new AiAgentQueryPlan();
        queryPlan.setIntent("CRM_DATABASE_QUERY");
        queryPlan.setEntity("contract_info");
        queryPlan.setQueryType("LIST");
        ParsedAiAgentQuestion parsedQuestion = new ParsedAiAgentQuestion();
        parsedQuestion.setRawQuestion("每个品种的总数量是多少");
        parsedQuestion.setIntent("CRM_DATABASE_QUERY");
        parsedQuestion.setQueryPlan(queryPlan);

        AiAgentChatResponse expected = new AiAgentChatResponse();
        expected.setIntent("CRM_DATABASE_QUERY");
        expected.setAnswer("原查询计划仍然正常执行");
        when(planGuard.isEnforced(context)).thenReturn(true);
        when(planGuard.validate(parsedQuestion, context))
                .thenReturn(new AiAgentSemanticPlanGuard.GuardResult(false, "知识映射校验未通过"));
        when(parser.repair(eq(parsedQuestion.getRawQuestion()), any(), eq(context), any())).thenReturn(null);
        when(databaseQueryService.answer(queryPlan, context)).thenReturn(expected);

        AiAgentChatResponse response = ReflectionTestUtils.invokeMethod(
                service, "routeParsedQuestion", parsedQuestion, context, true);

        assertThat(response).isSameAs(expected);
        verify(databaseQueryService).answer(queryPlan, context);
    }
}
