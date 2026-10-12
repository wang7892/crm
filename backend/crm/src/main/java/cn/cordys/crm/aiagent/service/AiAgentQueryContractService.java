package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryFilter;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryMetric;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Preserves request constraints across model repair and deterministic query routes. */
@Service
public class AiAgentQueryContractService {
    private final AiAgentSemanticSchemaService schema;

    public AiAgentQueryContractService(AiAgentSemanticSchemaService schema) {
        this.schema = schema;
    }

    public AiAgentQueryPlan prepare(ParsedAiAgentQuestion parsed, ParsedAiAgentQuestion original,
                                   AiAgentContext context) {
        if (context != null && StringUtils.isNotBlank(context.getTimeRangeError())) {
            throw new IllegalArgumentException(context.getTimeRangeError());
        }
        AiAgentQueryPlan plan = parsed.getQueryPlan();
        if (plan == null) {
            throw new IllegalArgumentException("没有完整查询计划，不能退化为无条件数据列表");
        }
        String question = StringUtils.defaultString(parsed.getRawQuestion());
        normalizeSalesAmount(plan, question);
        var entity = schema.findEntity(plan.getEntity())
                .orElseThrow(() -> new IllegalArgumentException("查询实体未配置"));
        plan.setFilters(new ArrayList<>(plan.getFilters() == null ? List.of() : plan.getFilters()));
        plan.setSelectFields(new ArrayList<>(plan.getSelectFields() == null || plan.getSelectFields().isEmpty()
                ? entity.defaultFields() : plan.getSelectFields()));
        ParsedAiAgentQuestion requirements = original == null ? parsed : original;
        boolean order = isOrderEntity(entity.name());

        String specialist = StringUtils.trimToNull(requirements.getSpecialistName());
        if (specialist != null) {
            if (!question.contains(specialist) && !contains(question, "我的", "我负责", "本人")) {
                throw new IllegalArgumentException("解析的人员姓名不在原问题中，请核实人员条件");
            }
            String field = order && question.contains("跟单员") && !contains(question, "联系专员", "理单员")
                    ? "merchandiser" : "owner_name";
            require(plan, entity, field, "eq", specialist);
        }
        if (StringUtils.isNotBlank(requirements.getCustomerName())) {
            require(plan, entity, entity.name().equals("customer") ? "name" : "customer_name",
                    "eq", requirements.getCustomerName().trim());
        }
        if (StringUtils.isNotBlank(requirements.getOrderNo())) {
            require(plan, entity, "order_no", "eq", requirements.getOrderNo().trim());
        }
        if (StringUtils.isNotBlank(requirements.getProductName())) {
            require(plan, entity, entity.name().equals("sales_order") ? "material_name" : "product_name",
                    "like", requirements.getProductName().trim());
        }
        if (order) {
            if (isUnshipped(question) || isShipped(question)) {
                for (var filter : plan.getFilters()) {
                    if (filter != null && List.of("status", "order_status", "approval_status").contains(filter.getField())
                            && filter.getValue() != null && !question.contains(String.valueOf(filter.getValue()))) {
                        throw new IllegalArgumentException("发货问题包含原问题未指定的流程状态，不能用猜测的状态值缩小结果");
                    }
                    if (filter != null && List.of("status", "order_status").contains(filter.getField())
                            && (isUnshipped(String.valueOf(filter.getValue())) || isShipped(String.valueOf(filter.getValue())))) {
                        throw new IllegalArgumentException("发货条件必须使用实际发货日期，不能把未发货/已发货当作流程状态值");
                    }
                }
            }
            if (question.matches(".*(?:没有|未曾|未|没)(?:签订过|签订|下过|下|订过|订)(?:任何)?(?:订单|合同).*")) {
                throw new IllegalArgumentException("没有订单/合同是按客户排除关联记录的问题，普通订单过滤不能表达完整含义");
            }
            if (contains(question, "部分发货", "全部发完", "全部发货", "发完")) {
                throw new IllegalArgumentException("部分/全部发货需要发货数量明细，实际发货日期无法证明发货完成比例");
            }
            boolean orderCount = !contains(question, "明细", "加工单", "生产单")
                    && (StringUtils.equalsIgnoreCase(plan.getQueryType(), "COUNT")
                    || contains(question, "订单数量", "订单数", "订单总数", "多少订单", "多少笔", "多少个订单"));
            if (orderCount) {
                if (StringUtils.equalsIgnoreCase(plan.getQueryType(), "LIST")) {
                    throw new IllegalArgumentException("问题要求订单数量统计，不能用普通订单列表代替统计计划");
                }
                List<AiAgentQueryMetric> metrics = new ArrayList<>(plan.getMetrics() == null ? List.of() : plan.getMetrics());
                if (metrics.isEmpty()) {
                    AiAgentQueryMetric metric = new AiAgentQueryMetric();
                    metric.setFunction("count_distinct");
                    metric.setField("order_no");
                    metric.setAlias("count_value");
                    metrics.add(metric);
                }
                for (var metric : metrics) {
                    if ("count".equalsIgnoreCase(metric.getFunction())) {
                        metric.setFunction("count_distinct");
                        metric.setField("order_no");
                    }
                }
                plan.setMetrics(metrics);
            }
            if (isUnshipped(question)) {
                require(plan, entity, "warehouse_actual_ship_date", "is_null", null);
            } else if (isShipped(question)) {
                require(plan, entity, "warehouse_actual_ship_date", "not_null", null);
            }
            // A date window alone is not a predicate: bind it to the business date explicitly.
            if (context != null && context.getTimeWindow() != null && hasTimeExpression(question)
                    && "全部".equals(context.getTimeWindow().label())) {
                throw new IllegalArgumentException("问题指定了时间，但未能解析出有效时间范围");
            }
            if (context != null && context.getTimeWindow() != null
                    && !"全部".equals(context.getTimeWindow().label()) && hasTimeExpression(question)) {
                String timeField = contains(question, "交期", "应交", "交货") ? "delivery_date"
                        : isShipped(question) && !contains(question, "下单", "创建") ? "warehouse_actual_ship_date"
                        : contains(question, "创建", "录入") ? "create_time"
                        : List.of("order_timeline", "contract_info").contains(entity.name()) ? "release_date" : "order_time";
                requireTime(plan, entity, timeField);
            }
        }
        AiAgentRankingPolicy.apply(plan, question);
        AiAgentQueryPlan normalized = new AiAgentQueryPlanner(schema).plan(plan).queryPlan();
        plan.setEntity(normalized.getEntity());
        plan.setQueryType(normalized.getQueryType());
        plan.setFilters(normalized.getFilters());
        plan.setSelectFields(normalized.getSelectFields());
        plan.setMetrics(normalized.getMetrics());
        plan.setGroupBy(normalized.getGroupBy());
        plan.setOrderBy(normalized.getOrderBy());
        plan.setLimit(normalized.getLimit());
        plan.setTopN(normalized.getTopN());
        plan.setRankPartitions(normalized.getRankPartitions());
        return plan;
    }

