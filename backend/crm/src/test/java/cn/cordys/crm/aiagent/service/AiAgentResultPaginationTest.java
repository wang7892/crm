package cn.cordys.crm.aiagent.service;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentConversationProperties;
import cn.cordys.crm.aiagent.config.AiAgentSqlProperties;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.query.*;
import cn.cordys.crm.aiagent.dto.response.*;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentResultPaginationTest {
    private final AiAgentSemanticSchemaService schema = new AiAgentSemanticSchemaService();
    private final AiAgentQueryPlanner planner = new AiAgentQueryPlanner(schema);
    private final AiAgentSqlBuilder builder = new AiAgentSqlBuilder(new AiAgentQueryPermissionService(null));

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 67, 234, 999, 1000, 1001, 1467})
    void returnsAllUpTo1000AndContinuesLargerResultsWithoutDuplicates(int total) {
        var jdbc = new NamedParameterJdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.getJdbcTemplate().execute("CREATE TABLE contract_info(id INT PRIMARY KEY, manager VARCHAR(50))");
        for (int id = 1; id <= total + 1; id++) {
            jdbc.getJdbcTemplate().update("INSERT INTO contract_info VALUES (?, ?)",
                    id, id <= total ? "赵芳" : "其他负责人");
        }
        var database = new AiAgentDatabaseQueryService(planner, builder, executor(jdbc), new AiAgentAnswerRenderer());
        var plan = plan("contract_info");
        var owner = new AiAgentQueryFilter(); owner.setField("manager"); owner.setOperator("eq"); owner.setValue("赵芳");
        plan.setFilters(List.of(owner));
        Set<String> records = new HashSet<>();
        int offset = 0;
        do {
            var context = context();
            context.setQueryQuestion("赵芳负责的订单有哪些");
            context.setContinuingQuery(offset > 0);
            var response = database.answer(plan, context);
            int returned = Math.min(1000, total - offset);
            assertThat(response.getPoints()).hasSize(returned);
            assertThat(response.getPageInfo().getTotal()).isEqualTo(total);
            assertThat(response.getPageInfo().getOffset()).isEqualTo(offset);
            assertThat(response.getPageInfo().getRemaining()).isEqualTo(total - offset - returned);
            response.getPoints().forEach(point -> assertThat(records.add(point.substring(point.indexOf('\n')))).isTrue());
            offset += returned;
            if (offset == total) {
                assertThat(response.getPageInfo().isContinuable()).isFalse();
                assertThat(context.getContinuation()).isNull();
                if (total > 0 && total <= 1000) assertThat(response.getAnswer()).contains("已返回全部 " + total);
                break;
            }
            assertThat(response.getPageInfo().isContinuable()).isTrue();
            assertThat(context.getContinuation().getNextOffset()).isEqualTo(offset);
            plan = context.getContinuation().getPlan();
            plan.setOffset((long) offset);
        } while (offset < total);
        assertThat(records).hasSize(total);
    }

    @Test
    void executesCountAndPagesAgainstDatabaseWithoutCountingOtherOwners() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new NamedParameterJdbcTemplate(source);
        jdbc.getJdbcTemplate().execute("CREATE TABLE contract_info(id INT PRIMARY KEY, manager VARCHAR(50), customer VARCHAR(50))");
        for (int id = 1; id <= 80; id++) {
            jdbc.getJdbcTemplate().update("INSERT INTO contract_info VALUES (?, ?, ?)", id, id <= 67 ? "赵芳" : "其他负责人", "客户" + id);
        }
        var plan = plan("contract_info");
        var owner = new AiAgentQueryFilter(); owner.setField("manager"); owner.setOperator("eq"); owner.setValue("赵芳");
        plan.setFilters(List.of(owner));
        var executor = executor(jdbc);
        Set<Object> ids = new HashSet<>();
        for (int offset : List.of(0, 30, 60)) {
            plan.setOffset((long) offset);
            var result = executor.execute(builder.build(planner.plan(plan), context()));
            assertThat(result.getWarnings()).isEmpty();
            assertThat(result.getTotalRows()).isEqualTo(67);
            assertThat(result.getRows()).hasSize(offset == 60 ? 7 : 30);
            result.getRows().forEach(row -> assertThat(ids.add(row.values().iterator().next())).isTrue());
        }
        assertThat(ids).hasSize(67);
        plan.setQueryType("AGGREGATE"); plan.setGroupBy(List.of("manager")); plan.setFilters(List.of()); plan.setOffset(0L);
        var metric = new AiAgentQueryMetric(); metric.setFunction("count"); metric.setField("id"); metric.setAlias("count_value");
        plan.setMetrics(List.of(metric));
        var grouped = executor.execute(builder.build(planner.plan(plan), context()));
        assertThat(grouped.getWarnings()).isEmpty();
        assertThat(grouped.getTotalRows()).isEqualTo(2);
        assertThat(grouped.getRows()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer", "contract", "sales_order", "contract_info"})
    void sameFilteredTotalAndThreePagesCover67Records(String entity) {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(contains("agent_result_count"), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(67L);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            int offset = sql.contains("OFFSET 60") ? 60 : sql.contains("OFFSET 30") ? 30 : 0;
            return rows(offset, Math.min(offset + 30, 67));
        });
        var executor = executor(jdbc);
        var seen = new HashSet<Object>();
        for (int offset : List.of(0, 30, 60)) {
            var plan = plan(entity);
            plan.setOffset((long) offset);
            var query = builder.build(planner.plan(plan), context());
            assertThat(query.sql()).contains("LIMIT 30", "ORDER BY");
            assertThat(query.countSql()).doesNotContain("LIMIT", "OFFSET", "ORDER BY");
            var result = executor.execute(query);
            assertThat(result.getTotalRows()).isEqualTo(67);
            var response = new AiAgentAnswerRenderer().render(result);
            assertThat(response.getPageInfo().getRemaining()).isEqualTo(Math.max(0, 67 - offset - 30));
            assertThat(response.getPageInfo().isContinuable()).isEqualTo(offset < 60);
            assertThat(response.getAnswer()).contains("共 67");
            result.getRows().forEach(row -> assertThat(seen.add(row.get("id"))).isTrue());
        }
        assertThat(seen).hasSize(67);
    }

    @Test
    void countUsesSamePermissionsFiltersAndParametersWithoutDisplayLimit() {
        var plan = plan("customer");
        var filter = new AiAgentQueryFilter();
        filter.setField("owner_name"); filter.setOperator("eq"); filter.setValue("赵芳");
        plan.setFilters(List.of(filter));
        var context = context();
        context.getDataPermission().setAll(false);
        context.getDataPermission().setSelf(true);
        var built = builder.build(planner.plan(plan), context);
        assertThat(built.sql()).contains("owner_user.name = :filter0", "c.owner = :userId");
        assertThat(built.countSql()).contains("owner_user.name = :filter0", "c.owner = :userId");
        assertThat(built.params()).containsEntry("filter0", "赵芳").containsEntry("userId", "user");
    }

    @Test
    void groupedCountCountsGroupsRatherThanUnderlyingDetailRecords() {
        var plan = plan("customer");
        plan.setQueryType("AGGREGATE"); plan.setGroupBy(List.of("owner_name"));
        var metric = new AiAgentQueryMetric();
        metric.setFunction("count"); metric.setField("id"); metric.setAlias("customer_count");
        plan.setMetrics(List.of(metric));
        var built = builder.build(planner.plan(plan), context());
        assertThat(built.countSql()).startsWith("SELECT COUNT(*) FROM (").contains("GROUP BY owner_user.name");
        var result = result(plan, rows(0, 30));
        result.setGroupByFields(List.of("owner_name")); result.setTotalRows(67L);
        var response = new AiAgentAnswerRenderer().render(result);
        assertThat(response.getAnswer()).contains("统计分组共 67 组", "还有 37 组");
    }

    @Test
    void failedCountKeepsRowsAndClearlyMarksUnknownTotal() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(rows(0, 30));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("count unavailable"));
        var result = executor(jdbc).execute(builder.build(planner.plan(plan("customer")), context()));
        var response = new AiAgentAnswerRenderer().render(result);
        assertThat(response.getPoints()).hasSize(30);
        assertThat(response.getAnswer()).contains("未能核实", "不能视为完整").doesNotContain("共 30");
        assertThat(response.getPageInfo().getTotal()).isNull();
        assertThat(response.getPageInfo().getRemaining()).isNull();
        assertThat(response.getWarnings()).anyMatch(w -> w.contains("总数统计失败"));
    }

    @Test
    void allRequestAndExplicitLimitAreAppliedBeforeExecution() {
        assertThat(AiAgentResultWindow.requestedSize("赵芳负责的客户有哪些")).isEqualTo(1000);
        assertThat(AiAgentResultWindow.requestedSize("赵芳负责全部的客户有哪些")).isEqualTo(1000);
        assertThat(AiAgentResultWindow.requestedSize("所有订单仅看前5条")).isEqualTo(5);
        assertThat(AiAgentResultWindow.requestedSize("返回999999999条")).isEqualTo(1000);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(rows(0, 67));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(67L);
        var database = new AiAgentDatabaseQueryService(planner, builder, executor(jdbc), new AiAgentAnswerRenderer());
        var context = context(); context.setQueryQuestion("全部客户");
        var response = database.answer(plan("customer"), context);
        verify(jdbc).queryForList(contains("LIMIT 1000"), any(MapSqlParameterSource.class));
        assertThat(response.getPoints()).hasSize(67);
        assertThat(response.getPageInfo().isHasMore()).isFalse();
    }

    @Test
    void savedLargerPageUsesCurrent1000RowDisplayCap() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(rows(1000, 1200));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1467L);
        var database = new AiAgentDatabaseQueryService(planner, builder, executor(jdbc), new AiAgentAnswerRenderer());
        var context = context(); context.setContinuingQuery(true);
        var plan = plan("customer"); plan.setLimit(5000); plan.setOffset(1000L);
        var response = database.answer(plan, context);
        verify(jdbc).queryForList(contains("LIMIT 1000 OFFSET 1000"), any(MapSqlParameterSource.class));
        assertThat(response.getPoints()).hasSize(200);
        assertThat(response.getPageInfo().getRemaining()).isEqualTo(267);
        assertThat(context.getContinuation().getNextOffset()).isEqualTo(1200);
    }

    @Test
    void disabledRowLimitReturnsMoreThan1000AndStillRespectsExplicitCounts() {
        assertThat(AiAgentResultWindow.requestedSize("全部客户", false)).isZero();
        assertThat(AiAgentResultWindow.requestedSize("前5条客户", false)).isEqualTo(5);
        assertThat(AiAgentResultWindow.requestedSize("前1500条客户", false)).isEqualTo(1500);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(rows(0, 1234));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1234L);
        var database = new AiAgentDatabaseQueryService(planner, builder, executor(jdbc), new AiAgentAnswerRenderer());
        var context = context(); context.setQueryQuestion("全部客户"); context.setLimitRows(false);
        var response = database.answer(plan("customer"), context);
        verify(jdbc).queryForList(argThat(sql -> !sql.contains("LIMIT")), any(MapSqlParameterSource.class));
        assertThat(response.getPoints()).hasSize(1234);
        assertThat(response.getPageInfo().isHasMore()).isFalse();
        assertThat(context.getContinuation()).isNull();
    }

    @Test
    void exactly30ReturnedAnd30TotalIsCompleteButUnknownTotalIsNot() {
        var result = result(plan("customer"), rows(0, 30));
        result.setTotalRows(30L);
        assertThat(new AiAgentAnswerRenderer().render(result).getPageInfo().isContinuable()).isFalse();
        assertThat(new AiAgentAnswerRenderer().analysisData(result, 30)).containsEntry("complete", true);
        result.setTotalRows(null);
        assertThat(new AiAgentAnswerRenderer().analysisData(result, 30))
                .containsEntry("truncated", false).containsEntry("complete", false);
    }

    @Test
    void analystTruncationNeverRemovesVisibleRowsOrContinuation() {
        var result = result(plan("customer"), rows(0, 30)); result.setTotalRows(67L);
        var response = new AiAgentAnswerRenderer().render(result);
        response.setPoints(IntStream.range(0, 30).mapToObj(i -> "第 " + i + " 条\n名称：" + "x".repeat(500)).toList());
        var evidence = new AiAgentQueryEvidence();
        evidence.setId("data"); evidence.setQuestion("客户有哪些"); evidence.setResponse(response);
        evidence.setData(new AiAgentAnswerRenderer().analysisData(result, 30));
        var cursor = new AiAgentQueryContinuation(); cursor.setPlan(plan("customer")); cursor.setNextOffset(30);
        evidence.setContinuation(cursor);
        var props = new AiAgentConversationProperties(); props.setMaxDataChars(4000);
        var service = new AiAgentConversationService(mock(AiAgentInternalMapper.class), mock(AiAgentLlmClient.class),
                props, new AiAgentIntentValidator());
        var outcome = service.queryOnly("客户有哪些", context(), ignored -> evidence);
        assertThat(outcome.response().getPoints()).hasSize(30);
        assertThat(outcome.snapshot().getContinuation().getNextOffset()).isEqualTo(30);
        assertThat(outcome.snapshot().getDatasets().getFirst().getResponse().getPoints().size()).isLessThan(30);
        var restored = JSON.parseObject(JSON.toJSONString(outcome.snapshot()), AiAgentConversationSnapshot.class);
        assertThat(restored.getContinuation().getPlan().getEntity()).isEqualTo("customer");
    }

    private AiAgentQueryExecutor executor(NamedParameterJdbcTemplate jdbc) {
        var props = new AiAgentSqlProperties(); props.setEnabled(true);
        var executor = new AiAgentQueryExecutor(new AiAgentSqlGuard(props));
        ReflectionTestUtils.setField(executor, "crmJdbcTemplate", jdbc);
        ReflectionTestUtils.setField(executor, "externalContractJdbcTemplate", jdbc);
        return executor;
    }


    private AiAgentQueryPlan plan(String entity) {
        var plan = new AiAgentQueryPlan(); plan.setEntity(entity); plan.setQueryType("LIST");
        plan.setSelectFields(List.of("id")); plan.setLimit(30); plan.setOffset(0L);
        return plan;
    }

    private AiAgentQueryResult result(AiAgentQueryPlan plan, List<Map<String, Object>> rows) {
        var result = new AiAgentQueryResult(); result.setPlan(plan); result.setRows(rows);
        result.setEntityLabel("客户"); result.setEvidence("customer"); result.setFieldLabels(Map.of("id", "客户ID"));
        return result;
    }

    private List<Map<String, Object>> rows(int start, int end) {
        return IntStream.range(start, end).mapToObj(i -> Map.<String, Object>of("id", i + 1)).toList();
    }

    private AiAgentContext context() {
        var context = new AiAgentContext(); context.setUserId("user"); context.setOrganizationId("org");
        var permission = new DeptDataPermissionDTO(); permission.setAll(true);
        context.setDataPermission(permission); context.setCustomerDataPermission(permission);
        context.setContractDataPermission(permission); context.setOrderDataPermission(permission);
        context.setTimeWindow(new AiAgentTimeWindow(0L, Long.MAX_VALUE, "全部"));
        return context;
    }
}
