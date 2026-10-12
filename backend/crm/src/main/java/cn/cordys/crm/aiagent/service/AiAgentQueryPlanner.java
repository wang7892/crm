package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.query.AiAgentQueryFilter;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryMetric;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryOrder;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AiAgentQueryPlanner {

    private static final int DEFAULT_LIMIT = AiAgentResultWindow.DEFAULT_SIZE;

    private final AiAgentSemanticSchemaService schemaService;

    public AiAgentQueryPlanner(AiAgentSemanticSchemaService schemaService) {
        this.schemaService = schemaService;
    }

    public PlannedQuery plan(AiAgentQueryPlan queryPlan) {
        if (queryPlan == null) {
            throw new IllegalArgumentException("查询计划为空");
        }
        String entityName = normalize(queryPlan.getEntity());
        AiAgentSemanticSchemaService.EntitySpec entity = schemaService.findEntity(entityName)
                .orElseThrow(() -> new IllegalArgumentException("不支持查询实体：" + StringUtils.defaultIfBlank(queryPlan.getEntity(), "未指定")));

        String queryType = StringUtils.upperCase(StringUtils.defaultIfBlank(queryPlan.getQueryType(), "LIST"), Locale.ROOT);
        if (!schemaService.isQueryTypeAllowed(queryType)) {
            throw new IllegalArgumentException("不支持查询类型：" + queryPlan.getQueryType());
        }

        AiAgentQueryPlan normalized = new AiAgentQueryPlan();
        normalized.setIntent("CRM_DATABASE_QUERY");
        normalized.setEntity(entity.name());
        normalized.setQueryType(queryType);
        normalized.setLimit(clampLimit(queryPlan.getLimit()));
        normalized.setOffset(Math.max(0L, queryPlan.getOffset() == null ? 0L : queryPlan.getOffset()));
        normalized.setNeedClarification(Boolean.TRUE.equals(queryPlan.getNeedClarification()));
        normalized.setClarificationQuestion(StringUtils.trimToNull(queryPlan.getClarificationQuestion()));
        normalized.setSelectFields(normalizeSelectFields(queryPlan, entity, queryType));
        normalized.setFilters(normalizeFilters(queryPlan.getFilters(), entity));
        normalized.setMetrics(normalizeMetrics(queryPlan.getMetrics(), entity, queryType));
        normalized.setGroupBy(normalizeGroupBy(queryPlan.getGroupBy(), entity));
        for (AiAgentQueryMetric metric : normalized.getMetrics()) {
            if (!List.of("sum", "avg").contains(metric.getFunction())) {
                continue;
            }
            String dimension = metric.getField().equals("amount") ? "currency"
                    : List.of("quantity", "total_quantity").contains(metric.getField()) ? "unit" : null;
            if (dimension != null && entity.findField(dimension).isPresent()
                    && !normalized.getGroupBy().contains(dimension)) {
                normalized.getGroupBy().add(dimension);
            }
        }
        normalized.setOrderBy(normalizeOrderBy(queryPlan.getOrderBy(), entity, normalized));
        if (queryPlan.getTopN() != null) {
            if (queryPlan.getTopN() < 1 || queryPlan.getTopN() > 10000 || normalized.getGroupBy().isEmpty()
                    || normalized.getOrderBy().isEmpty() || "LIST".equals(queryType)
                    || normalized.getMetrics().stream().noneMatch(metric -> metric.getAlias().equals(normalized.getOrderBy().getFirst().getField()))) {
                throw new IllegalArgumentException("前 N 名需要有效的分组及统计指标排序，N 范围为 1—10000");
            }
            normalized.setTopN(queryPlan.getTopN());
        }
        if (!"LIST".equals(queryType) && !normalized.getOrderBy().isEmpty()) {
            normalized.setRankPartitions(normalized.getGroupBy().stream()
                    .filter(field -> List.of("currency", "unit", "order_month").contains(field)).toList());
        }

        return new PlannedQuery(entity, normalized);
    }

    private List<String> normalizeSelectFields(AiAgentQueryPlan queryPlan, AiAgentSemanticSchemaService.EntitySpec entity,
                                               String queryType) {
        if (!StringUtils.equals(queryType, "LIST")) {
            return List.of();
        }
        List<String> requested = queryPlan.getSelectFields() == null || queryPlan.getSelectFields().isEmpty()
                ? entity.defaultFields()
                : queryPlan.getSelectFields();
        List<String> result = new ArrayList<>();
        for (String fieldName : requested) {
            AiAgentSemanticSchemaService.FieldSpec field = resolveField(entity, fieldName);
            if (!field.selectable()) {
                throw new IllegalArgumentException("字段不允许展示：" + fieldName);
            }
            if (!result.contains(field.key())) {
                result.add(field.key());
            }
        }
        if (result.isEmpty()) {
            result.addAll(entity.defaultFields());
        }
        return result;
    }

    private List<AiAgentQueryFilter> normalizeFilters(List<AiAgentQueryFilter> filters,
                                                      AiAgentSemanticSchemaService.EntitySpec entity) {
        List<AiAgentQueryFilter> result = new ArrayList<>();
        if (filters == null) {
            return result;
        }
        for (AiAgentQueryFilter filter : filters) {
            if (filter == null || StringUtils.isBlank(filter.getField())) {
                throw new IllegalArgumentException("过滤条件缺少字段，不能忽略用户条件");
            }
            AiAgentSemanticSchemaService.FieldSpec field = resolveField(entity, filter.getField());
            if (!field.filterable()) {
                throw new IllegalArgumentException("字段不允许过滤：" + filter.getField());
            }
            String operator = normalize(filter.getOperator());
            if (StringUtils.isBlank(operator)) {
                operator = "eq";
            }
            if (!schemaService.isOperatorAllowed(operator)) {
                throw new IllegalArgumentException("不支持过滤操作符：" + filter.getOperator());
            }
            if (!List.of("is_null", "not_null").contains(operator)
                    && (filter.getValue() == null || filter.getValue() instanceof String text && StringUtils.isBlank(text)
                    || filter.getValue() instanceof java.util.Collection<?> values && values.isEmpty())) {
                throw new IllegalArgumentException("过滤条件缺少有效值：" + field.key());
            }
            AiAgentQueryFilter normalized = new AiAgentQueryFilter();
            normalized.setField(field.key());
            normalized.setOperator(operator);
            normalized.setValue(filter.getValue());
            result.add(normalized);
        }
        return result;
    }

    private List<AiAgentQueryMetric> normalizeMetrics(List<AiAgentQueryMetric> metrics,
                                                      AiAgentSemanticSchemaService.EntitySpec entity,
                                                      String queryType) {
        List<AiAgentQueryMetric> result = new ArrayList<>();
        if (StringUtils.equals(queryType, "LIST")) {
            return result;
        }
        if (metrics == null || metrics.isEmpty()) {
            AiAgentQueryMetric metric = new AiAgentQueryMetric();
            metric.setFunction("count");
            metric.setField("id");
            metric.setAlias("count_value");
            result.add(metric);
            return result;
        }
        for (AiAgentQueryMetric metric : metrics) {
            if (metric == null) {
                continue;
            }
            String function = normalize(metric.getFunction());
            if (!List.of("count", "count_distinct", "sum", "avg", "max", "min").contains(function)) {
                throw new IllegalArgumentException("不支持聚合函数：" + metric.getFunction());
            }
            AiAgentSemanticSchemaService.FieldSpec field = resolveField(entity, StringUtils.defaultIfBlank(metric.getField(), "id"));
            if (!List.of("count", "count_distinct").contains(function) && !field.aggregatable()) {
                throw new IllegalArgumentException("字段不允许聚合：" + metric.getField());
            }
            if (List.of("sum", "avg").contains(function)
                    && field.valueKind() != AiAgentSemanticSchemaService.ValueKind.NUMBER) {
                throw new IllegalArgumentException("只有数值字段可以求和或求平均：" + field.key());
            }
            AiAgentQueryMetric normalized = new AiAgentQueryMetric();
            normalized.setFunction(function);
            normalized.setField(field.key());
            normalized.setAlias(sanitizeAlias(StringUtils.defaultIfBlank(metric.getAlias(), function + "_" + field.key())));
            result.add(normalized);
        }
        return result;
    }

    private List<String> normalizeGroupBy(List<String> groupBy, AiAgentSemanticSchemaService.EntitySpec entity) {
        List<String> result = new ArrayList<>();
        if (groupBy == null) {
            return result;
        }
        for (String fieldName : groupBy) {
            if (StringUtils.isBlank(fieldName)) {
                continue;
            }
            // The model commonly represents “按月汇总订单” as groupBy=order_time.
            // order_time is an epoch timestamp and must not be grouped by its raw
            // value; normalize that request to the controlled month bucket.
            String normalizedFieldName = StringUtils.equalsIgnoreCase(entity.name(), "sales_order")
                    && StringUtils.equalsIgnoreCase(fieldName, "order_time")
                    ? "order_month" : fieldName;
            AiAgentSemanticSchemaService.FieldSpec field = resolveField(entity, normalizedFieldName);
            if (!field.aggregatable()) {
                throw new IllegalArgumentException("字段不允许分组统计：" + fieldName);
            }
            if (!result.contains(field.key())) {
                result.add(field.key());
            }
        }
        return result;
    }

    private List<AiAgentQueryOrder> normalizeOrderBy(List<AiAgentQueryOrder> orderBy,
                                                     AiAgentSemanticSchemaService.EntitySpec entity,
                                                     AiAgentQueryPlan plan) {
        List<AiAgentQueryOrder> result = new ArrayList<>();
        if (orderBy == null) {
            return result;
        }
        for (AiAgentQueryOrder order : orderBy) {
            if (order == null || StringUtils.isBlank(order.getField())) {
                continue;
            }
            AiAgentQueryOrder normalized = new AiAgentQueryOrder();
            if (!"LIST".equals(plan.getQueryType())) {
                var matches = plan.getMetrics().stream().filter(metric -> metric.getAlias().equals(order.getField())
                        || metric.getField().equals(order.getField())).toList();
                if (matches.size() == 1) normalized.setField(matches.getFirst().getAlias());
                else if (matches.size() > 1) throw new IllegalArgumentException("排序指标不唯一，请使用明确的统计指标别名");
                else {
                    var field = resolveField(entity, order.getField());
                    if (!plan.getGroupBy().contains(field.key())) throw new IllegalArgumentException("汇总结果只能按统计指标或分组字段排序");
                    normalized.setField(field.key());
                }
            } else {
                AiAgentSemanticSchemaService.FieldSpec field = resolveField(entity, order.getField());
                if (!field.sortable()) throw new IllegalArgumentException("字段不允许排序：" + order.getField());
                normalized.setField(field.key());
            }
            normalized.setDirection(StringUtils.equalsIgnoreCase(order.getDirection(), "asc") ? "asc" : "desc");
            result.add(normalized);
        }
        return result;
    }

    private AiAgentSemanticSchemaService.FieldSpec resolveField(AiAgentSemanticSchemaService.EntitySpec entity, String fieldName) {
        return entity.resolveField(fieldName)
                .orElseThrow(() -> new IllegalArgumentException("不支持查询字段：" + fieldName));
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit < 0) {
            return DEFAULT_LIMIT;
        }
        return limit;
    }

    private String normalize(String value) {
        return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
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

    public record PlannedQuery(AiAgentSemanticSchemaService.EntitySpec entity, AiAgentQueryPlan queryPlan) {
    }
}
