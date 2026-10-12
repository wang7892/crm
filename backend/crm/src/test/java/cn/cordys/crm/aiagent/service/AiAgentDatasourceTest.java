package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.*;
import cn.cordys.crm.aiagent.mapper.AiAgentDatasourceMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiAgentDatasourceTest {
    @TempDir Path root;
    AiAgentDatasourceConnections connections;
    AiAgentDatasourceSecrets secrets;
    AiAgentTabularImportService imports;
    AiAgentDatasourceService sources;
    AiAgentDatasourceMapper mapper;
    Map<String, Stored> records;

    @BeforeEach void setup() {
        connections = new AiAgentDatasourceConnections(root.toString());
        secrets = new AiAgentDatasourceSecrets(root.toString());
        imports = new AiAgentTabularImportService(connections);
        mapper = mock(AiAgentDatasourceMapper.class); records = new HashMap<>();
        when(mapper.insert(any())).thenAnswer(invocation -> { Stored s = invocation.getArgument(0); records.put(s.getId(), s); return 1; });
        when(mapper.get(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            Stored s = records.get(invocation.getArgument(0));
            return s != null && s.getOrganizationId().equals(invocation.getArgument(1)) && s.getOwnerId().equals(invocation.getArgument(2))
                    ? JSON.parseObject(JSON.toJSONString(s), Stored.class) : null;
        });
        when(mapper.update(any(), anyLong())).thenAnswer(invocation -> {
            Stored s = invocation.getArgument(0); long previous = invocation.getArgument(1);
            if (records.get(s.getId()).getRevision() != previous) return 0;
            records.put(s.getId(), s); return 1;
        });
        when(mapper.delete(anyString(), anyString(), anyString())).thenAnswer(invocation -> records.remove(invocation.getArgument(0)) == null ? 0 : 1);
        sources = new AiAgentDatasourceService(mapper, connections, secrets, imports);
    }

    private View uploadCsv() {
        String csv = "product,amount,notes\r\nApple,10,\"one,two\"\r\nOrange,20,\"multi\r\nline\"\r\nPear,30,ok\r\n";
        return sources.upload("Sales", new MockMultipartFile("file", "sales.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)), "UTF-8", "org", "user");
    }

    private Config request(View v) {
        Config c = new Config(); c.setId(v.getId()); c.setName(v.getName()); c.setType(v.getType());
        c.setRevision(v.getRevision()); c.setSelectedTables(v.getTables().stream().map(Table::getName).toList());
        return c;
    }

    @Test void editedDescriptionsAndManualRelationsAreValidatedAndPersisted() {
        var uploaded = uploadCsv(); var config = request(uploaded);
        var table = uploaded.getTables().getFirst(); table.setComment("销售报表");
        var column = table.getColumns().getFirst(); column.setComment("产品品种");
        config.setMetadata(List.of(table));
        var relation = new Relation(); relation.setSourceTable(table.getName()); relation.setTargetTable(table.getName());
        relation.setSourceColumn(column.getName()); relation.setTargetColumn(column.getName());
        config.setRelations(List.of(relation));
        var saved = sources.save(config, "org", "user");
        assertThat(saved.getTables().getFirst().getComment()).isEqualTo("销售报表");
        assertThat(saved.getTables().getFirst().getColumns().getFirst().getComment()).isEqualTo("产品品种");
        assertThat(context(saved).getDatasourceRelations()).containsExactly(relation);
        relation.setTargetColumn("missing_column");
        assertThatThrownBy(() -> AiAgentDatasourceService.validateRelations(saved.getTables(), saved.getSelectedTables(), List.of(relation)))
                .hasMessageContaining("字段不存在");
        relation.setTargetColumn(column.getName()); relation.setTargetTable("private_table");
        assertThatThrownBy(() -> AiAgentDatasourceService.validateRelations(saved.getTables(), saved.getSelectedTables(), List.of(relation)))
                .hasMessageContaining("选中的表");
    }

    @Test void discoversForeignKeysWhenJdbcReturnsCatalogForPostgresqlStyleInspection() throws Exception {
        String url = "jdbc:h2:mem:foreign_keys_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE customers (id INT PRIMARY KEY)");
            sql.execute("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT REFERENCES customers(id))");
        }
        var inspector = new AiAgentDatasourceConnections(root.toString()) {
            @Override public Connection open(Config config) throws SQLException { return DriverManager.getConnection(url); }
        };
        Config config = new Config(); config.setType("POSTGRESQL"); config.setSchema("PUBLIC");
        var tables = inspector.inspect(config);
        var relations = tables.stream().flatMap(t -> t.getRelations().stream()).toList();
        assertThat(relations).hasSize(1);
        assertThat(relations.getFirst().getSourceTable()).isEqualTo("ORDERS");
        assertThat(relations.getFirst().getTargetTable()).isEqualTo("CUSTOMERS");
        assertThat(relations.getFirst().getSourceColumn()).isEqualTo("CUSTOMER_ID");
    }

    private AiAgentContext context(View v) {
        AiAgentContext c = new AiAgentContext(); c.setUserId("user"); c.setOrganizationId("org"); sources.populateContext(v.getId(), c); return c;
    }

    @Test void csvImportsAndRunsRealSelectWithCountAndPagination() {
        View uploaded = uploadCsv();
        assertThat(uploaded.getSelectedTables()).isEmpty();
        assertThatThrownBy(() -> context(uploaded)).hasMessageContaining("尚未选择");
        View saved = sources.save(request(uploaded), "org", "user");
        AiAgentDatasourceQueryService queries = new AiAgentDatasourceQueryService(sources);
        AiAgentContext context = context(saved);
        var first = queries.execute("SELECT \"product\" AS product, \"amount\" AS amount, \"notes\" AS notes FROM table_1 ORDER BY amount DESC", context, 2, 0);
        assertThat(first.getTotalRows()).isEqualTo(3);
        assertThat(first.getRows()).hasSize(2);
        assertThat(first.getRows().getFirst().get("product")).isEqualTo("Pear");
        assertThat(first.getRows().get(1).get("notes")).isEqualTo("multi\r\nline");
        var second = queries.execute("SELECT \"product\" AS product, \"amount\" AS amount FROM table_1 ORDER BY amount DESC", context, 2, 2);
        assertThat(second.getRows()).hasSize(1);
        assertThat(second.getRows().getFirst().get("product")).isEqualTo("Apple");
        var aggregate = queries.execute("SELECT SUM(\"amount\") AS total_amount FROM table_1", context, 10, 0);
        assertThat(aggregate.getRows().getFirst().get("total_amount").toString()).startsWith("60");
        assertThat(JSON.toJSONString(saved)).doesNotContain("password\":", "agent_reader");
    }

    @Test void fileDatabaseSurvivesNewServiceInstancesAndReaderCannotWrite() throws Exception {
        View v = sources.save(request(uploadCsv()), "org", "user");
        var restarted = new AiAgentDatasourceService(mapper, new AiAgentDatasourceConnections(root.toString()),
                new AiAgentDatasourceSecrets(root.toString()), imports);
        AiAgentContext c = context(v);
        try (Connection db = restarted.openForQuery(c); Statement statement = db.createStatement()) {
            assertThatThrownBy(() -> statement.execute("DELETE FROM \"table_1\"")).isInstanceOf(SQLException.class);
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM \"table_1\"")) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(3); }
        }
        sources.delete(v.getId(), "org", "user");
        assertThat(Files.exists(connections.filePath(v.getId()).resolveSibling(v.getId() + ".mv.db"))).isFalse();
    }

    @Test void ownerAndOrganizationAreRequiredForEveryOperation() {
        View v = uploadCsv();
        assertThatThrownBy(() -> sources.get(v.getId(), "other", "user")).hasMessageContaining("无权");
        assertThatThrownBy(() -> sources.get(v.getId(), "org", "other")).hasMessageContaining("无权");
        assertThatThrownBy(() -> sources.save(request(v), "org", "other")).hasMessageContaining("无权");
        assertThatThrownBy(() -> sources.delete(v.getId(), "other", "user")).hasMessageContaining("无权");
        assertThatThrownBy(() -> sources.tables(request(v), "org", "other")).hasMessageContaining("无权");
    }

    @Test void staleRevisionAndUnknownTablesAreRejected() {
        View v = uploadCsv(); Config c = request(v);
        c.setSelectedTables(List.of("not_imported"));
        assertThatThrownBy(() -> sources.save(c, "org", "user")).hasMessageContaining("请选择");
        Config stale = request(v); View saved = sources.save(stale, "org", "user");
        assertThatThrownBy(() -> sources.save(stale, "org", "user")).hasMessageContaining("已被修改");
        AiAgentContext oldContext = context(saved);
        String fingerprint = AiAgentConversationService.permissionFingerprint(oldContext);
        sources.save(request(saved), "org", "user");
        assertThatThrownBy(() -> sources.openForQuery(oldContext)).hasMessageContaining("变更");
        assertThat(AiAgentConversationService.permissionFingerprint(context(sources.get(v.getId(), "org", "user")))).isNotEqualTo(fingerprint);
    }

    @Test void encryptsWithRandomNoncesAndDecryptsAcrossRestarts() throws Exception {
        String encrypted = secrets.encrypt("database-secret");
        assertThat(encrypted).doesNotContain("database-secret");
        assertThat(secrets.encrypt("database-secret")).isNotEqualTo(encrypted);
        assertThat(new AiAgentDatasourceSecrets(root.toString()).decrypt(encrypted)).isEqualTo("database-secret");
        byte[] data = Base64.getDecoder().decode(encrypted); data[data.length - 1] ^= 1;
        assertThatThrownBy(() -> secrets.decrypt(Base64.getEncoder().encodeToString(data))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AiAgentDatasourceSecrets(root.resolve("missing").toString()).decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test void emptyEditPasswordRetainsOwnedDatabaseSecret() {
        Stored s = new Stored(); s.setId("source"); s.setType("MYSQL"); s.setOrganizationId("org"); s.setOwnerId("user");
        Config old = database("MYSQL"); old.setPassword(secrets.encrypt("old-secret")); s.setConfigJson(JSON.toJSONString(old)); records.put(s.getId(), s);
        Config edit = database("MYSQL"); edit.setId("source"); edit.setPassword("");
        assertThat(sources.resolved(edit, "org", "user").getPassword()).isEqualTo("old-secret");
        edit.setPassword("new-secret");
        assertThat(sources.resolved(edit, "org", "user").getPassword()).isEqualTo("new-secret");
    }

    @Test void excelImportsMultipleSheetsAndUsesCachedFormulas() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var one = workbook.createSheet("销售"); one.createRow(0).createCell(0).setCellValue("金额"); one.createRow(1).createCell(0).setCellFormula("25/2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            var two = workbook.createSheet("库存"); two.createRow(0).createCell(0).setCellValue("qty"); two.createRow(1).createCell(0).setCellValue(3);
            workbook.write(out); bytes = out.toByteArray();
        }
        View v = sources.upload("Excel", new MockMultipartFile("file", "sales.xlsx", "application/octet-stream", bytes), "UTF-8", "org", "user");
        assertThat(v.getTables()).hasSize(2);
        assertThat(v.getTables().getFirst().getColumns().getFirst().getName()).isEqualTo("col_1");
        assertThat(v.getTables().getFirst().getColumns().getFirst().getComment()).isEqualTo("金额");
        Config c = request(v); c.setSelectedTables(List.of("table_1")); v = sources.save(c, "org", "user");
        AiAgentContext context = context(v);
        assertThat(context.getDatasourceSchema()).hasSize(1);
        var result = new AiAgentDatasourceQueryService(sources).execute("SELECT SUM(\"col_1\") AS amount FROM table_1", context, 20, 0);
        assertThat(result.getRows().getFirst().get("amount").toString()).startsWith("12.5");
        assertThatThrownBy(() -> AiAgentDatasourceQueryService.compile("SELECT qty AS qty FROM table_2", context, 20, 0)).hasMessageContaining("选中");
    }

    @ParameterizedTest @ValueSource(strings = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER", "FILE"})
    void dialectPaginationAndCtesAreGeneratedForEachType(String type) {
        AiAgentContext c = queryContext(type);
        String input = "WITH totals AS (SELECT amount AS amount FROM products) SELECT amount AS amount FROM totals ORDER BY amount DESC";
        var q = AiAgentDatasourceQueryService.compile(input, c, 10, 20);
        assertThat(q.sql()).startsWith("WITH totals AS");
        assertThat(q.countSql()).startsWith("WITH totals AS");
        assertThat(q.sql()).contains("ORDER BY 1 DESC");
        if (Set.of("SQLSERVER", "ORACLE").contains(type)) assertThat(q.sql()).endsWith("OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY");
        else assertThat(q.sql()).endsWith("LIMIT 10 OFFSET 20");
        if (type.equals("SQLSERVER")) assertThat(q.sql()).doesNotContain("FROM totals ORDER BY");
    }

    @ParameterizedTest @ValueSource(strings = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER", "FILE"})
    void eachDialectSupportsDisablingDefaultLimitWithoutChangingBusinessTopN(String type) {
        var c = queryContext(type); c.setLimitRows(false);
        var all = AiAgentDatasourceQueryService.compile("SELECT amount AS amount FROM products ORDER BY amount DESC", c, 0, 0);
        assertThat(all.sql()).doesNotContain("LIMIT", "FETCH", "TOP", "OFFSET").contains("ORDER BY 1 DESC");
        assertThat(all.plan().getLimit()).isZero();
        String topSql = "SELECT " + (type.equals("SQLSERVER") ? "TOP 5 " : "") + "amount AS amount FROM products ORDER BY amount DESC"
                + (type.equals("SQLSERVER") ? "" : type.equals("ORACLE") ? " FETCH FIRST 5 ROWS ONLY" : " LIMIT 5");
        var top = AiAgentDatasourceQueryService.compile(topSql, c, 0, 0);
        assertThat(top.sql()).contains(type.equals("SQLSERVER") ? "TOP 5" : type.equals("ORACLE") ? "FETCH FIRST 5" : "LIMIT 5");
        assertThatThrownBy(() -> AiAgentDatasourceQueryService.compile("SELECT amount AS amount FROM hidden", c, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fileQueryReturnsAll1234RowsWhenDisabledAndDoesNotLimitAggregateInputs() {
        StringBuilder csv = new StringBuilder("product,amount\n");
        for (int i = 0; i < 1234; i++) csv.append("Product-").append(i).append(",1\n");
        var uploaded = sources.upload("Sales", new MockMultipartFile("file", "sales.csv", "text/csv",
                csv.toString().getBytes(StandardCharsets.UTF_8)), "UTF-8", "org", "user");
        var c = context(sources.save(request(uploaded), "org", "user"));
        var queries = new AiAgentDatasourceQueryService(sources);
        String sql = "SELECT \"product\" AS product, \"amount\" AS amount FROM table_1 ORDER BY product";
        assertThat(queries.execute(sql, c, 1000, 0).getRows()).hasSize(1000);
        c.setLimitRows(false);
        var all = queries.execute(sql, c, 0, 0);
        assertThat(all.getRows()).hasSize(1234);
        assertThat(all.getTotalRows()).isEqualTo(1234);
        var response = new AiAgentAnswerRenderer().render(all);
        var config = Map.<String,Object>of("type", "column", "axis", Map.of("x", Map.of("value", "product"), "y", List.of(Map.of("value", "amount"))));
        var chart = new AiAgentChartService().build(response.getResultTable(), config, false).getFirst();
        assertThat(chart.getCategories()).hasSize(1234);
        assertThat(chart.isPartial()).isFalse();
        for (boolean enabled : List.of(true, false)) {
            c.setLimitRows(enabled);
            var sum = queries.execute("SELECT SUM(\"amount\") AS total_amount FROM table_1", c, 1, 0);
            assertThat(((Number) sum.getRows().getFirst().get("total_amount")).longValue()).isEqualTo(1234);
            assertThat(queries.execute(sql + " LIMIT 5", c, enabled ? 1000 : 0, 0).getRows()).hasSize(5);
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "SELECT id AS id FROM hidden", "SELECT id AS id FROM other.products", "SELECT id AS id FROM sys.tables",
            "SELECT * FROM products", "DELETE FROM products", "SELECT id AS id INTO hacked FROM products",
            "SELECT id AS id FROM products; DROP TABLE products", "SELECT pg_read_file('/etc/passwd') AS data FROM products",
            "SELECT nextval('seq') AS data FROM products", "WITH products AS (SELECT id AS id FROM hidden) SELECT id AS id FROM products",
            "SELECT id AS id FROM products FOR UPDATE", "SELECT id AS id FROM products WHERE id IN (SELECT id FROM hidden)"
    }) void forbidsWritesUnselectedTablesAndUnsafeFunctions(String sql) {
        assertThatThrownBy(() -> AiAgentDatasourceQueryService.compile(sql, queryContext("POSTGRESQL"), 10, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sqlServerBracketsAndTopAreSupported() {
        var q = AiAgentDatasourceQueryService.compile("SELECT TOP 3 [amount] AS [amount] FROM [dbo].[products] ORDER BY [amount] DESC", queryContext("SQLSERVER"), 10, 0);
        assertThat(q.sql()).contains("TOP 3", "[dbo].[products]", "ORDER BY [amount] DESC");
    }

    @Test void rejectsConnectionParameterInjectionAndUnsafePaths() {
        Config c = database("MYSQL"); c.setHost("localhost;password=secret");
        assertThatThrownBy(() -> AiAgentDatasourceConnections.validate(c)).isInstanceOf(IllegalArgumentException.class);
        c.setHost("localhost"); c.setDatabase("test?allowMultiQueries=true");
        assertThatThrownBy(() -> AiAgentDatasourceConnections.validate(c)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connections.filePath("../../secret")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsInvalidAndUnevenCsv() {
        assertThatThrownBy(() -> imports.readCsv(new ByteArrayInputStream("a,b\n1".getBytes(StandardCharsets.UTF_8)), "UTF-8")).hasMessageContaining("不一致");
        assertThatThrownBy(() -> imports.readCsv(new ByteArrayInputStream(new byte[0]), "UTF-8")).hasMessageContaining("表头");
    }

    static Config database(String type) { Config c = new Config(); c.setName("DB"); c.setType(type); c.setHost("localhost"); c.setPort(3306); c.setDatabase("sales"); c.setUsername("reader"); return c; }
    static AiAgentContext queryContext(String type) {
        AiAgentContext c = new AiAgentContext(); c.setDatasourceId("source"); c.setDatasourceType(type);
        c.setDatasourceNamespace(type.equals("SQLSERVER") ? "dbo" : type.equals("ORACLE") ? "READER" : type.equals("FILE") ? "PUBLIC" : "public");
        Table t = new Table(); t.setName("products"); Column col = new Column(); col.setName("amount"); col.setType("DECIMAL"); t.setColumns(List.of(col));
        c.setDatasourceSchema(List.of(t)); return c;
    }
}
