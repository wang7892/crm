package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.config.AiAgentSemanticRagProperties;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeSearchMatchResponse;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeSearchTestResponse;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmAiAgentQuestionParserSemanticContextTest {

    @Test
    void shouldInjectRetrievedDocumentKnowledgeIntoSystemPrompt() {
        AiAgentLlmClient llmClient = mock(AiAgentLlmClient.class);
        AiAgentSemanticRagProperties semanticProperties = new AiAgentSemanticRagProperties();
        LlmAiAgentQuestionParser parser = parser(llmClient, semanticProperties);
        String question = "每个品种的总数量是多少？";
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyString(), eq(question), eq("provider")))
                .thenReturn(validPlanJson());

        AiKnowledgeSearchMatchResponse match = new AiKnowledgeSearchMatchResponse();
        match.setDocumentName("业务口径.md");
        match.setSectionPath("字段定义");
        match.setContent("品种是外部合同表中的品名字段，不是订单中的原料名称。");
        AiKnowledgeSearchTestResponse search = new AiKnowledgeSearchTestResponse();
        search.setMatches(List.of(match));
        AiAgentContext context = new AiAgentContext();
        context.setKnowledgeSearch(search);

        parser.parse(question, "provider", context);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(promptCaptor.capture(), eq(question), eq("provider"));
        assertThat(promptCaptor.getValue()).contains(
                "retrievedKnowledge",
                "业务口径.md",
                "品种是外部合同表中的品名字段，不是订单中的原料名称",
                "当公司知识与模型的一般常识或默认推断冲突时，以公司知识为准");
    }

    @Test
    void shouldInjectOnlyControlledSemanticRuleJson() {
        AiAgentLlmClient llmClient = mock(AiAgentLlmClient.class);
        AiAgentSemanticRagProperties semanticProperties = new AiAgentSemanticRagProperties();
        semanticProperties.setEnabled(true);
        LlmAiAgentQuestionParser parser = parser(llmClient, semanticProperties);
        String question = "每个品种的总数量是多少？";
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyString(), eq(question), eq("provider")))
                .thenReturn(validPlanJson());

        parser.parse(question, "provider", context());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(promptCaptor.capture(), eq(question), eq("provider"));
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains(
                "semanticRules",
                "TERM_CONTRACT_PRODUCT_VARIETY",
                "contract_info",
                "product_name",
                "品种是外部合同表的一个字段，不要理解为订单中的字段");
        assertThat(prompt).doesNotContain(
                "document-secret",
                "chunk-secret",
                "来源原文绝不能进入提示词");
    }

    @Test
    void shouldNotInjectSemanticRulesWhenFeatureIsDisabled() {
        AiAgentLlmClient llmClient = mock(AiAgentLlmClient.class);
        AiAgentSemanticRagProperties semanticProperties = new AiAgentSemanticRagProperties();
        semanticProperties.setEnabled(false);
        LlmAiAgentQuestionParser parser = parser(llmClient, semanticProperties);
        String question = "每个品种的总数量是多少？";
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyString(), eq(question), eq("provider")))
                .thenReturn(validPlanJson());

        parser.parse(question, "provider", context());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(promptCaptor.capture(), eq(question), eq("provider"));
        assertThat(promptCaptor.getValue()).doesNotContain(
                "TERM_CONTRACT_PRODUCT_VARIETY",
                "semanticRules:",
                "品种是外部合同表的一个字段");
    }

    @Test
    void shouldFeedPlanValidationFailureBackForOneRepairRequest() {
        AiAgentLlmClient llmClient = mock(AiAgentLlmClient.class);
        AiAgentSemanticRagProperties semanticProperties = new AiAgentSemanticRagProperties();
        semanticProperties.setEnabled(true);
        LlmAiAgentQuestionParser parser = parser(llmClient, semanticProperties);
        String question = "公司客户有哪些？";
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyString(), eq(question), eq("provider")))
                .thenReturn(validPlanJson());

        parser.repair(question, "provider", context(), "缺少 customer_source eq 公司客户");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(promptCaptor.capture(), eq(question), eq("provider"));
        assertThat(promptCaptor.getValue()).contains(
                "上一次为同一问题生成的查询计划未通过", "缺少 customer_source eq 公司客户");
    }

    private LlmAiAgentQuestionParser parser(AiAgentLlmClient llmClient,
                                             AiAgentSemanticRagProperties semanticProperties) {
        AiAgentLlmProperties llmProperties = new AiAgentLlmProperties();
        llmProperties.setEnabled(true);
        llmProperties.setMaxInputLength(1000);
        AiAgentIntentValidator intentValidator = new AiAgentIntentValidator();
        AiAgentSemanticContextBuilder contextBuilder = new AiAgentSemanticContextBuilder(semanticProperties);
        return new LlmAiAgentQuestionParser(
                llmProperties,
                llmClient,
                intentValidator,
                new AiAgentSemanticSchemaService(),
                new AiAgentParsedQuestionNormalizer(intentValidator),
                contextBuilder,
                semanticProperties
        );
    }

    private AiAgentContext context() {
        AiAgentSemanticRuleMatch match = new AiAgentSemanticRuleMatch();
        match.setRuleId("TERM_CONTRACT_PRODUCT_VARIETY");
        match.setVersion(1);
        match.setCanonicalTerm("品种");
        match.setAliases(List.of("产品品种"));
        match.setInstruction("品种是外部合同表的一个字段，不要理解为订单中的字段。");
        match.setDocumentId("document-secret");
        match.setChunkId("chunk-secret");
        match.setSectionPath("来源原文绝不能进入提示词");
        AiAgentSemanticRuleMatch.Target target = new AiAgentSemanticRuleMatch.Target();
        target.setEntity("contract_info");
        target.setField("product_name");
        match.setTarget(target);
        AiAgentSemanticRuleMatch.Target forbidden = new AiAgentSemanticRuleMatch.Target();
        forbidden.setEntity("sales_order");
        match.setForbiddenTargets(List.of(forbidden));
        AiAgentContext context = new AiAgentContext();
        context.setSemanticRuleMatches(List.of(match));
        return context;
    }

    private String validPlanJson() {
        return """
                {
                  "intent": "CRM_DATABASE_QUERY",
                  "confidence": 0.99,
                  "queryPlan": {
                    "intent": "CRM_DATABASE_QUERY",
                    "queryType": "AGGREGATE",
                    "entity": "contract_info",
                    "selectFields": [],
                    "filters": [],
                    "metrics": [{"function":"sum","field":"total_quantity","alias":"total_quantity"}],
                    "groupBy": ["product_name"],
                    "orderBy": [],
                    "limit": 100,
                    "needClarification": false
                  }
                }
                """;
    }
}
