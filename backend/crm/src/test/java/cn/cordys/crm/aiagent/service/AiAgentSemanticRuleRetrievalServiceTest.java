package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentSemanticRagProperties;
import cn.cordys.crm.aiagent.domain.AiKnowledgeChunk;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRule;
import cn.cordys.crm.aiagent.mapper.AiAgentKnowledgeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentSemanticRuleRetrievalServiceTest {

    private static final String ORG_ID = "org-1";

    @Test
    void shouldMatchApprovedPublishedRuleAndRevalidateIt() {
        AiAgentKnowledgeMapper mapper = mock(AiAgentKnowledgeMapper.class);
        AiKnowledgeChunk chunk = chunk("chunk-1", rule("RULE_VARIETY", "品种",
                "contract_info", "product_name", "EXTERNAL_CONTRACT", "APPROVED"));
        when(mapper.listPublishedSemanticRuleChunks(ORG_ID)).thenReturn(List.of(chunk));
        when(mapper.revalidatePublishedSemanticRuleChunks(eq(ORG_ID), anyList())).thenReturn(List.of(chunk));

        AiAgentSemanticRuleRetrievalService.RetrievalResult result = service(mapper)
                .retrieve("每个品种的总数量是多少？", ORG_ID);

        assertThat(result.conflict()).isFalse();
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.getCanonicalTerm()).isEqualTo("品种");
            assertThat(match.getTarget().getEntity()).isEqualTo("contract_info");
            assertThat(match.getTarget().getField()).isEqualTo("product_name");
        });
        verify(mapper).revalidatePublishedSemanticRuleChunks(eq(ORG_ID), anyList());
    }

    @Test
    void shouldIgnoreRulesThatAreNotApproved() {
        AiAgentKnowledgeMapper mapper = mock(AiAgentKnowledgeMapper.class);
        AiKnowledgeChunk chunk = chunk("chunk-pending", rule("RULE_PENDING", "品种",
                "contract_info", "product_name", "EXTERNAL_CONTRACT", "PENDING"));
        when(mapper.listPublishedSemanticRuleChunks(ORG_ID)).thenReturn(List.of(chunk));

        AiAgentSemanticRuleRetrievalService.RetrievalResult result = service(mapper)
                .retrieve("品种有哪些？", ORG_ID);

        assertThat(result.matches()).isEmpty();
        assertThat(result.fallbackReason()).isEqualTo("NO_EXACT_MATCH");
        verify(mapper, never()).revalidatePublishedSemanticRuleChunks(eq(ORG_ID), anyList());
    }

    @Test
    void shouldPreferLongestOverlappingTerm() {
        AiAgentKnowledgeMapper mapper = mock(AiAgentKnowledgeMapper.class);
        AiKnowledgeChunk shortRule = chunk("chunk-short", rule("RULE_SHORT", "品种",
                "contract_info", "product_name", "EXTERNAL_CONTRACT", "APPROVED"));
        AiKnowledgeChunk longRule = chunk("chunk-long", rule("RULE_LONG", "产品品种",
                "contract_info", "product_name", "EXTERNAL_CONTRACT", "APPROVED"));
        when(mapper.listPublishedSemanticRuleChunks(ORG_ID)).thenReturn(List.of(shortRule, longRule));
        when(mapper.revalidatePublishedSemanticRuleChunks(eq(ORG_ID), anyList()))
                .thenReturn(List.of(shortRule, longRule));

        AiAgentSemanticRuleRetrievalService.RetrievalResult result = service(mapper)
                .retrieve("每个产品品种的总数量是多少？", ORG_ID);

        assertThat(result.matches()).singleElement()
                .extracting(match -> match.getCanonicalTerm())
                .isEqualTo("产品品种");
    }

    @Test
    void shouldFailClosedWhenSameTermHasConflictingTargets() {
        AiAgentKnowledgeMapper mapper = mock(AiAgentKnowledgeMapper.class);
        AiKnowledgeChunk externalRule = chunk("chunk-external", rule("RULE_EXTERNAL", "品种",
                "contract_info", "product_name", "EXTERNAL_CONTRACT", "APPROVED"));
        AiKnowledgeChunk orderRule = chunk("chunk-order", rule("RULE_ORDER", "品种",
                "sales_order", "material_name", "CRM", "APPROVED"));
        when(mapper.listPublishedSemanticRuleChunks(ORG_ID)).thenReturn(List.of(externalRule, orderRule));
        when(mapper.revalidatePublishedSemanticRuleChunks(eq(ORG_ID), anyList()))
                .thenReturn(List.of(externalRule, orderRule));

        AiAgentSemanticRuleRetrievalService.RetrievalResult result = service(mapper)
                .retrieve("品种有哪些？", ORG_ID);

        assertThat(result.conflict()).isTrue();
        assertThat(result.fallbackReason()).contains("冲突");
    }

    private AiAgentSemanticRuleRetrievalService service(AiAgentKnowledgeMapper mapper) {
        AiAgentSemanticRagProperties properties = new AiAgentSemanticRagProperties();
        properties.setEnabled(true);
        properties.setMaxRules(3);
        return new AiAgentSemanticRuleRetrievalService(mapper, new AiAgentSemanticSchemaService(), properties);
    }

    private AiKnowledgeChunk chunk(String id, AiAgentSemanticRule rule) {
        AiKnowledgeChunk chunk = new AiKnowledgeChunk();
        chunk.setId(id);
        chunk.setOrganizationId(ORG_ID);
        chunk.setDocumentId("document-" + id);
        chunk.setContent(JSON.toJSONString(rule));
        chunk.setEnabled(1);
        return chunk;
    }

    private AiAgentSemanticRule rule(String ruleId, String term, String entity, String field,
                                     String dataSource, String reviewStatus) {
        AiAgentSemanticRule rule = new AiAgentSemanticRule();
        rule.setSchemaVersion("1.0");
        rule.setRuleId(ruleId);
        rule.setVersion(1);
        rule.setType("TERM_MAPPING");
        rule.setCanonicalTerm(term);
        rule.setScope("CRM_DATABASE_QUERY");
        rule.setPriority(100);
        AiAgentSemanticRule.Mapping mapping = new AiAgentSemanticRule.Mapping();
        mapping.setEntity(entity);
        mapping.setField(field);
        mapping.setDataSource(dataSource);
        rule.setMapping(mapping);
        AiAgentSemanticRule.Review review = new AiAgentSemanticRule.Review();
        review.setStatus(reviewStatus);
        rule.setReview(review);
        return rule;
    }
}
