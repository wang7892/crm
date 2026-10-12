package cn.cordys.crm.aiagent.service;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.config.AiAgentSemanticRagProperties;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.AiAgentTimeWindow;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryFilter;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryMetric;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryResult;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiAgentPurchaseQueryRegressionTest {
    private static final String CUSTOMER = "摩洛哥VANOS(FTH)";
    private final AiAgentSemanticSchemaService schema = new AiAgentSemanticSchemaService();
    private final AiAgentQueryContractService contract = new AiAgentQueryContractService(schema);
    private final AiAgentQueryPlanner planner = new AiAgentQueryPlanner(schema);
    private final AiAgentSqlBuilder sql = new AiAgentSqlBuilder(new AiAgentQueryPermissionService(null));

    @ParameterizedTest
    @ValueSource(strings = {"release_date", "下单日期", "order_time"})
    void exposesContractBusinessDateWithoutRequiringTimeline(String field) {
        var date = schema.findEntity("contract_info").orElseThrow().resolveField(field).orElseThrow();
        assertThat(date.expression()).isEqualTo("ci.release_date");
        assertThat(date.valueKind()).isEqualTo(AiAgentSemanticSchemaService.ValueKind.SQL_TIMESTAMP);
    }


    @Test
    void preservesContractPlanWhenProductRuleIsEnforced() {
        var parsed = purchase(CUSTOMER + "最近3个月采购的品种及数量");
        var context = context();
        var match = new AiAgentSemanticRuleMatch();
        match.setCanonicalTerm("品种");
        var target = new AiAgentSemanticRuleMatch.Target();
        target.setEntity("contract_info");
        target.setField("product_name");
        match.setTarget(target);
        context.setSemanticRuleMatches(List.of(match));
        var properties = new AiAgentSemanticRagProperties();
        properties.setEnabled(true);

        contract.prepare(parsed, null, context);

        assertThat(new AiAgentSemanticPlanGuard(properties).validate(parsed, context).allowed()).isTrue();
        assertThat(parsed.getQueryPlan().getGroupBy()).containsExactly("product_name", "unit");
    }

    @Test
    void preservesOriginalPersonAndCustomerDuringPurchaseRepair() {
        var original = purchase("张海芳负责的" + CUSTOMER + "最近3个月采购的品种及数量");
        original.setSpecialistName("张海芳");
        var repaired = purchase(original.getRawQuestion());
        repaired.setCustomerName(null);

        contract.prepare(repaired, original, context());

        assertThat(repaired.getQueryPlan().getFilters()).anySatisfy(f -> {
            assertThat(f.getField()).isEqualTo("manager");
            assertThat(f.getOperator()).isEqualTo("eq");
            assertThat(f.getValue()).isEqualTo("张海芳");
        }).anySatisfy(f -> {
            assertThat(f.getField()).isEqualTo("customer");
            assertThat(f.getValue()).isEqualTo(CUSTOMER);
        });
        repaired.getQueryPlan().setFilters(List.of(filter("manager", "eq", "赵琳")));
        assertThatThrownBy(() -> contract.prepare(repaired, original, context())).hasMessageContaining("冲突");
    }

    @Test
    void keepsExplicitCreationDateQueriesAvailable() {
        var parsed = purchase(CUSTOMER + "最近3个月创建的合同按产品汇总数量");
        contract.prepare(parsed, null, context());
        var built = sql.build(planner.plan(parsed.getQueryPlan()), context());
        assertThat(built.sql()).contains("ci.create_time >=", "ci.create_time <").doesNotContain("ci.release_date");
    }

    @Test
    void doesNotTreatNearThreeMonthsAsAllTime() {
        var parsed = purchase(CUSTOMER + "近3个月采购的品种及数量");
        var context = context();
        context.setTimeWindow(new AiAgentTimeWindow(0, Long.MAX_VALUE, "全部"));
        assertThatThrownBy(() -> contract.prepare(parsed, null, context)).hasMessageContaining("有效时间范围");
    }

    private ParsedAiAgentQuestion purchase(String question) {
        var parsed = new ParsedAiAgentQuestion();
        parsed.setRawQuestion(question);
        parsed.setIntent("CRM_DATABASE_QUERY");
        parsed.setCustomerName(CUSTOMER);
        var plan = new AiAgentQueryPlan();
        plan.setEntity("contract_info");
        plan.setQueryType("AGGREGATE");
        plan.setGroupBy(List.of("product_name"));
        var metric = new AiAgentQueryMetric();
        metric.setField("total_quantity");
        metric.setFunction("sum");
        metric.setAlias("purchase_quantity");
        plan.setMetrics(List.of(metric));
        parsed.setQueryPlan(plan);
        return parsed;
    }

    private AiAgentChatService service(AiAgentDatabaseQueryService database, LlmAiAgentQuestionParser parser) {
        var service = new AiAgentChatService();
        var properties = new AiAgentSemanticRagProperties();
        properties.setEnabled(false);
        ReflectionTestUtils.setField(service, "queryContractService", contract);
        ReflectionTestUtils.setField(service, "semanticPlanGuard", new AiAgentSemanticPlanGuard(properties));
        ReflectionTestUtils.setField(service, "aiAgentDatabaseQueryService", database);
        ReflectionTestUtils.setField(service, "llmAiAgentQuestionParser", parser);
        return service;
    }

    private AiAgentContext context() {
        var context = new AiAgentContext();
        context.setOrganizationId("org-1");
        context.setUserId("user-1");
        var all = new DeptDataPermissionDTO();
        all.setAll(true);
        context.setContractDataPermission(all);
        context.setOrderDataPermission(all);
        context.setDataPermission(all);
        AiAgentTimeWindow window = ReflectionTestUtils.invokeMethod(
                new AiAgentChatService(), "resolveTimeWindow", "最近3个月", null);
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertThat(window.startTime()).isEqualTo(today.minusMonths(3).atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli());
        context.setTimeWindow(window);
        return context;
    }

    private AiAgentQueryFilter filter(String field, String operator, Object value) {
        var filter = new AiAgentQueryFilter();
        filter.setField(field);
        filter.setOperator(operator);
        filter.setValue(value);
        return filter;
    }
}