    private void normalizeSalesAmount(AiAgentQueryPlan plan, String question) {
        if (!AiAgentRankingPolicy.salesAmount(question)) return;
        if (!List.of("sales_order", "contract", "contract_info").contains(plan.getEntity())) {
            throw new IllegalArgumentException("销售额必须按外部合同金额及合同下单日期统计");
        }
        plan.setEntity("contract_info");
        plan.setQueryType("AGGREGATE");
        if (plan.getFilters() != null) plan.getFilters().forEach(filter -> filter.setField(salesField(filter.getField())));
        plan.setGroupBy(new ArrayList<>(plan.getGroupBy() == null ? List.of() : plan.getGroupBy().stream().map(this::salesField).distinct().toList()));
        if (question.contains("客户") && AiAgentRankingPolicy.sorting(question) && !plan.getGroupBy().contains("customer")) {
            plan.getGroupBy().add("customer");
        }
        if (AiAgentRankingPolicy.sorting(question) && !contains(question, "每月", "各月", "逐月", "按月")) {
            plan.getGroupBy().removeIf(field -> List.of("order_month", "order_time", "release_date").contains(field));
        }
        if (contains(question, "每月", "各月", "逐月", "按月")) {
            plan.getGroupBy().remove("release_date");
            if (!plan.getGroupBy().contains("order_month")) plan.getGroupBy().add("order_month");
        }
        var metric = new AiAgentQueryMetric(); metric.setFunction("sum"); metric.setField("amount"); metric.setAlias("sales_amount");
        plan.setMetrics(new ArrayList<>(List.of(metric)));
        if (plan.getOrderBy() != null) plan.getOrderBy().forEach(order -> {
            String field = order.getField();
            order.setField(List.of("customer_name", "owner_name", "customer", "manager", "currency").contains(field) ? salesField(field) : "sales_amount");
        });
    }

