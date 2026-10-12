package cn.cordys.crm.aiagent.service;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.config.AiAgentSemanticRagProperties;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.AiAgentTimeWindow;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import cn.cordys.crm.aiagent.dto.query.*;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.test.util.ReflectionTestUtils;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import static org.assertj.core.api.Assertions.*;

class AiAgentQueryAccuracyTest {
    private final AiAgentSemanticSchemaService schema = new AiAgentSemanticSchemaService();
    private final AiAgentQueryContractService contract = new AiAgentQueryContractService(schema);
    private final AiAgentQueryPlanner planner = new AiAgentQueryPlanner(schema);
    private final AiAgentSqlBuilder sql = new AiAgentSqlBuilder(new AiAgentQueryPermissionService(null));

    @ParameterizedTest
    @CsvSource({
            "张海芳,order_timeline,manager,release_date",
            "赵琳,order_timeline,manager,release_date",
            "李四,sales_order,owner_name,order_time"
    })
    void preservesPersonMonthAndShipmentAcrossEntities(String person, String entity, String owner, String date) {
        var parsed = request("本月未发货的订单有哪些，只查询" + person + "未发货的订单", entity);
        parsed.setSpecialistName(person);
        var context = context();
        contract.prepare(parsed, null, context);
        contract.prepare(parsed, null, context);
        var built = sql.build(planner.plan(parsed.getQueryPlan()), context);

        assertThat(built.plan().getFilters()).extracting(AiAgentQueryFilter::getField)
                .containsExactly(owner, "warehouse_actual_ship_date", date);
        assertThat(built.params()).containsEntry("filter0", person);
        assertThat(built.sql()).contains("warehouse_actual_ship_date IS NULL", " >= :filter2Start", " < :filter2End");
        assertThat(built.plan().getSelectFields()).contains(owner, "warehouse_actual_ship_date", date);
    }

