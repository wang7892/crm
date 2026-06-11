package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryFilter;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryMetric;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryOrder;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AiAgentSqlBuilder {

    private static final ZoneId AGENT_ZONE = ZoneId.of("Asia/Shanghai");

    private final AiAgentQueryPermissionService permissionService;

    public AiAgentSqlBuilder(AiAgentQueryPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public BuiltQuery build(AiAgentQueryPlanner.PlannedQuery plannedQuery, AiAgentContext context) {
        AiAgentSemanticSchemaService.EntitySpec entity = plannedQuery.entity();
        AiAgentQueryPlan plan = plannedQuery.queryPlan();
        Map<String, Object> params = new LinkedHashMap<>();
        permissionService.fillCommonParams(params, entity, context);
        AiAgentQueryPermissionService.PermissionSql permissionSql = permissionService.build(entity, context);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(selectClause(plan, entity));
        sql.append("\nFROM ").append(entity.fromSql()).append("\n");
        if (StringUtils.isNotBlank(permissionSql.joinSql())) {
            sql.append(permissionSql.joinSql()).append("\n");
        }
        sql.append("WHERE 1 = 1");
        sql.append(permissionSql.whereSql());
        appendFilters(sql, params, plan, entity, context);
        appendGroupBy(sql, plan, entity);
        appendOrderBy(sql, plan, entity);
        appendLimit(sql, plan);

        return new BuiltQuery(
                sql.toString(),
                params,
                entity.dataSourceKind(),
                entity,
                plan,
                fieldLabels(entity),
                fieldMasks(entity)
        );
    }

    private String selectClause(AiAgentQueryPlan plan, AiAgentSemanticSchemaService.EntitySpec entity) {
        if (StringUtils.equals(plan.getQueryType(), "LIST")) {
            List<String> selects = new ArrayList<>();
            for (String fieldName : plan.getSelectFields()) {
                AiAgentSemanticSchemaService.FieldSpec field = entity.findField(fieldName).orElseThrow();
                selects.add(field.expression() + " AS " + field.key());
            }
            return String.join(", ", selects);
        }

        List<String> selects = new ArrayList<>();
        for (String groupFieldName : plan.getGroupBy()) {
            AiAgentSemanticSchemaService.FieldSpec field = entity.findField(groupFieldName).orElseThrow();
            selects.add(field.expression() + " AS " + field.key());
        }
        for (AiAgentQueryMetric metric : plan.getMetrics()) {
            AiAgentSemanticSchemaService.FieldSpec field = entity.findField(metric.getField()).orElseThrow();
            String alias = sanitizeAlias(metric.getAlias());
            String function = StringUtils.lowerCase(metric.getFunction(), Locale.ROOT);
            if (StringUtils.equals(function, "count")) {
                selects.add("COUNT(1) AS " + alias);
            } else {
                selects.add(function.toUpperCase(Locale.ROOT) + "(" + field.expression() + ") AS " + alias);
            }
        }
        if (selects.isEmpty()) {
            selects.add("COUNT(1) AS count_value");
        }
        return String.join(", ", selects);
    }

    private void appendFilters(StringBuilder sql, Map<String, Object> params, AiAgentQueryPlan plan,
                               AiAgentSemanticSchemaService.EntitySpec entity, AiAgentContext context) {
        int index = 0;
        for (AiAgentQueryFilter filter : plan.getFilters()) {
            AiAgentSemanticSchemaService.FieldSpec field = entity.findField(filter.getField()).orElseThrow();
            String operator = StringUtils.lowerCase(filter.getOperator(), Locale.ROOT);
            if (appendSpecialFilter(sql, params, field, operator, filter.getValue(), index)) {
                index++;
                continue;
            }
            String expression = field.expression();
            String paramName = "filter" + index++;
            switch (operator) {
                case "eq" -> {
                    sql.append(" AND ").append(expression).append(" = :").append(paramName);
                    params.put(paramName, coerceValue(field, filter.getValue(), context, false));
                }
                case "ne" -> {
                    sql.append(" AND ").append(expression).append(" <> :").append(paramName);
                    params.put(paramName, coerceValue(field, filter.getValue(), context, false));
                }
                case "like" -> {
                    sql.append(" AND ").append(expression).append(" LIKE :").append(paramName);
                    params.put(paramName, "%" + StringUtils.defaultString(String.valueOf(filter.getValue())).trim() + "%");
                }
                case "not_like" -> {
                    sql.append(" AND ").append(expression).append(" NOT LIKE :").append(paramName);
                    params.put(paramName, "%" + StringUtils.defaultString(String.valueOf(filter.getValue())).trim() + "%");
                }
                case "in" -> {
                    sql.append(" AND ").append(expression).append(" IN (:").append(paramName).append(")");
                    params.put(paramName, coerceCollection(field, filter.getValue(), context));
                }
                case "not_in" -> {
                    sql.append(" AND ").append(expression).append(" NOT IN (:").append(paramName).append(")");
                    params.put(paramName, coerceCollection(field, filter.getValue(), context));
                }
                case "gt", "gte", "lt", "lte" -> {
                    String symbol = switch (operator) {
                        case "gt" -> ">";
                        case "gte" -> ">=";
                        case "lt" -> "<";
                        default -> "<=";
                    };
                    sql.append(" AND ").append(expression).append(" ").append(symbol).append(" :").append(paramName);
                    params.put(paramName, coerceValue(field, filter.getValue(), context, false));
                }
                case "between" -> appendBetween(sql, params, field, filter.getValue(), context, paramName);
                case "is_null" -> sql.append(" AND ").append(expression).append(" IS NULL");
                case "not_null" -> sql.append(" AND ").append(expression).append(" IS NOT NULL");
                default -> throw new IllegalArgumentException("不支持过滤操作符：" + operator);
            }
        }
    }

    private boolean appendSpecialFilter(StringBuilder sql, Map<String, Object> params,
                                        AiAgentSemanticSchemaService.FieldSpec field, String operator,
                                        Object value, int index) {
        if (!StringUtils.equals(field.key(), "name_keyword")) {
            return false;
        }
        if (!StringUtils.equals(operator, "like") && !StringUtils.equals(operator, "not_like")) {
            throw new IllegalArgumentException("客户名称关键词只支持 like/not_like 查询");
        }
        String paramName = "filter" + index;
        String predicate = "(c.name LIKE :" + paramName + " OR c.full_name LIKE :" + paramName + ")";
        if (StringUtils.equals(operator, "not_like")) {
            predicate = "NOT " + predicate;
        }
        sql.append(" AND ").append(predicate);
        params.put(paramName, "%" + StringUtils.defaultString(String.valueOf(value)).trim() + "%");
        return true;
    }

    private void appendBetween(StringBuilder sql, Map<String, Object> params, AiAgentSemanticSchemaService.FieldSpec field,
                               Object rawValue, AiAgentContext context, String paramName) {
        Object start;
        Object end;
        if (isCurrentTimeWindow(rawValue)) {
            start = currentWindowValue(field, context, true);
            end = currentWindowValue(field, context, false);
        } else if (rawValue instanceof Collection<?> collection && collection.size() >= 2) {
            List<?> values = new ArrayList<>(collection);
            start = coerceValue(field, values.get(0), context, true);
            end = coerceValue(field, values.get(1), context, false);
        } else {
            throw new IllegalArgumentException("between 过滤需要两个值或 CURRENT_TIME_WINDOW");
        }
        sql.append(" AND ").append(field.expression()).append(" BETWEEN :").append(paramName).append("Start")
                .append(" AND :").append(paramName).append("End");
        params.put(paramName + "Start", start);
        params.put(paramName + "End", end);
    }

    private void appendGroupBy(StringBuilder sql, AiAgentQueryPlan plan, AiAgentSemanticSchemaService.EntitySpec entity) {
        if (plan.getGroupBy() == null || plan.getGroupBy().isEmpty()) {
            return;
        }
        List<String> groups = new ArrayList<>();
        for (String fieldName : plan.getGroupBy()) {
            groups.add(entity.findField(fieldName).orElseThrow().expression());
        }
        sql.append(" GROUP BY ").append(String.join(", ", groups));
    }

    private void appendOrderBy(StringBuilder sql, AiAgentQueryPlan plan, AiAgentSemanticSchemaService.EntitySpec entity) {
        if (plan.getOrderBy() == null || plan.getOrderBy().isEmpty()) {
            return;
        }
        List<String> orders = new ArrayList<>();
        for (AiAgentQueryOrder order : plan.getOrderBy()) {
            AiAgentSemanticSchemaService.FieldSpec field = entity.findField(order.getField()).orElseThrow();
            orders.add(field.expression() + " " + (StringUtils.equals(order.getDirection(), "asc") ? "ASC" : "DESC"));
        }
        if (!orders.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", orders));
        }
    }

    private void appendLimit(StringBuilder sql, AiAgentQueryPlan plan) {
        if (StringUtils.equals(plan.getQueryType(), "COUNT") && (plan.getGroupBy() == null || plan.getGroupBy().isEmpty())) {
            return;
        }
        sql.append(" LIMIT ").append(plan.getLimit());
    }

    private List<Object> coerceCollection(AiAgentSemanticSchemaService.FieldSpec field, Object value, AiAgentContext context) {
        List<Object> values = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                values.add(coerceValue(field, item, context, false));
            }
        } else {
            values.add(coerceValue(field, value, context, false));
        }
        return values;
    }

    private Object coerceValue(AiAgentSemanticSchemaService.FieldSpec field, Object value,
                               AiAgentContext context, boolean startOfDay) {
        if (isCurrentTimeWindow(value)) {
            return currentWindowValue(field, context, startOfDay);
        }
        if (value == null) {
            return null;
        }
        return switch (field.valueKind()) {
            case NUMBER -> value;
            case EPOCH_MILLIS -> toEpochMillis(value, startOfDay);
            case SQL_TIMESTAMP -> toTimestamp(value, startOfDay);
            case TEXT -> String.valueOf(value).trim();
        };
    }

    private Object currentWindowValue(AiAgentSemanticSchemaService.FieldSpec field, AiAgentContext context, boolean start) {
        long millis = start ? context.getTimeWindow().startTime() : context.getTimeWindow().endTime();
        if (field.valueKind() == AiAgentSemanticSchemaService.ValueKind.SQL_TIMESTAMP) {
            return new Timestamp(millis);
        }
        return millis;
    }

    private long toEpochMillis(Object value, boolean startOfDay) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = StringUtils.defaultString(String.valueOf(value)).trim();
        try {
            if (text.length() >= 10 && text.charAt(4) == '-') {
                LocalDate date = LocalDate.parse(text.substring(0, 10));
                if (!startOfDay) {
                    date = date.plusDays(1);
                }
                return date.atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli();
            }
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private Timestamp toTimestamp(Object value, boolean startOfDay) {
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value instanceof Number number) {
            return new Timestamp(number.longValue());
        }
        String text = StringUtils.defaultString(String.valueOf(value)).trim();
        try {
            if (text.length() >= 10 && text.charAt(4) == '-') {
                LocalDate date = LocalDate.parse(text.substring(0, 10));
                if (!startOfDay) {
                    date = date.plusDays(1);
                }
                return Timestamp.from(date.atStartOfDay(AGENT_ZONE).toInstant());
            }
            return Timestamp.from(Instant.parse(text));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isCurrentTimeWindow(Object value) {
        return StringUtils.equalsIgnoreCase(StringUtils.defaultString(value == null ? null : String.valueOf(value)).trim(),
                "CURRENT_TIME_WINDOW");
    }

    private Map<String, String> fieldLabels(AiAgentSemanticSchemaService.EntitySpec entity) {
        Map<String, String> labels = new LinkedHashMap<>();
        entity.fields().forEach((key, field) -> labels.put(key, field.label()));
        labels.put("count_value", "数量");
        return labels;
    }

    private Map<String, String> fieldMasks(AiAgentSemanticSchemaService.EntitySpec entity) {
        Map<String, String> masks = new LinkedHashMap<>();
        entity.fields().forEach((key, field) -> {
            if (field.sensitive()) {
                masks.put(key, field.mask());
            }
        });
        return masks;
    }

    private String sanitizeAlias(String alias) {
        String value = StringUtils.defaultIfBlank(alias, "metric_value")
                .replaceAll("[^A-Za-z0-9_]", "_");
        if (StringUtils.isBlank(value)) {
            return "metric_value";
        }
        if (Character.isDigit(value.charAt(0))) {
            return "m_" + value;
        }
        return value;
    }

    public record BuiltQuery(
            String sql,
            Map<String, Object> params,
            AiAgentSemanticSchemaService.DataSourceKind dataSourceKind,
            AiAgentSemanticSchemaService.EntitySpec entity,
            AiAgentQueryPlan plan,
            Map<String, String> fieldLabels,
            Map<String, String> fieldMasks
    ) {
    }
}
