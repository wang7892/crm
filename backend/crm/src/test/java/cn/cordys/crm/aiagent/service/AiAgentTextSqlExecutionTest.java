package cn.cordys.crm.aiagent.service;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.config.AiAgentSqlProperties;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentTextSqlExecutionTest {
    private AiAgentTextSqlCompiler compiler;
    private AiAgentSqlQueryService service;
    private NamedParameterJdbcTemplate jdbc;
    private AiAgentContext context;
    private AiAgentInternalMapper mapper;

    @BeforeEach
    void setUp() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new NamedParameterJdbcTemplate(ds);
        jdbc.getJdbcTemplate().execute("CREATE TABLE customer(id VARCHAR(30), name VARCHAR(100), owner VARCHAR(30), phone VARCHAR(30), email VARCHAR(100), organization_id VARCHAR(30), in_shared_pool BOOLEAN)");
        jdbc.getJdbcTemplate().execute("INSERT INTO customer VALUES ('a','Alice','user','13812345678','alice@example.com','org',false),('b','Bob','user','13912345678','bob@example.com','org',false),('c','Hidden owner','other','123','x@x.com','org',false),('d','Hidden org','user','123','x@x.com','other',false),('e','Hidden pool','user','123','x@x.com','org',true)");
        jdbc.getJdbcTemplate().execute("CREATE TABLE contract(id VARCHAR(30), customer_id VARCHAR(30), owner VARCHAR(30), organization_id VARCHAR(30))");
        jdbc.getJdbcTemplate().execute("INSERT INTO contract VALUES ('ct-a','a','user','org'),('ct-b','b','other','org'),('ct-d','d','user','other')");
        jdbc.getJdbcTemplate().execute("CREATE TABLE contract_info(id VARCHAR(30), manager VARCHAR(50), customer VARCHAR(100), amount DECIMAL(10,2))");
        jdbc.getJdbcTemplate().execute("INSERT INTO contract_info VALUES ('x','Allowed','Alice',10),('y','Denied','Bob',20)");
        var realSchema = new AiAgentSemanticSchemaService();
        var schema = mock(AiAgentSemanticSchemaService.class);
        when(schema.findEntity(anyString())).thenAnswer(invocation -> realSchema.findEntity(invocation.getArgument(0)));
        when(schema.findEntity("customer")).thenReturn(Optional.of(relation(realSchema, "customer", "customer c", List.of("id", "name", "owner", "phone", "email"))));
        when(schema.findEntity("contract")).thenReturn(Optional.of(relation(realSchema, "contract", "contract ct", List.of("id", "customer_id", "owner"))));
        when(schema.findEntity("contract_info")).thenReturn(Optional.of(relation(realSchema, "contract_info", "contract_info ci", List.of("id", "manager", "customer", "amount"))));
        mapper = mock(AiAgentInternalMapper.class);
        when(mapper.findUserNamesByDataPermission(any(), any(), any())).thenReturn(List.of("Allowed"));
        compiler = new AiAgentTextSqlCompiler(schema, new AiAgentQueryPermissionService(mapper));
        var properties = new AiAgentSqlProperties(); properties.setEnabled(true);
        var executor = new AiAgentQueryExecutor(new AiAgentSqlGuard(properties));
        ReflectionTestUtils.setField(executor, "crmDataSource", ds);
        ReflectionTestUtils.setField(executor, "externalContractJdbcTemplate", jdbc);
        service = new AiAgentSqlQueryService(properties, compiler, executor, new AiAgentAnswerRenderer());
        context = new AiAgentContext(); context.setOrganizationId("org"); context.setUserId("user");
        context.setQueryQuestion("visible customers"); context.setAnalysisRowLimit(200);
        var self = new DeptDataPermissionDTO(); self.setSelf(true);
        context.setDataPermission(self); context.setCustomerDataPermission(self); context.setContractDataPermission(self);
    }

    private AiAgentSemanticSchemaService.EntitySpec relation(AiAgentSemanticSchemaService schema, String name, String from, List<String> keys) {
        var original = schema.findEntity(name).orElseThrow();
        var fields = new LinkedHashMap<String, AiAgentSemanticSchemaService.FieldSpec>();
        keys.forEach(key -> fields.put(key, original.findField(key).orElseThrow()));
        return new AiAgentSemanticSchemaService.EntitySpec(name, original.label(), original.evidence(),
                original.dataSourceKind(), original.permissionKind(), from, keys, fields);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT id AS id, name AS name FROM customer WHERE 1=1 OR owner='other' ORDER BY name",
            "WITH visible AS (SELECT id,name FROM customer) SELECT id AS id,name AS name FROM visible ORDER BY name",
            "SELECT id AS id,name AS name FROM customer UNION SELECT id,name FROM customer",
            "SELECT id AS id,name AS name FROM customer WHERE EXISTS (SELECT 1 FROM customer nested WHERE nested.id=customer.id)",
            "SELECT id AS id,name AS name FROM (SELECT id,name FROM customer) nested"
    })
    void permissionsSurviveOrCteUnionAndSubqueries(String sql) {
        var response = service.answer(sql, context, 200, 0);
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getSqlQueries()).containsExactly(sql);
        assertThat(response.getPageInfo().getTotal()).isEqualTo(2);
        assertThat(context.getAnalysisData().get("rows").toString()).contains("Alice", "Bob").doesNotContain("Hidden");
    }

    @Test
    void displayedSqlIsTheModelQueryNotTheServerPermissionRewrite() {
        String sql = "SELECT name AS name FROM customer WHERE name='Alice'";
        var response = service.answer(sql, context);
        var compiled = compiler.compile(sql, context, 10, 0);
        assertThat(compiled.sql()).isNotEqualTo(sql).contains(":scope0_orgId");
        assertThat(response.getSqlQueries()).containsExactly(sql);
        assertThat(cn.cordys.common.util.JSON.toJSONString(response))
                .doesNotContain("scope0_orgId", "scope0_userId", "organization_id");
    }

    @Test
    void permissionsApplyIndependentlyToEveryJoinAndNegativeSubquery() {
        service.answer("SELECT c.name AS name, ct.id AS contract_id FROM customer c LEFT JOIN contract ct ON ct.customer_id=c.id ORDER BY name", context);
        String rows = context.getAnalysisData().get("rows").toString();
        assertThat(rows).contains("ct-a", "Bob").doesNotContain("ct-b", "Hidden");
        service.answer("SELECT c.name AS name FROM customer c WHERE NOT EXISTS (SELECT 1 FROM contract ct WHERE ct.customer_id=c.id)", context);
        assertThat(context.getAnalysisData().get("rows").toString()).contains("Bob").doesNotContain("Alice");
        var query = compiler.compile("SELECT c.id AS id, ct.id AS contract_id FROM customer c JOIN contract ct ON ct.customer_id=c.id", context, 10, 0);
        assertThat(query.params()).containsKeys("scope0_orgId", "scope0_userId", "scope1_orgId", "scope1_userId");
    }

    @Test
    void fieldsAreMaskedBeforeAliasesOrExpressionsCanBypassThem() {
        service.answer("SELECT phone AS renamed, CONCAT(email,'!') AS transformed FROM customer WHERE id='a'", context);
        assertThat(context.getAnalysisData().get("rows").toString()).contains("138****5678", "a***@example.com!")
                .doesNotContain("13812345678", "alice@example.com");
        var query = compiler.compile("SELECT name AS name FROM customer WHERE name='Alice'", context, 10, 0);
        assertThat(query.params()).containsValue("Alice");
        assertThat(query.sql()).doesNotContain("'Alice'");
        query = compiler.compile("SELECT name AS name FROM customer WHERE name='O''Brien'", context, 10, 0);
        assertThat(query.params()).containsValue("O'Brien");
    }

    @Test
    void pagingPreservesModelOrderingLimitAndCountAndReappliesCurrentPermissions() {
        String sql = "SELECT id AS id,name AS name FROM customer ORDER BY name DESC LIMIT 2";
        var first = service.answer(sql, context, 1, 0);
        assertThat(first.getSqlQueries()).containsExactly(sql);
        assertThat(first.getPageInfo().getTotal()).isEqualTo(2);
        assertThat(context.getAnalysisData().get("rows").toString()).contains("Bob");
        assertThat(context.getContinuation().getGeneratedSql()).isEqualTo(sql);
        assertThat(context.getContinuation().getNextOffset()).isEqualTo(1);
        assertThat(context.getExecutedQueryPlan()).isNull();
        var second = service.answer(sql, context, 1, 1);
        assertThat(second.getSqlQueries()).containsExactly(sql);
        assertThat(context.getAnalysisData().get("rows").toString()).contains("Alice");
        assertThat(context.getContinuation()).isNull();
        context.setUserId("other");
        service.answer(sql, context, 1, 0);
        assertThat(context.getAnalysisData().get("rows").toString()).contains("Hidden owner").doesNotContain("Alice", "Bob");
    }

    @Test
    void missingPermissionsCannotBecomeFullAccess() {
        context.setDataPermission(null);
        var response = service.answer("SELECT name AS name FROM customer", context);
        assertThat(response.getPageInfo().getTotal()).isZero();
        assertThat(context.getAnalysisData().get("rows")).isEqualTo(List.of());
    }

    @Test
    void aggregationUsesFullPermissionScopedRelationBeforePagination() {
        service.answer("SELECT COUNT(*) AS customer_count FROM customer", context, 1, 0);
        assertThat(context.getAnalysisData().get("rows").toString()).contains("customer_count=2");
        assertThat(context.getAnalysisData().get("complete")).isEqualTo(true);
        assertThat(context.getContinuation()).isNull();
        var response = service.answer("SELECT owner AS owner,COUNT(*) AS customer_count FROM customer GROUP BY owner ORDER BY customer_count DESC", context, 1, 0);
        assertThat(response.getPageInfo().getTotal()).isEqualTo(1);
    }

    @Test
    void all234GroupsReachTableAndChartIndependentlyOfAnalystContext() {
        jdbc.getJdbcTemplate().update("DELETE FROM customer");
        insertVisibleCustomers(234, 6);
        context.setAnalysisRowLimit(100);
        var response = service.answer("SELECT name AS name,COUNT(*) AS customer_count FROM customer GROUP BY name ORDER BY name", context);
        assertThat(response.getPageInfo().getTotal()).isEqualTo(234);
        assertThat(response.getPoints()).hasSize(234);
        assertThat(response.getPageInfo().isHasMore()).isFalse();
        var chart = mappedChart(response);
        assertThat(chart.getCategories()).hasSize(234);
        assertThat(chart.getSeries().getFirst().values()).containsOnly(6d);
        assertThat(chart.isPartial()).isFalse();
        assertThat(context.getAnalysisData()).containsEntry("includedRows", 100).containsEntry("complete", false);
        assertThat(context.getContinuation()).isNull();
    }

    @Test
    void rowSwitchControlsOnlyOuterResultsAndRetainsModelTopNAndPermissions() {
        jdbc.getJdbcTemplate().update("DELETE FROM customer");
        insertVisibleCustomers(1234, 1);
        jdbc.getJdbcTemplate().update("INSERT INTO customer VALUES ('hidden','Hidden','other','123','x@x.com','org',false)");
        String sql = "SELECT name AS name,COUNT(*) AS customer_count FROM customer GROUP BY name ORDER BY name";
        var limited = service.answer(sql, context);
        assertThat(limited.getPoints()).hasSize(1000);
        assertThat(limited.getPageInfo().getTotal()).isEqualTo(1234);
        assertThat(limited.getPageInfo().getRemaining()).isEqualTo(234);
        assertThat(mappedChart(limited).getCategories()).hasSize(1000);
        assertThat(mappedChart(limited).isPartial()).isTrue();

        context.setLimitRows(false);
        var all = service.answer(sql, context);
        assertThat(all.getPoints()).hasSize(1234).noneMatch(point -> point.contains("Hidden"));
        assertThat(mappedChart(all).getCategories()).hasSize(1234);
        assertThat(mappedChart(all).isPartial()).isFalse();
        assertThat(all.getPageInfo().isHasMore()).isFalse();
        assertThat(context.getContinuation()).isNull();
        for (boolean enabled : List.of(true, false)) {
            context.setLimitRows(enabled);
            var top = service.answer(sql + " LIMIT 5", context);
            assertThat(top.getPoints()).hasSize(5);
            assertThat(top.getPageInfo().getTotal()).isEqualTo(5);
            service.answer("SELECT COUNT(*) AS customer_count FROM customer", context);
            assertThat(context.getAnalysisData().get("rows").toString()).contains("customer_count=1234");
        }
    }

    @Test
    void executionTraceReportsActualPaginationWithoutPermissionSqlOrBindings() {
        String sql = "SELECT name AS name FROM customer ORDER BY name";
        try (var scope = AiAgentProgress.captureIfAbsent()) {
            service.answer(sql, context, 1, 0);
            var result = AiAgentProgress.steps().getLast();
            assertThat(result.name()).isEqualTo("query_result");
            assertThat(result.details().getFirst().content()).isEqualTo(sql);
            assertThat(result.details().getLast().content()).contains("本次返回：1", "匹配总数：2", "仍有后续结果");
            assertThat(cn.cordys.common.util.JSON.toJSONString(AiAgentProgress.steps())).doesNotContain("scope0_", "organization_id");
        }
        try (var scope = AiAgentProgress.captureIfAbsent()) {
            service.answer(sql, context, 1, 1);
            assertThat(AiAgentProgress.steps().getLast().details().getLast().content()).contains("本次查询结果已全部返回");
        }
    }

    private cn.cordys.crm.aiagent.dto.response.AiAgentChart mappedChart(cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse response) {
        var config = Map.<String,Object>of("type", "column", "axis", Map.of("x", Map.of("value", "name"), "y", List.of(Map.of("value", "customer_count"))));
        return new AiAgentChartService().build(response.getResultTable(), config, response.getPageInfo().isHasMore()).getFirst();
    }

    private void insertVisibleCustomers(int groups, int copies) {
        jdbc.getJdbcTemplate().batchUpdate("INSERT INTO customer VALUES (?, ?, 'user', '123', 'x@x.com', 'org', false)",
                java.util.stream.IntStream.range(0, groups * copies)
                        .mapToObj(i -> new Object[]{"visible-" + i, "Customer-" + String.format("%04d", i / copies)})
                        .toList());
    }

    @Test
    void externalDatabaseUsesManagerPermissionsAndRejectsCrossDatabaseJoins() {
        service.answer("SELECT customer AS customer, amount AS amount FROM contract_info", context);
        assertThat(context.getAnalysisData().get("rows").toString()).contains("Alice").doesNotContain("Bob");
        assertThatThrownBy(() -> compiler.compile("SELECT c.name AS name FROM customer c JOIN contract_info ci ON ci.customer=c.name", context, 10, 0))
                .hasMessageContaining("separate queries");
        assertThatThrownBy(() -> compiler.compile("WITH customer AS (SELECT id FROM contract) SELECT id AS id FROM customer", context, 10, 0))
                .hasMessageContaining("shadow");
        assertThatThrownBy(() -> compiler.compile("SELECT * FROM customer", context, 10, 0)).hasMessageContaining("SELECT *");
        assertThatThrownBy(() -> service.answer("SELECT password AS password FROM customer", context))
                .hasMessageContaining("SQLState").hasMessageNotContaining("SELECT * FROM");
    }

    @Test
    void realMysqlBusinessSchemasAndAllPermissionVariantsCanBeRewrittenAndValidated() {
        var realSchema = new AiAgentSemanticSchemaService();
        var nativeCompiler = new AiAgentTextSqlCompiler(realSchema, new AiAgentQueryPermissionService(mapper));
        var properties = new AiAgentSqlProperties(); properties.setEnabled(true);
        var guard = new AiAgentSqlGuard(properties);
        for (String mode : List.of("all", "self", "department", "collaboration", "none")) {
            var permission = new DeptDataPermissionDTO();
            switch (mode) {
                case "all" -> permission.setAll(true);
                case "self" -> permission.setSelf(true);
                case "department" -> permission.setDeptIds(java.util.Set.of("dept"));
                case "collaboration" -> permission.setVisible(true);
            }
            context.setDataPermission(permission); context.setContractDataPermission(permission); context.setOrderDataPermission(permission);
            for (var entity : realSchema.entities().values()) {
                String field = entity.defaultFields().getFirst();
                var query = nativeCompiler.compile("SELECT " + field + " AS value FROM " + entity.name(), context, 10, 0);
                assertThat(guard.validate(query.sql()).allowed()).as("%s / %s: %s", mode, entity.name(), guard.validate(query.sql()).reason()).isTrue();
                assertThat(guard.validate(query.countSql()).allowed()).as("%s count / %s", mode, entity.name()).isTrue();
                assertThatCode(() -> org.springframework.jdbc.core.namedparam.NamedParameterUtils.substituteNamedParameters(query.sql(),
                        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(query.params())))
                        .as("%s bindings / %s", mode, entity.name()).doesNotThrowAnyException();
            }
        }
    }
}
