package cn.cordys.crm.aiagent.service;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.common.service.DataScopeService;
import cn.cordys.crm.aiagent.config.AiAgentSqlProperties;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.query.*;
import cn.cordys.crm.aiagent.dto.request.AiAgentChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentRankingRegressionTest {
    private final AiAgentSemanticSchemaService schema = new AiAgentSemanticSchemaService();
    private final AiAgentQueryPlanner planner = new AiAgentQueryPlanner(schema);
    private final AiAgentQueryContractService contract = new AiAgentQueryContractService(schema);
    private final AiAgentSqlBuilder builder = new AiAgentSqlBuilder(new AiAgentQueryPermissionService(null));

    @ParameterizedTest
    @ValueSource(strings = {"1~6月份销售额前10的客户", "1—6月销售额前十", "2026年1月至6月", "2026年一月到六月",
            "2026年1月1日至2026年6月30日", "2026年1月1日到6月30日", "2026-01-01至2026-06-30",
            "2026/01/01~2026/06/30", "2026-01至2026-06"})
    void completeDateRangesNeverBecomeAllTimeOrJanuary(String question) {
        AiAgentTimeWindow window = ReflectionTestUtils.invokeMethod(new AiAgentChatService(), "resolveQuestionTimeWindow",
                question, LocalDate.of(2026, 9, 19));
        assertThat(window.startTime()).isEqualTo(millis("2026-01-01"));
        assertThat(window.endTime()).isEqualTo(millis("2026-07-01"));
        assertThat(AiAgentQueryContractService.hasTimeExpression(question)).isTrue();
    }

    @Test
    void crossYearAndInvalidDateRanges() {
        var window = AiAgentDateRangeParser.resolve("2025年11月至2026年2月", LocalDate.of(2026, 9, 19));
        assertThat(window.startTime()).isEqualTo(millis("2025-11-01"));
        assertThat(window.endTime()).isEqualTo(millis("2026-03-01"));
        assertThatThrownBy(() -> AiAgentDateRangeParser.resolve("2026年6月至1月", LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiAgentDateRangeParser.resolve("2026年1月至13月", LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiAgentDateRangeParser.resolve("2026年1月至3月对比2025年4月至6月", LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void topTenUsesFullPeriodTotalsForEachCurrencyRatherThanFirstTenGroups() {
        var jdbc = database();
        for (String currency : List.of("USD", "CNY")) {
            for (int customer = 1; customer <= 15; customer++) {
                add(jdbc, currency + customer, currency, customer * 10, "2026-01-02 00:00:00");
                add(jdbc, currency + customer, currency, customer * 100, "2026-06-30 23:59:59");
            }
            add(jdbc, "outsideJuly", currency, 9999999, "2026-07-01 00:00:00");
            add(jdbc, "outsidePriorYear", currency, 9999999, "2025-12-31 23:59:59");
        }
        var context = context("2026年1月1日至2026年6月30日销售金额前10的客户");
        var plan = salesPlan();
        prepare(plan, context);
        assertThat(plan.getEntity()).isEqualTo("contract_info");
        assertThat(plan.getTopN()).isEqualTo(10);
        plan.setLimit(200);
        var built = builder.build(planner.plan(plan), context);
        var result = executor(jdbc).execute(built);
        assertThat(result.getWarnings()).isEmpty();
        assertThat(result.getTotalRows()).isEqualTo(20);
        assertThat(result.getRows()).hasSize(20);
        for (String currency : List.of("USD", "CNY")) {
            var group = result.getRows().stream().filter(row -> currency.equals(row.get("currency"))).toList();
            assertThat(group).hasSize(10);
            assertThat(group.getFirst().get("customer")).isEqualTo(currency + "15");
            assertThat(group.getLast().get("customer")).isEqualTo(currency + "6");
            assertThat((BigDecimal) group.getFirst().get("sales_amount")).isEqualByComparingTo("1650");
            assertThat(group).extracting(row -> ((Number) row.get("ranking_position")).intValue())
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }
        var response = service(jdbc).answer(plan, context);
        assertThat(response.getPoints()).hasSize(20);
        assertThat(response.getPageInfo().isContinuable()).isFalse();
        assertThat(response.getAnswer()).contains("按币种分别排名", "前 10 名", "共 20").doesNotContain("还有");
    }

    @Test
    void sorting1234GroupsQueriesTheWholePopulationAndPages1000Then234() {
        var jdbc = database();
        for (int i = 1; i <= 1234; i++) add(jdbc, "客户" + i, "USD", i, "2026-06-10 00:00:00");
        var context = context("1~6月份销售额按客户从高到低排序");
        var plan = salesPlan(); prepare(plan, context);
        var database = service(jdbc);
        var first = database.answer(plan, context);
        assertThat(first.getPoints()).hasSize(1000);
        assertThat(first.getPoints().getFirst()).contains("客户1234");
        assertThat(first.getPageInfo().getTotal()).isEqualTo(1234);
        assertThat(first.getPageInfo().getRemaining()).isEqualTo(234);
        plan = context.getContinuation().getPlan(); plan.setOffset(1000L);
        var next = context("1~6月份销售额按客户从高到低排序"); next.setContinuingQuery(true);
        var last = database.answer(plan, next);
        assertThat(last.getPoints()).hasSize(234);
        assertThat(last.getPoints().getFirst()).contains("客户234");
        assertThat(last.getPoints().getLast()).contains("客户1\n");
        assertThat(last.getPageInfo().isContinuable()).isFalse();
    }

    @Test
    void genericAggregatesCanRankCountsAndRejectUnknownSortExpressions() {
        var plan = new AiAgentQueryPlan(); plan.setEntity("contract_info"); plan.setQueryType("AGGREGATE");
        plan.setGroupBy(List.of("customer"));
        var metric = new AiAgentQueryMetric(); metric.setFunction("count"); metric.setField("id"); metric.setAlias("orders");
        plan.setMetrics(List.of(metric));
        AiAgentRankingPolicy.apply(plan, "订单数量最低的前五名客户");
        var jdbc = database();
        for (int i = 0; i < 6; i++) for (int j = 0; j <= i; j++) add(jdbc, "客户" + i, "USD", 1, "2026-06-01 00:00:00");
        var result = executor(jdbc).execute(builder.build(planner.plan(plan), context("")));
        assertThat(result.getTotalRows()).isEqualTo(5);
        assertThat(result.getRows()).extracting(row -> ((Number) row.get("orders")).intValue()).containsExactly(1, 2, 3, 4, 5);
        var invalid = new AiAgentQueryOrder(); invalid.setField("orders desc; DROP TABLE contract_info");
        plan.setOrderBy(List.of(invalid));
        assertThatThrownBy(() -> planner.plan(plan)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void monthlyRankingKeepsSeparateMonthAndCurrencyPartitions() {
        var context = context("2026年1月至6月每月销售额前10的客户");
        var plan = salesPlan(); prepare(plan, context);
        assertThat(plan.getGroupBy()).contains("order_month", "customer", "currency");
        var query = builder.build(planner.plan(plan), context);
        assertThat(query.sql()).contains("DATE_FORMAT(ci.release_date", "PARTITION BY currency, order_month");
        assertThat(query.plan().getTopN()).isEqualTo(10);
    }


    private AiAgentQueryPlan salesPlan() {
        var plan = new AiAgentQueryPlan(); plan.setEntity("sales_order"); plan.setQueryType("AGGREGATE");
        plan.setGroupBy(List.of("customer_name", "currency")); plan.setLimit(10);
        return plan;
    }

    private void prepare(AiAgentQueryPlan plan, AiAgentContext context) {
        var parsed = new ParsedAiAgentQuestion(); parsed.setRawQuestion(context.getQueryQuestion()); parsed.setQueryPlan(plan);
        contract.prepare(parsed, null, context); context.setOriginalParsedQuestion(parsed);
    }

    private AiAgentDatabaseQueryService service(NamedParameterJdbcTemplate jdbc) {
        var service = new AiAgentDatabaseQueryService(planner, builder, executor(jdbc), new AiAgentAnswerRenderer());
        ReflectionTestUtils.setField(service, "queryContractService", contract);
        return service;
    }

    private NamedParameterJdbcTemplate database() {
        var jdbc = new NamedParameterJdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.getJdbcTemplate().execute("CREATE TABLE contract_info(id INT AUTO_INCREMENT PRIMARY KEY, customer VARCHAR(100), currency VARCHAR(10), amount DECIMAL(20,2), release_date TIMESTAMP)");
        return jdbc;
    }

    private void add(NamedParameterJdbcTemplate jdbc, String customer, String currency, int amount, String date) {
        jdbc.getJdbcTemplate().update("INSERT INTO contract_info(customer,currency,amount,release_date) VALUES (?,?,?,?)", customer, currency, amount, date);
    }

    private AiAgentQueryExecutor executor(NamedParameterJdbcTemplate jdbc) {
        var properties = new AiAgentSqlProperties(); properties.setEnabled(true);
        var executor = new AiAgentQueryExecutor(new AiAgentSqlGuard(properties));
        ReflectionTestUtils.setField(executor, "externalContractJdbcTemplate", jdbc);
        return executor;
    }

    private DeptDataPermissionDTO permission() { var all = new DeptDataPermissionDTO(); all.setAll(true); return all; }

    private AiAgentContext context(String question) {
        var context = new AiAgentContext(); context.setUserId("user"); context.setOrganizationId("org");
        context.setDataPermission(permission()); context.setContractDataPermission(permission());
        context.setQueryQuestion(question);
        context.setTimeWindow(AiAgentDateRangeParser.resolve(question, LocalDate.of(2026, 9, 19)));
        return context;
    }

    private long millis(String day) { return LocalDate.parse(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
}