    @ParameterizedTest
    @CsvSource({
            "张海芳,赵琳", "赵琳,李四", "李四,张海芳"
    })
    void refusesAnotherPersonsOrders(String requested, String actual) {
        var original = request(requested + "的订单", "sales_order");
        original.setSpecialistName(requested);
        var repaired = request(requested + "的订单", "sales_order");
        repaired.setSpecialistName(actual);
        repaired.getQueryPlan().setFilters(List.of(filter("owner_name", "eq", actual)));
        assertThatThrownBy(() -> contract.prepare(repaired, original, context()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("冲突");
    }

    @Test
    void requiresShipmentFieldInsteadOfWorkflowStatus() {
        var parsed = request("张海芳本月未发货的订单", "contract_info");
        parsed.setSpecialistName("张海芳");
        assertThatThrownBy(() -> contract.prepare(parsed, null, context()))
                .hasMessageContaining("warehouse_actual_ship_date");
    }

    @ParameterizedTest
    @CsvSource({"未发货", "订单待处理", "生产已安排"})
    void rejectsInventedWorkflowStateForShipmentQuestion(String state) {
        var parsed = request("张海芳未发货的订单", "sales_order");
        parsed.getQueryPlan().setFilters(List.of(filter("status", "eq", state)));
        assertThatThrownBy(() -> contract.prepare(parsed, null, context()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("状态");
    }

    @Test
    void keepsMerchandiserSeparateFromManager() {
        var parsed = request("跟单员张海芳的未发货订单", "sales_order");
        parsed.setSpecialistName("张海芳");
        contract.prepare(parsed, null, context());
        assertThat(parsed.getQueryPlan().getFilters()).extracting(AiAgentQueryFilter::getField)
                .contains("merchandiser").doesNotContain("owner_name");
    }

    @ParameterizedTest
    @CsvSource({
            "本月应交但未发货的订单,delivery_date,is_null",
            "本月已发货的订单,warehouse_actual_ship_date,not_null",
            "本月下单的已发货订单,release_date,not_null",
            "本月未发货的订单,release_date,is_null"
    })
    void choosesBusinessDate(String question, String date, String state) {
        var parsed = request(question, "order_timeline");
        contract.prepare(parsed, null, context());
        assertThat(parsed.getQueryPlan().getFilters()).anySatisfy(f -> {
            assertThat(f.getField()).isEqualTo(date);
            assertThat(f.getOperator()).isEqualTo("between");
        }).anySatisfy(f -> {
            assertThat(f.getField()).isEqualTo("warehouse_actual_ship_date");
            assertThat(f.getOperator()).isEqualTo(state);
        });
    }

    @Test
    void rejectsConflictingShipmentPredicate() {
        var parsed = request("张海芳未发货的订单", "order_timeline");
        parsed.getQueryPlan().setFilters(List.of(filter("warehouse_actual_ship_date", "not_null", null)));
        assertThatThrownBy(() -> contract.prepare(parsed, null, context())).hasMessageContaining("冲突");
    }

    @Test
    void narrowsExactPersonWithoutDeletingOtherFilters() {
        var parsed = request("张海芳本月未发货的订单", "order_timeline");
        parsed.setSpecialistName("张海芳");
        parsed.getQueryPlan().setFilters(List.of(filter("联系专员", "like", "张海芳"), filter("customer", "like", "印尼")));
        contract.prepare(parsed, null, context());
        assertThat(parsed.getQueryPlan().getFilters()).anySatisfy(f -> {
            assertThat(f.getField()).isEqualTo("manager");
            assertThat(f.getOperator()).isEqualTo("eq");
        }).anySatisfy(f -> {
            assertThat(f.getField()).isEqualTo("customer");
            assertThat(f.getValue()).isEqualTo("印尼");
        });
    }

    @Test
    void countsOrdersIndependentlyOfDetailRows() {
        var parsed = request("每个理单员本月有多少订单", "order_timeline");
        parsed.getQueryPlan().setQueryType("COUNT");
        parsed.getQueryPlan().setGroupBy(List.of("manager"));
        contract.prepare(parsed, null, context());
        var built = sql.build(planner.plan(parsed.getQueryPlan()), context());
        assertThat(built.sql()).contains("COUNT(DISTINCT ot.order_no)", "GROUP BY TRIM(ci.manager)");
    }

    @Test
    void groupsMoneyByCurrency() {
        var plan = request("订单金额合计", "sales_order").getQueryPlan();
        plan.setQueryType("AGGREGATE");
        var metric = new AiAgentQueryMetric();
        metric.setFunction("sum");
        metric.setField("amount");
        plan.setMetrics(List.of(metric));
        assertThat(planner.plan(plan).queryPlan().getGroupBy()).containsExactly("currency");
        metric.setField("status");
        assertThatThrownBy(() -> planner.plan(plan)).hasMessageContaining("数值字段");
    }

    @Test
    void rejectsEmptyPredicatesInsteadOfDroppingThem() {
        var plan = request("指定客户", "customer").getQueryPlan();
        plan.setFilters(List.of(filter("name", "eq", null)));
        assertThatThrownBy(() -> planner.plan(plan)).hasMessageContaining("有效值");
        plan.setFilters(List.of(filter(null, "eq", "客户")));
        assertThatThrownBy(() -> planner.plan(plan)).hasMessageContaining("缺少字段");
    }

    @Test
    void auditedManagerRuleWorksThroughConfiguredTimelineJoin() {
        var properties = new AiAgentSemanticRagProperties();
        properties.setEnabled(true);
        var guard = new AiAgentSemanticPlanGuard(properties);
        var parsed = request("张海芳未发货的订单", "order_timeline");
        parsed.setSpecialistName("张海芳");
        contract.prepare(parsed, null, context());
        var match = new AiAgentSemanticRuleMatch();
        match.setCanonicalTerm("理单员");
        var target = new AiAgentSemanticRuleMatch.Target();
        target.setEntity("contract_info");
        target.setField("manager");
        match.setTarget(target);
        var context = context();
        context.setSemanticRuleMatches(List.of(match));
        assertThat(guard.validate(parsed, context).allowed()).isTrue();
    }

    @Test
    void rendersEvidenceAndDoesNotClaimNullMeansUnshippedOrPageSizeMeansTotal() {
        var parsed = request("本月未发货的订单", "order_timeline");
        contract.prepare(parsed, null, context());
        var result = new AiAgentQueryResult();
        result.setPlan(parsed.getQueryPlan());
        result.setEntityLabel("外部订单时间轴");
        result.setRows(List.of(Map.of("order_no", "MLS_1")));
        result.setFieldLabels(Map.of("release_date", "下单日期", "warehouse_actual_ship_date", "仓库实际发货日"));
        result.setTimeWindowLabel("本月");
        var response = new AiAgentAnswerRenderer().render(result);
        assertThat(response.getAnswer()).contains("未能核实", "不能视为完整").doesNotContain("共 1");
        assertThat(response.getWarnings()).anyMatch(w -> w.contains("漏录"))
                .anyMatch(w -> w.contains("下单日期：本月"));
    }

    @Test
    void monthEndIsExclusive() {
        var parsed = request("本月未发货的订单", "order_timeline");
        contract.prepare(parsed, null, context());
        var built = sql.build(planner.plan(parsed.getQueryPlan()), context());
        assertThat(built.params()).containsEntry("filter1End", new Timestamp(context().getTimeWindow().endTime()));
        assertThat(built.sql()).contains("ot.release_date < :filter1End").doesNotContain("BETWEEN");
    }


    @ParameterizedTest
    @CsvSource({
            "哪些订单部分发货,发货数量",
            "哪些订单已经全部发完,发货数量",
            "今年没有签订过订单的客户有哪些,排除关联"
    })
    void rejectsQuestionsThatCannotBeProvenByTheSelectedData(String question, String reason) {
        assertThatThrownBy(() -> contract.prepare(request(question, "order_timeline"), null, context()))
                .hasMessageContaining(reason);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-08,2026-09-08T00:00:00",
            "2026-09-08T14:35:20,2026-09-08T14:35:20",
            "2026-09-08 14:35:20,2026-09-08T14:35:20"
    })
    void scalarDatePredicatesKeepTheSpecifiedDayAndTime(String value, String expected) {
        var plan = request("按时间查询", "order_timeline").getQueryPlan();
        plan.setFilters(List.of(filter("release_date", "gte", value)));
        var built = sql.build(planner.plan(plan), context());
        long millis = java.time.LocalDateTime.parse(expected).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        assertThat(built.params()).containsEntry("filter0", new Timestamp(millis));
    }

    @Test
    void invalidDateCannotBecomeNullOrEpochZero() {
        var plan = request("按时间查询", "order_timeline").getQueryPlan();
        plan.setFilters(List.of(filter("release_date", "gte", "不是日期")));
        assertThatThrownBy(() -> sql.build(planner.plan(plan), context())).hasMessageContaining("日期格式无效");
    }

    @Test
    void finalExecutionChecksOriginalConditionsToo() {
        var executor = mock(AiAgentQueryExecutor.class);
        var database = new AiAgentDatabaseQueryService(planner, sql, executor, new AiAgentAnswerRenderer());
        ReflectionTestUtils.setField(database, "queryContractService", contract);
        var context = context();
        var original = request("张海芳的订单", "sales_order");
        original.setSpecialistName("张海芳");
        context.setOriginalParsedQuestion(original);
        var plan = request(original.getRawQuestion(), "sales_order").getQueryPlan();
        plan.setFilters(List.of(filter("owner_name", "eq", "赵琳")));
        assertThat(database.answer(plan, context).getIntent()).isEqualTo("CRM_DATABASE_QUERY_REJECTED");
        verifyNoInteractions(executor);
    }


    private ParsedAiAgentQuestion request(String question, String entity) {
        var parsed = new ParsedAiAgentQuestion();
        parsed.setRawQuestion(question);
        parsed.setIntent("CRM_DATABASE_QUERY");
        var plan = new AiAgentQueryPlan();
        plan.setEntity(entity);
        plan.setQueryType("LIST");
        plan.setSelectFields(entity.equals("customer") ? List.of("name") : List.of("order_no"));
        parsed.setQueryPlan(plan);
        return parsed;
    }

    private AiAgentContext context() {
        var context = new AiAgentContext();
        context.setOrganizationId("org-1");
        context.setUserId("user-1");
        var all = new DeptDataPermissionDTO();
        all.setAll(true);
        context.setDataPermission(all);
        context.setOrderDataPermission(all);
        context.setContractDataPermission(all);
        var zone = ZoneId.of("Asia/Shanghai");
        context.setTimeWindow(new AiAgentTimeWindow(
                LocalDate.of(2026, 9, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
                LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli(), "本月"));
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
