package cn.cordys.crm.aiagent.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAgentSemanticRuleExtractionServiceTest {

    @Test
    void shouldCreateOneCompleteAutomaticallyApprovedRuleFromModelCandidate() {
        AiAgentLlmClient llmClient = mock(AiAgentLlmClient.class);
        when(llmClient.chat(anyString(), anyString())).thenReturn("""
                {"rules":[{
                  "canonicalTerm":"品种",
                  "aliases":["产品品种"],
                  "definition":"品种表示外部合同明细中的产品名称。",
                  "suggestedMapping":{"entity":"contract_info","field":"product_name"},
                  "forbiddenMappings":[{"entity":"sales_order","field":null}],
                  "examples":[],
                  "sourceQuote":"品种是外部合同表的一个字段，不要理解为订单中的字段。",
                  "confidence":0.98,
                  "review":{"status":"APPROVED"},
                  "version":99
                }]}
                """);
        AiAgentLlmProperties properties = new AiAgentLlmProperties();
        properties.setModel("extract-model");
        AiAgentSemanticRuleValidationService validation =
                new AiAgentSemanticRuleValidationService(new AiAgentSemanticSchemaService());
        AiAgentSemanticRuleExtractionService service =
                new AiAgentSemanticRuleExtractionService(llmClient, properties, validation);
        AiKnowledgeDocument document = new AiKnowledgeDocument();
        document.setId("doc-1");
        String quote = "品种是外部合同表的一个字段，不要理解为订单中的字段。";

        List<AiAgentSemanticRule> rules = service.extract(
                document,
                "org-1",
                "# 外部合同口径\n\n" + quote,
                List.of(new AiAgentSemanticRuleExtractionService.SourceFragment(1, "品种", quote))
        );

        assertThat(rules).hasSize(1);
        AiAgentSemanticRule rule = rules.get(0);
        assertThat(rule.getReview().getStatus()).isEqualTo("APPROVED");
        assertThat(rule.getInstruction()).contains(quote);
        assertThat(rule.getVersion()).isZero();
        assertThat(rule.getSource().getDocumentId()).isEqualTo("doc-1");
        assertThat(rule.getSource().getPageNo()).isEqualTo(1);
        assertThat(rule.getMapping().getEntity()).isEqualTo("contract_info");
        assertThat(rule.getMapping().getField()).isEqualTo("product_name");
        assertThat(validation.serialize(rule)).contains("\"canonicalTerm\":\"品种\"");
    }

    @Test
    void shouldFailExplicitlyWhenExtractionModelIsUnavailable() {
        AiAgentLlmClient llmClient = mock(AiAgentLlmClient.class);
        when(llmClient.chat(anyString(), anyString())).thenReturn(null);
        AiAgentSemanticRuleValidationService validation =
                new AiAgentSemanticRuleValidationService(new AiAgentSemanticSchemaService());
        AiAgentSemanticRuleExtractionService service =
                new AiAgentSemanticRuleExtractionService(llmClient, new AiAgentLlmProperties(), validation);
        AiKnowledgeDocument document = new AiKnowledgeDocument();
        document.setId("doc-1");

        assertThatThrownBy(() -> service.extract(
                document,
                "org-1",
                "# 品种",
                List.of(new AiAgentSemanticRuleExtractionService.SourceFragment(null, "品种", "品种"))))
                .isInstanceOf(GenericException.class)
                .hasMessageContaining("模型不可用");
    }
}
