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

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

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
        normalized.setNeedClarification(Boolean.TRUE.equals(queryPlan.getNeedClarification()));
        normalized.setClarificationQuestion(StringUtils.trimToNull(queryPlan.getClarificationQuestion()));
        normalized.setSelectFields(normalizeSelectFields(queryPlan, entity, queryType));
        normalized.setFilters(normalizeFilters(queryPlan.getFilters(), entity));
        normalized.setMetrics(normalizeMetrics(queryPlan.getMetrics(), entity, queryType));
        normalized.setGroupBy(normalizeGroupBy(queryPlan.getGroupBy(), entity));
        normalized.setOrderBy(normalizeOrderBy(queryPlan.getOrderBy(), entity, queryType));

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
                continue;
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
            if (!List.of("count", "sum", "avg", "max", "min").contains(function)) {
                throw new IllegalArgumentException("不支持聚合函数：" + metric.getFunction());
            }
            AiAgentSemanticSchemaService.FieldSpec field = resolveField(entity, StringUtils.defaultIfBlank(metric.getField(), "id"));
            if (!StringUtils.equals(function, "count") && !field.aggregatable()) {
                throw new IllegalArgumentException("字段不允许聚合：" + metric.getField());
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
            AiAgentSemanticSchemaService.FieldSpec field = resolveField(entity, fieldName);
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
                                                     String queryType) {
        List<AiAgentQueryOrder> result = new ArrayList<>();
        if (orderBy == null || !StringUtils.equals(queryType, "LIST")) {
            return result;
        }
        for (AiAgentQueryOrder order : orderBy) {
            if (order == null || StringUtils.isBlank(order.getField())) {
                continue;
            }
            AiAgentSemanticSchemaService.FieldSpec field = resolveField(entity, order.getField());
            if (!field.sortable()) {
                throw new IllegalArgumentException("字段不允许排序：" + order.getField());
            }
            AiAgentQueryOrder normalized = new AiAgentQueryOrder();
            normalized.setField(field.key());
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
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
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
