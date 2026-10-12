package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.AiAgentTimeWindow;
import cn.cordys.crm.aiagent.config.AiAgentSemanticRagProperties;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryFilter;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryMetric;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.common.dto.DeptDataPermissionDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentOrderTimelineSchemaTest {

    private final AiAgentSemanticSchemaService schema = new AiAgentSemanticSchemaService();

    @Test
    void shouldExposeOrderTimelineWithChineseFieldSemantics() {
        AiAgentSemanticSchemaService.EntitySpec entity = schema.findEntity("order_timeline").orElseThrow();

        assertThat(entity.dataSourceKind()).isEqualTo(AiAgentSemanticSchemaService.DataSourceKind.EXTERNAL_CONTRACT);
        assertThat(entity.permissionKind()).isEqualTo(AiAgentSemanticSchemaService.PermissionKind.EXTERNAL_CONTRACT);
        assertThat(entity.evidence()).isEqualTo("mls_agent_data.order_timeline");
        assertThat(entity.resolveField("仓库实际发货日").orElseThrow().key())
                .isEqualTo("warehouse_actual_ship_date");
        assertThat(entity.resolveField("库龄天数").orElseThrow().key()).isEqualTo("stock_age");
        assertThat(entity.resolveField("delivery_date").orElseThrow().valueKind())
                .isEqualTo(AiAgentSemanticSchemaService.ValueKind.SQL_TIMESTAMP);
    }

    @Test
    void shouldBuildControlledTimelinePlanOnExternalDataSource() {
        AiAgentQueryPermissionService permissionService = new AiAgentQueryPermissionService(null);
        AiAgentQueryPlanner planner = new AiAgentQueryPlanner(schema);
        AiAgentQueryPlan raw = new AiAgentQueryPlan();
        raw.setEntity("order_timeline");
        raw.setQueryType("LIST");
        raw.setSelectFields(List.of("order_no", "delivery_date", "warehouse_actual_ship_date", "stock_age"));
        AiAgentQueryFilter filter = new AiAgentQueryFilter();
        filter.setField("order_no");
        filter.setOperator("eq");
        filter.setValue("MLS_242241");
        raw.setFilters(List.of(filter));

        AiAgentQueryPlanner.PlannedQuery planned = planner.plan(raw);
        AiAgentContext context = new AiAgentContext();
        context.setOrganizationId("org-1");
        context.setUserId("user-1");
        DeptDataPermissionDTO all = new DeptDataPermissionDTO();
        all.setAll(true);
        context.setContractDataPermission(all);
        AiAgentQueryPermissionService.PermissionSql permission = permissionService.build(planned.entity(), context);
        AiAgentSqlBuilder.BuiltQuery built = new AiAgentSqlBuilder(permissionService).build(planned, context);

        assertThat(planned.entity().fromSql()).contains("order_timeline ot", "contract_info ci");
        assertThat(permission.whereSql()).isEmpty();
        assertThat(built.sql()).contains(
                "ot.order_no AS order_no",
                "ot.delivery_date AS delivery_date",
                "FROM order_timeline ot",
                "JOIN contract_info ci ON ci.order_no = ot.order_no");
        assertThat(planned.queryPlan().getSelectFields())
                .containsExactly("order_no", "delivery_date", "warehouse_actual_ship_date", "stock_age");
    }


    @Test
    void shouldNormalizeOrderTimestampGroupingToMonthBucket() {
        AiAgentQueryPermissionService permissionService = new AiAgentQueryPermissionService(null);
        AiAgentQueryPlanner planner = new AiAgentQueryPlanner(schema);
        AiAgentQueryPlan raw = new AiAgentQueryPlan();
        raw.setEntity("sales_order");
        raw.setQueryType("AGGREGATE");
        raw.setGroupBy(List.of("order_time"));
        AiAgentQueryMetric metric = new AiAgentQueryMetric();
        metric.setFunction("count_distinct");
        metric.setField("order_no");
        metric.setAlias("order_count");
        raw.setMetrics(List.of(metric));

        AiAgentContext context = new AiAgentContext();
        context.setOrganizationId("org-1");
        context.setUserId("user-1");
        AiAgentQueryPlanner.PlannedQuery planned = planner.plan(raw);
        AiAgentSqlBuilder.BuiltQuery built = new AiAgentSqlBuilder(permissionService).build(planned, context);

        assertThat(planned.queryPlan().getGroupBy()).containsExactly("order_month");
        assertThat(built.sql()).contains("DATE_FORMAT(FROM_UNIXTIME(so.order_time / 1000), '%Y-%m')");
    }


    @Test
    void shouldLeaveAllDataUnrestrictedWhenCurrentWindowMeansAll() {
        AiAgentQueryPermissionService permissionService = new AiAgentQueryPermissionService(null);
        AiAgentQueryPlanner planner = new AiAgentQueryPlanner(schema);
        AiAgentQueryPlan raw = new AiAgentQueryPlan();
        raw.setEntity("order_timeline");
        raw.setQueryType("LIST");
        raw.setSelectFields(List.of("order_no", "delivery_date"));
        AiAgentQueryFilter filter = new AiAgentQueryFilter();
        filter.setField("delivery_date");
        filter.setOperator("between");
        filter.setValue("CURRENT_TIME_WINDOW");
        raw.setFilters(List.of(filter));

        AiAgentContext context = new AiAgentContext();
        context.setTimeWindow(new AiAgentTimeWindow(0L, Long.MAX_VALUE, "全部"));
        AiAgentSqlBuilder.BuiltQuery built = new AiAgentSqlBuilder(permissionService)
                .build(planner.plan(raw), context);

        assertThat(built.sql()).doesNotContain("ot.delivery_date BETWEEN");
        assertThat(built.params()).doesNotContainKey("filter0Start");
    }
}
