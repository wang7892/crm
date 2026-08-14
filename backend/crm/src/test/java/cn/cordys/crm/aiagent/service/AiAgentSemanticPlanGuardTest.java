package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentSemanticRagProperties;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryFilter;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryMetric;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryOrder;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentSemanticPlanGuardTest {

    private final AiAgentSemanticPlanGuard guard = new AiAgentSemanticPlanGuard(enabledProperties());

    @Test
    void shouldAcceptExpectedAggregatePlan() {
        ParsedAiAgentQuestion parsed = parsed("contract_info", List.of("product_name"));
        parsed.getQueryPlan().setGroupBy(List.of("product_name"));
        AiAgentQueryMetric metric = new AiAgentQueryMetric();
        metric.setFunction("sum");
        metric.setField("total_quantity");
        parsed.getQueryPlan().setMetrics(List.of(metric));

        AiAgentSemanticPlanGuard.GuardResult result = guard.validate(parsed, context());

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldRejectForbiddenEntityEvenWhenLlmReturnsDatabaseIntent() {
        ParsedAiAgentQuestion parsed = parsed("sales_order", List.of("material_name"));

        AiAgentSemanticPlanGuard.GuardResult result = guard.validate(parsed, context());

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("查询实体");
    }

    @Test
    void shouldRequireTargetFieldAndGroupingForEveryTermQuestion() {
        ParsedAiAgentQuestion missingField = parsed("contract_info", List.of("customer"));
        AiAgentSemanticPlanGuard.GuardResult missingFieldResult = guard.validate(missingField, context());

        ParsedAiAgentQuestion missingGroup = parsed("contract_info", List.of("product_name"));
        AiAgentQueryFilter productFilter = new AiAgentQueryFilter();
        productFilter.setField("product_name");
        productFilter.setOperator("eq");
        productFilter.setValue("ABC");
        missingGroup.getQueryPlan().setFilters(List.of(productFilter));
        AiAgentSemanticPlanGuard.GuardResult missingGroupResult = guard.validate(missingGroup, context());

        assertThat(missingFieldResult.allowed()).isFalse();
        assertThat(missingFieldResult.reason()).contains("目标字段");
        assertThat(missingGroupResult.allowed()).isFalse();
        assertThat(missingGroupResult.reason()).contains("分组");
    }

    @Test
    void shouldRejectListPlanWhenTargetFieldOnlyControlsOrdering() {
        ParsedAiAgentQuestion parsed = parsed("contract_info", List.of("customer"));
        parsed.setRawQuestion("有哪些品种？");
        parsed.getQueryPlan().setQueryType("LIST");
        AiAgentQueryOrder order = new AiAgentQueryOrder();
        order.setField("product_name");
        order.setDirection("asc");
        parsed.getQueryPlan().setOrderBy(List.of(order));

        AiAgentSemanticPlanGuard.GuardResult result = guard.validate(parsed, context());

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("目标字段");
    }

    @Test
    void shouldRejectAggregatePlanWhenTargetFieldOnlyAppearsInSelectFields() {
        ParsedAiAgentQuestion parsed = parsed("contract_info", List.of("product_name"));
        parsed.setRawQuestion("统计品种数据");

        AiAgentSemanticPlanGuard.GuardResult result = guard.validate(parsed, context());

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("目标字段");
    }

    @Test
    void shouldRejectAggregatePlanWhenTargetFieldOnlyAppearsInCountMetric() {
        ParsedAiAgentQuestion parsed = parsed("contract_info", List.of());
        parsed.setRawQuestion("统计品种记录数量");
        AiAgentQueryMetric metric = new AiAgentQueryMetric();
        metric.setFunction("count");
        metric.setField("product_name");
        parsed.getQueryPlan().setMetrics(List.of(metric));

        AiAgentSemanticPlanGuard.GuardResult result = guard.validate(parsed, context());

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("目标字段");
    }

    @Test
    void shouldAcceptListPlanFilteringByTargetField() {
        ParsedAiAgentQuestion parsed = parsed("contract_info", List.of("customer"));
        parsed.setRawQuestion("品种为 ABC 的记录有哪些？");
        parsed.getQueryPlan().setQueryType("LIST");
        AiAgentQueryFilter filter = new AiAgentQueryFilter();
        filter.setField("product_name");
        filter.setOperator("eq");
        filter.setValue("ABC");
        parsed.getQueryPlan().setFilters(List.of(filter));

        AiAgentSemanticPlanGuard.GuardResult result = guard.validate(parsed, context());

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldRequireExactBusinessValueFilter() {
        ParsedAiAgentQuestion parsed = parsed("customer", List.of("customer_source"));
        parsed.setRawQuestion("公司客户有哪些？");
        parsed.getQueryPlan().setQueryType("LIST");
        AiAgentQueryFilter wrongFilter = new AiAgentQueryFilter();
        wrongFilter.setField("customer_source");
        wrongFilter.setOperator("eq");
        wrongFilter.setValue("展会客户");
        parsed.getQueryPlan().setFilters(List.of(wrongFilter));

        AiAgentSemanticPlanGuard.GuardResult result = guard.validate(parsed, companyCustomerContext());

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("customer_source", "公司客户");
    }

    @Test
    void shouldAcceptExactBusinessValueFilter() {
        ParsedAiAgentQuestion parsed = parsed("customer", List.of("customer_source"));
        parsed.setRawQuestion("公司客户有哪些？");
        parsed.getQueryPlan().setQueryType("LIST");
        AiAgentQueryFilter filter = new AiAgentQueryFilter();
        filter.setField("customer_source");
        filter.setOperator("eq");
        filter.setValue("公司客户");
        parsed.getQueryPlan().setFilters(List.of(filter));

        AiAgentSemanticPlanGuard.GuardResult result = guard.validate(parsed, companyCustomerContext());

        assertThat(result.allowed()).isTrue();
    }

    private ParsedAiAgentQuestion parsed(String entity, List<String> selectFields) {
        AiAgentQueryPlan plan = new AiAgentQueryPlan();
        plan.setEntity(entity);
        plan.setQueryType("AGGREGATE");
        plan.setSelectFields(selectFields);
        ParsedAiAgentQuestion parsed = new ParsedAiAgentQuestion();
        parsed.setIntent("CRM_DATABASE_QUERY");
        parsed.setRawQuestion("每个品种的总数量是多少？");
        parsed.setQueryPlan(plan);
        return parsed;
    }

    private AiAgentContext context() {
        AiAgentSemanticRuleMatch match = new AiAgentSemanticRuleMatch();
        match.setRuleId("TERM_CONTRACT_PRODUCT_VARIETY");
        match.setVersion(1);
        match.setCanonicalTerm("品种");
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

    private AiAgentContext companyCustomerContext() {
        AiAgentSemanticRuleMatch match = new AiAgentSemanticRuleMatch();
        match.setRuleId("TERM_COMPANY_CUSTOMER");
        match.setVersion(1);
        match.setRuleType("FILTER_VALUE");
        match.setCanonicalTerm("公司客户");
        AiAgentSemanticRuleMatch.Target target = new AiAgentSemanticRuleMatch.Target();
        target.setEntity("customer");
        target.setField("customer_source");
        match.setTarget(target);
        AiAgentSemanticRuleMatch.FilterConstraint required = new AiAgentSemanticRuleMatch.FilterConstraint();
        required.setEntity("customer");
        required.setField("customer_source");
        required.setOperator("eq");
        required.setValue("公司客户");
        match.setRequiredFilters(List.of(required));
        AiAgentContext context = new AiAgentContext();
        context.setSemanticRuleMatches(List.of(match));
        return context;
    }

    private static AiAgentSemanticRagProperties enabledProperties() {
        AiAgentSemanticRagProperties properties = new AiAgentSemanticRagProperties();
        properties.setEnabled(true);
        return properties;
    }
}