    private String salesField(String field) {
        return switch (StringUtils.defaultString(field)) {
            case "customer_name" -> "customer";
            case "owner_name" -> "manager";
            case "order_time", "start_time" -> "release_date";
            default -> field;
        };
    }

    private void requireTime(AiAgentQueryPlan plan, AiAgentSemanticSchemaService.EntitySpec entity, String field) {
        String key = resolve(entity, field);
        List<AiAgentQueryFilter> matching = plan.getFilters().stream()
                .filter(f -> f != null && key.equals(resolve(entity, f.getField())))
                .filter(f -> !List.of("is_null", "not_null").contains(StringUtils.defaultString(f.getOperator())))
                .toList();
        if (!matching.isEmpty()) {
            if (matching.size() != 1 || !"between".equalsIgnoreCase(matching.get(0).getOperator())) {
                throw new IllegalArgumentException("时间条件必须完整表达为 between/CURRENT_TIME_WINDOW：" + key);
            }
            // The server's request window, not a model-invented calendar date, defines relative time.
            matching.get(0).setValue("CURRENT_TIME_WINDOW");
            includeEvidenceField(plan, key);
        } else {
            require(plan, entity, key, "between", "CURRENT_TIME_WINDOW");
        }
    }

    private void require(AiAgentQueryPlan plan, AiAgentSemanticSchemaService.EntitySpec entity,
                         String field, String operator, Object value) {
        String key = resolve(entity, field);
        for (AiAgentQueryFilter existing : plan.getFilters()) {
            if (existing == null || !key.equals(resolve(entity, existing.getField()))) {
                continue;
            }
            String op = StringUtils.defaultIfBlank(existing.getOperator(), "eq").toLowerCase(java.util.Locale.ROOT);
            if (operator.equals("not_null") && !List.of("is_null", "not_null").contains(op)) {
                continue;
            }
            if (operator.equals("between") && op.equals("not_null")) {
                continue;
            }
            boolean sameValue = Objects.equals(value, existing.getValue())
                    || (value != null && existing.getValue() != null
                    && String.valueOf(value).equals(String.valueOf(existing.getValue()).trim()));
            if (sameValue && (operator.equals(op) || operator.equals("eq") && op.equals("like"))) {
                existing.setField(key);
                existing.setOperator(operator);
                existing.setValue(value);
                includeEvidenceField(plan, key);
                return;
            }
            throw new IllegalArgumentException("查询条件与问题要求冲突：" + field + " 必须 " + operator + " " + value);
        }
        AiAgentQueryFilter filter = new AiAgentQueryFilter();
        filter.setField(key);
        filter.setOperator(operator);
        filter.setValue(value);
        plan.getFilters().add(filter);
        includeEvidenceField(plan, key);
    }

    private void includeEvidenceField(AiAgentQueryPlan plan, String key) {
        if (StringUtils.equalsIgnoreCase(StringUtils.defaultIfBlank(plan.getQueryType(), "LIST"), "LIST")
                && !plan.getSelectFields().contains(key)) {
            plan.getSelectFields().add(key);
        }
    }

    private String resolve(AiAgentSemanticSchemaService.EntitySpec entity, String field) {
        return entity.resolveField(field).filter(AiAgentSemanticSchemaService.FieldSpec::filterable)
                .orElseThrow(() -> new IllegalArgumentException("实体 " + entity.name()
                        + " 无法表达必需条件 " + field + "；请选择包含全部所需字段的实体"))
                .key();
    }

    public static boolean isOrderEntity(String name) {
        return List.of("sales_order", "order_timeline", "contract_info").contains(name);
    }

    public static boolean isUnshipped(String question) {
        return contains(question, "未发", "没发货", "没有发货", "尚未发货", "未出库", "未记录发货");
    }

    public static boolean isShipped(String question) {
        return !isUnshipped(question) && contains(question, "已发", "已经发货", "已出库");
    }

    public static boolean hasTimeExpression(String question) {
        return AiAgentDateRangeParser.hasExplicitDate(question)
                || contains(question, "本月", "这个月", "上月", "上个月", "本季度", "今年", "去年", "最近", "本周", "今天", "昨天")
                || question.matches(".*(?:20\\d{2}年|近\\s*(?:\\d+|[一二三四五六七八九十]+)\\s*个?\\s*[天月]).*");
    }

    private static boolean contains(String question, String... words) {
        for (String word : words) {
            if (StringUtils.contains(question, word)) {
                return true;
            }
        }
        return false;
    }
}
