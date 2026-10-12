package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Compile model SELECTs over permission-scoped business relations, including nested queries. */
@Service
public class AiAgentTextSqlCompiler {
    private final AiAgentSemanticSchemaService schemaService;
    private final AiAgentQueryPermissionService permissionService;

    public AiAgentTextSqlCompiler(AiAgentSemanticSchemaService schemaService,
                                 AiAgentQueryPermissionService permissionService) {
        this.schemaService = schemaService;
        this.permissionService = permissionService;
    }

    public AiAgentSqlBuilder.BuiltQuery compile(String generatedSql, AiAgentContext context, int pageSize, long offset) {
        if (context == null) throw new IllegalArgumentException("Query permissions are required");
        var parsed = AiAgentSqlParser.parse(generatedSql, false);
        Map<String, AiAgentSemanticSchemaService.EntitySpec> entities = new LinkedHashMap<>();
        for (String table : parsed.tables()) {
            entities.put(table, schemaService.findEntity(table)
                    .orElseThrow(() -> new IllegalArgumentException("SQL table is not configured: " + table)));
        }
        if (parsed.ctes().stream().anyMatch(name -> schemaService.findEntity(name).isPresent())) {
            throw new IllegalArgumentException("CTEs cannot shadow business tables");
        }
        var primary = entities.values().iterator().next();
        if (entities.values().stream().anyMatch(entity -> entity.dataSourceKind() != primary.dataSourceKind())) {
            throw new IllegalArgumentException("CRM and external database tables require separate queries");
        }

        Select select = parsed.select();
        List<SelectItem<?>> items = outputItems(select);
        List<String> outputs = outputNames(items);
        List<String> ordering = outputOrdering(select, items, outputs);
        int limit = AiAgentResultWindow.normalizeSize(pageSize, context.isLimitRows());
        long safeOffset = Math.max(0, offset);
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, String> relations = new LinkedHashMap<>();
        for (var entry : entities.entrySet()) {
            relations.put(entry.getKey(), scopedRelation(entry.getValue(), context, params, relations.size()));
        }

        AtomicInteger replaced = new AtomicInteger();
        StringBuilder buffer = new StringBuilder();
        ExpressionDeParser expressions = new AiAgentSqlParser.TraversingExpressions() {
            @Override
            public void visit(StringValue value) {
                String name = "modelValue" + params.size();
                params.put(name, value.getNotExcapedValue());
                getBuffer().append(':').append(name);
            }
        };
        SelectDeParser queries = new SelectDeParser(expressions, buffer) {
            @Override
            public void visit(Table table) {
                String name = AiAgentSqlParser.identifier(table.getFullyQualifiedName());
                if (parsed.tableReferences().stream().noneMatch(reference -> reference == table)) {
                    super.visit(table);
                    return;
                }
                String relation = relations.get(name);
                if (relation == null) throw new IllegalArgumentException("Unconfigured SQL table: " + name);
                getBuffer().append('(').append(relation).append(") ");
                getBuffer().append(table.getAlias() == null ? "AS `" + name + "`" : table.getAlias().toString());
                replaced.incrementAndGet();
            }
        };
        expressions.setSelectVisitor(queries);
        expressions.setBuffer(buffer);
        select.accept(new StatementDeParser(expressions, queries, buffer));
        if (parsed.tableReferences().size() != replaced.get()) throw new IllegalArgumentException("SQL permission rewriting is incomplete");

        String secured = buffer.toString();
        String countSql = "SELECT COUNT(*) FROM (" + secured + ") agent_sql_count";
        String sql = "SELECT * FROM (" + secured + ") agent_sql_result ORDER BY "
                + String.join(", ", ordering);
        if (limit > 0) sql += " LIMIT " + limit + " OFFSET " + safeOffset;
        AiAgentQueryPlan display = new AiAgentQueryPlan();
        display.setIntent("CRM_DATABASE_QUERY");
        display.setEntity(primary.name());
        display.setQueryType("LIST");
        display.setSelectFields(outputs);
        display.setLimit(limit);
        display.setOffset(safeOffset);
        if (select instanceof PlainSelect plain && plain.getGroupBy() != null) {
            display.setQueryType("AGGREGATE");
            display.setGroupBy(plain.getGroupBy().getGroupByExpressionList().stream().map(Object::toString).toList());
        } else if (select instanceof PlainSelect && items.stream().anyMatch(item -> item.getExpression() instanceof Function function
                && Set.of("count", "sum", "avg", "min", "max").contains(function.getName().toLowerCase(java.util.Locale.ROOT)))) {
            display.setQueryType("AGGREGATE");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        entities.values().forEach(entity -> entity.fields().values().forEach(field -> labels.put(field.key(), field.label())));
        return new AiAgentSqlBuilder.BuiltQuery(sql, params, primary.dataSourceKind(), primary, display, labels, Map.of(), countSql);
    }

    private String scopedRelation(AiAgentSemanticSchemaService.EntitySpec entity, AiAgentContext context,
                                  Map<String, Object> parameters, int index) {
        Map<String, Object> local = new LinkedHashMap<>();
        permissionService.fillCommonParams(local, entity, context);
        var permission = permissionService.build(entity, context);
        List<String> fields = new ArrayList<>();
        for (var field : entity.fields().values()) {
            if (!field.selectable()) continue;
            String expression = field.expression();
            // Mask before model SQL can rename or transform a sensitive output column.
            if (field.sensitive()) {
                expression = switch (field.mask()) {
                    case "phone" -> "CASE WHEN " + expression + " IS NULL THEN NULL ELSE CONCAT(LEFT(" + expression
                            + ", 3), '****', RIGHT(" + expression + ", 4)) END";
                    case "email" -> "CASE WHEN " + expression + " IS NULL THEN NULL WHEN LOCATE('@', " + expression
                            + ") = 0 THEN '***' ELSE CONCAT(LEFT(" + expression + ", 1), '***', SUBSTRING(" + expression
                            + ", LOCATE('@', " + expression + "))) END";
                    default -> "CASE WHEN " + expression + " IS NULL THEN NULL ELSE '***' END";
                };
            }
            fields.add(expression + " AS `" + field.key() + "`");
        }
        String relation = "SELECT " + String.join(", ", fields) + " FROM " + entity.fromSql()
                + " " + permission.joinSql() + " WHERE 1 = 1" + permission.whereSql();
        ExpressionDeParser bindings = new AiAgentSqlParser.TraversingExpressions() {
            @Override
            public void visit(net.sf.jsqlparser.expression.JdbcNamedParameter parameter) {
                String original = parameter.getName();
                if (!local.containsKey(original)) throw new IllegalArgumentException("Missing permission binding: " + original);
                String name = "scope" + index + "_" + original;
                parameters.put(name, local.get(original));
                getBuffer().append(':').append(name);
            }
        };
        StringBuilder sql = new StringBuilder();
        SelectDeParser selections = new SelectDeParser(bindings, sql);
        bindings.setSelectVisitor(selections);
        bindings.setBuffer(sql);
        AiAgentSqlParser.parse(relation, true).select().accept(new StatementDeParser(bindings, selections, sql));
        return sql.toString();
    }

    static List<SelectItem<?>> outputItems(Select select) {
        if (select instanceof ParenthesedSelect nested) return outputItems(nested.getSelect());
        if (select instanceof PlainSelect plain) return plain.getSelectItems();
        if (select instanceof SetOperationList set) return outputItems(set.getSelects().getFirst());
        throw new IllegalArgumentException("Use SELECT columns, optionally with UNION or CTEs");
    }

    static List<String> outputNames(List<SelectItem<?>> items) {
        List<String> result = new ArrayList<>();
        for (var item : items) {
            String name = item.getAlias() != null ? AiAgentSqlParser.identifier(item.getAlias().getName())
                    : item.getExpression() instanceof Column column ? AiAgentSqlParser.identifier(column.getColumnName()) : null;
            if (name == null || !name.matches("[a-z_][a-z0-9_]*") || result.contains(name)) {
                throw new IllegalArgumentException("Each output needs a unique English column name; SELECT * is not supported");
            }
            result.add(name);
        }
        return result;
    }

    static List<String> outputOrdering(Select select, List<SelectItem<?>> items, List<String> outputs) {
        Map<Integer, String> order = new LinkedHashMap<>();
        if (select.getOrderByElements() != null) {
            for (var element : select.getOrderByElements()) {
                int position = -1;
                if (element.getExpression() instanceof LongValue value) {
                    position = Math.toIntExact(value.getValue()) - 1;
                } else {
                    String expression = AiAgentSqlParser.identifier(element.getExpression().toString());
                    for (int i = 0; i < items.size(); i++) {
                        if (expression.equals(outputs.get(i))
                                || expression.equals(AiAgentSqlParser.identifier(items.get(i).getExpression().toString()))) position = i;
                    }
                }
                if (position < 0 || position >= outputs.size()) {
                    throw new IllegalArgumentException("ORDER BY must reference an output column, alias or position");
                }
                order.put(position, element.isAsc() ? " ASC" : " DESC");
            }
        }
        for (int i = 0; i < outputs.size(); i++) order.putIfAbsent(i, " ASC");
        return order.entrySet().stream().map(entry -> (entry.getKey() + 1) + entry.getValue()).toList();
    }
}
