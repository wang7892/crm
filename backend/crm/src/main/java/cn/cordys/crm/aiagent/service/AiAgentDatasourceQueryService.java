package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.Table;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryResult;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.*;

@Service
public class AiAgentDatasourceQueryService {
    private final AiAgentDatasourceService sources;
    public AiAgentDatasourceQueryService(AiAgentDatasourceService sources) { this.sources = sources; }

    record Query(String sql, String countSql, AiAgentQueryPlan plan, Map<String, String> labels) { }

    static Query compile(String sql, AiAgentContext context, int size, long offset) {
        var parsed = AiAgentSqlParser.parse(sql, false);
        String type = context.getDatasourceType();
        String namespace = context.getDatasourceNamespace();
        Map<String, Table> allowed = new LinkedHashMap<>();
        context.getDatasourceSchema().forEach(t -> allowed.put(AiAgentSqlParser.identifier(t.getName()), t));
        if (parsed.ctes().stream().anyMatch(allowed::containsKey)) throw new IllegalArgumentException("CTE 不能覆盖选中表名");
        for (var ref : parsed.tableReferences()) {
            String unqualified = AiAgentSqlParser.identifier(ref.getName());
            String qualified = AiAgentSqlParser.identifier(ref.getFullyQualifiedName());
            Table table = allowed.get(unqualified);
            if (table == null || !(qualified.equals(unqualified)
                    || qualified.equals(AiAgentSqlParser.identifier(namespace) + "." + unqualified))) {
                throw new IllegalArgumentException("SQL 只能访问当前数据源选中的表：" + unqualified);
            }
            if (ref.getFullyQualifiedName().contains("@") || ref.getPivot() != null || ref.getUnPivot() != null
                    || ref.getIndexHint() != null || ref.getSqlServerHints() != null || ref.getSampleClause() != null) {
                throw new IllegalArgumentException("不允许数据库链接或表扩展");
            }
            // Use metadata names and the configured namespace, never a model-supplied database/server prefix.
            ref.setName(quote(table.getName(), type));
            ref.setSchemaName(quote(namespace, type));
        }
        var select = parsed.select();
        var items = AiAgentTextSqlCompiler.outputItems(select);
        var outputs = AiAgentTextSqlCompiler.outputNames(items);
        var ordering = AiAgentTextSqlCompiler.outputOrdering(select, items, outputs);
        int limit = AiAgentResultWindow.normalizeSize(size, context.isLimitRows());
        long start = Math.max(0, offset);
        String prefix = "";
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            prefix = "WITH " + select.getWithItemsList().stream().map(Object::toString).collect(java.util.stream.Collectors.joining(", ")) + " ";
            select.setWithItemsList(null);
        }
        if ("SQLSERVER".equals(type) && select.getLimit() != null) {
            throw new IllegalArgumentException("SQL Server 前 N 行使用 TOP，不要使用 LIMIT");
        }
        // SQL Server disallows an unbounded ORDER BY inside a derived table.
        if ("SQLSERVER".equals(type) && select instanceof PlainSelect p
                && p.getTop() == null && p.getOffset() == null && p.getFetch() == null) select.setOrderByElements(null);
        String body = select.toString();
        String count = prefix + "SELECT COUNT(*) FROM (" + body + ") agent_sql_count";
        String page = prefix + "SELECT * FROM (" + body + ") agent_sql_result ORDER BY " + String.join(", ", ordering);
        if (limit > 0) page += Set.of("ORACLE", "SQLSERVER").contains(type)
                ? " OFFSET " + start + " ROWS FETCH NEXT " + limit + " ROWS ONLY"
                : " LIMIT " + limit + " OFFSET " + start;
        AiAgentQueryPlan plan = new AiAgentQueryPlan(); plan.setIntent("CRM_DATABASE_QUERY");
        plan.setEntity("datasource"); plan.setQueryType("LIST"); plan.setSelectFields(outputs); plan.setLimit(limit); plan.setOffset(start);
        Map<String, String> labels = new LinkedHashMap<>();
        context.getDatasourceSchema().forEach(t -> t.getColumns().forEach(c -> labels.put(AiAgentSqlParser.identifier(c.getName()),
                c.getComment() == null || c.getComment().isBlank() ? c.getName() : c.getComment())));
        return new Query(page, count, plan, labels);
    }

    private static String quote(String identifier, String type) {
        if (identifier == null || identifier.isBlank()) throw new IllegalArgumentException("数据源 Schema 不可用");
        return "MYSQL".equals(type) ? "`" + identifier.replace("`", "``") + "`"
                : "SQLSERVER".equals(type) ? "[" + identifier.replace("]", "]]") + "]"
                : "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public AiAgentQueryResult execute(String sql, AiAgentContext context, int size, long offset) {
        return execute(sql, context, size, offset, true);
    }

    public AiAgentQueryResult execute(String sql, AiAgentContext context, int size, long offset, boolean countTotal) {
        Query query = compile(sql, context, size, offset);
        long started = System.currentTimeMillis();
        AiAgentQueryResult result = new AiAgentQueryResult(); result.setPlan(query.plan());
        result.setEntityLabel(context.getDatasourceName()); result.setEvidence("datasource:" + context.getDatasourceId());
        result.setSelectedFields(query.plan().getSelectFields()); result.setFieldLabels(query.labels()); result.setFieldMasks(Map.of());
        try (Connection db = sources.openForQuery(context)) {
            if (!"ORACLE".equals(context.getDatasourceType())) db.setReadOnly(true);
            db.setAutoCommit(false);
            try {
                if ("ORACLE".equals(context.getDatasourceType())) {
                    try (Statement s = db.createStatement()) { s.execute("SET TRANSACTION READ ONLY"); }
                }
                if (countTotal) try (PreparedStatement count = db.prepareStatement(query.countSql())) {
                    count.setQueryTimeout(30); count.setMaxRows(1);
                    try (ResultSet rows = count.executeQuery()) { if (rows.next()) result.setTotalRows(rows.getLong(1)); }
                }
                try (PreparedStatement page = db.prepareStatement(query.sql())) {
                    page.setQueryTimeout(30); page.setMaxRows(query.plan().getLimit());
                    try (ResultSet rows = page.executeQuery()) {
                        ResultSetMetaData meta = rows.getMetaData();
                        int bytes = 0;
                        while (rows.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= meta.getColumnCount(); i++) {
                                Object value = rows.getObject(i);
                                if (value instanceof Blob || value instanceof Clob || value instanceof byte[]) {
                                    throw new IllegalArgumentException("不支持查询二进制或大文本字段，请选择其他列");
                                }
                                if (value != null && !(value instanceof Number) && !(value instanceof Boolean)) {
                                    String text = value.toString();
                                    if (text.length() > 8000) throw new IllegalArgumentException("单个查询字段过长，请缩小字段内容");
                                    value = text;
                                }
                                bytes += value == null ? 4 : value.toString().length();
                                if (bytes > 500_000) throw new IllegalArgumentException("查询结果过大，请缩小查询范围");
                                row.put(query.plan().getSelectFields().get(i - 1), value);
                            }
                            result.getRows().add(row);
                        }
                    }
                }
            } finally { db.rollback(); }
        } catch (SQLException e) {
            throw new IllegalArgumentException("当前数据源 SQL 执行失败，请检查 SQL 方言、字段、只读权限或超时。"
                    + (e.getSQLState() != null && e.getSQLState().matches("[A-Z0-9]{5}") ? " SQLState=" + e.getSQLState() : ""));
        }
        result.setDurationMs(System.currentTimeMillis() - started);
        return result;
    }
}
