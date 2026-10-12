package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.query.AiAgentQueryMetric;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryResult;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentToolCallDTO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AiAgentAnswerRenderer {

    private static final ZoneId AGENT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public AiAgentChatResponse render(AiAgentQueryResult result) {
        AiAgentChatResponse response = new AiAgentChatResponse();
        response.setResultTable(resultTable(result));
        response.setIntent("CRM_DATABASE_QUERY");
        response.getEvidence().add(result.getEvidence());
        response.getWarnings().addAll(result.getWarnings());
        if (StringUtils.isNotBlank(result.getTotalWarning())) {
            response.getWarnings().add(result.getTotalWarning());
        }
        response.getTools().add(tool(result.getToolName(), result.getWarnings().isEmpty() ? "SUCCESS" : "SKIPPED",
                "执行受控数据库查询：" + result.getEntityLabel() + "；trace=" + result.getTraceId(), result.getDurationMs()));
        addQueryScope(response, result);

        if (!result.getWarnings().isEmpty() && result.getRows().isEmpty()) {
            response.setAnswer("这个问题需要查询数据库，但当前未能执行。");
            return response;
        }
        if (result.getRows().isEmpty() && !AiAgentResultWindow.isPaged(result.getPlan())) {
            response.setAnswer("当前权限范围内未找到符合条件的数据。");
            return response;
        }

        switch (StringUtils.defaultString(result.getPlan().getQueryType())) {
            case "COUNT" -> renderCount(response, result);
            case "AGGREGATE" -> renderAggregate(response, result);
            default -> renderList(response, result);
        }
        return response;
    }

    private void renderList(AiAgentChatResponse response, AiAgentQueryResult result) {
        int rowCount = result.getRows().size();
        renderPageSummary(response, result, "条");
        for (int index = 0; index < rowCount; index++) {
            response.getPoints().add(formatRow(result, result.getRows().get(index), offset(result) + index + 1));
        }
    }

    private long offset(AiAgentQueryResult result) {
        return result.getPlan().getOffset() == null ? 0 : result.getPlan().getOffset();
    }

    private void renderPageSummary(AiAgentChatResponse response, AiAgentQueryResult result, String unit) {
        String subject = "当前权限范围内符合条件的" + result.getEntityLabel();
        if ("customer".equals(result.getPlan().getEntity()) && "LIST".equals(result.getPlan().getQueryType())) {
            unit = "个";
            for (var filter : result.getPlan().getFilters()) {
                if ("owner_name".equals(filter.getField()) && "eq".equals(filter.getOperator())) {
                    subject = "当前权限范围内，“" + filter.getValue() + "”负责的客户";
                    break;
                }
            }
        } else if ("组".equals(unit)) {
            subject += "统计分组";
        }
        var page = AiAgentResultWindow.page(result.getTotalRows(), offset(result), result.getRows().size(),
                result.getPlan().getLimit() == null ? AiAgentResultWindow.DEFAULT_SIZE : result.getPlan().getLimit(), unit, true);
        response.setPageInfo(page);
        response.setAnswer(AiAgentResultWindow.describe(subject, page));
        if (result.getPlan().getTopN() != null) {
            String partitions = result.getPlan().getRankPartitions().stream().map(field -> labelFor(result, field))
                    .collect(java.util.stream.Collectors.joining("、"));
            String scope = partitions.isEmpty() ? "" : "按" + partitions + "分别排名，";
            String direction = "asc".equals(result.getPlan().getOrderBy().getFirst().getDirection()) ? "从低到高" : "从高到低";
            String metric = labelFor(result, result.getPlan().getOrderBy().getFirst().getField());
            response.setAnswer("当前权限范围内，" + scope + "按" + metric + direction + "取前 "
                    + result.getPlan().getTopN() + " 名。" + AiAgentResultWindow.describe("本次榜单", page));
        } else if (!result.getPlan().getRankPartitions().isEmpty()) {
            response.getWarnings().add("不同币种或单位分别排序，金额或数量不跨币种、单位比较。");
        }
    }

    private void addQueryScope(AiAgentChatResponse response, AiAgentQueryResult result) {
        if (result.getPlan() == null || result.getPlan().getFilters() == null) {
            return;
        }
        List<String> conditions = new ArrayList<>();
        for (var filter : result.getPlan().getFilters()) {
            String label = labelFor(result, filter.getField());
            String mask = result.getFieldMasks() == null ? null : result.getFieldMasks().get(filter.getField());
            String operator = StringUtils.defaultString(filter.getOperator());
            if (operator.equals("is_null") || operator.equals("not_null")) {
                conditions.add(label + (operator.equals("is_null") ? "未记录" : "有记录"));
            } else if ("CURRENT_TIME_WINDOW".equals(filter.getValue())) {
                conditions.add(label + "：" + StringUtils.defaultIfBlank(result.getTimeWindowLabel(), "当前时间范围"));
            } else {
                String relation = switch (operator) {
                    case "eq" -> "等于";
                    case "ne" -> "不等于";
                    case "like" -> "包含";
                    case "not_like" -> "不包含";
                    case "gt" -> "大于";
                    case "gte" -> "大于等于";
                    case "lt" -> "小于";
                    case "lte" -> "小于等于";
                    case "in" -> "属于";
                    case "not_in" -> "不属于";
                    default -> "范围";
                };
                conditions.add(label + relation + formatValue(filter.getValue(), mask));
            }
            if (filter.getField().equals("warehouse_actual_ship_date") && operator.equals("is_null")) {
                response.getWarnings().add("发货口径：未记录仓库实际发货日期，可能包含未发货、漏录或尚未同步的订单。");
            }
        }
        if (!conditions.isEmpty()) {
            response.getWarnings().add("筛选口径：" + String.join("；", conditions));
        }
        if (result.getPlan().getEntity().equals("sales_order") && result.getPlan().getQueryType().equals("LIST")) {
            response.getWarnings().add("以下为加工明细，同一订单号可能有多条记录。");
        }
    }

    private void renderCount(AiAgentChatResponse response, AiAgentQueryResult result) {
        if (result.getGroupByFields() == null || result.getGroupByFields().isEmpty()) {
            Object count = result.getRows().get(0).get("count_value");
            response.setAnswer("当前权限范围内符合条件的" + result.getEntityLabel() + "数量为 " + formatValue(count, null) + "。");
            return;
        }
        renderAggregate(response, result);
    }

    private void renderAggregate(AiAgentChatResponse response, AiAgentQueryResult result) {
        int rowCount = result.getRows().size();
        if (AiAgentResultWindow.isPaged(result.getPlan())) {
            renderPageSummary(response, result, "组");
        } else {
            response.setAnswer("当前权限范围内符合条件的" + result.getEntityLabel() + "统计结果如下。");
        }
        for (int index = 0; index < rowCount; index++) {
            response.getPoints().add(formatRow(result, result.getRows().get(index), offset(result) + index + 1));
        }
    }

    public Map<String, Object> analysisData(AiAgentQueryResult result, int maxRows) {
        return resultData(result, Math.max(1, Math.min(maxRows, 200)));
    }

    private cn.cordys.crm.aiagent.dto.response.AiAgentResultTable resultTable(AiAgentQueryResult result) {
        var table = new cn.cordys.crm.aiagent.dto.response.AiAgentResultTable();
        var keys = new java.util.LinkedHashSet<String>();
        if (result.getSelectedFields() != null) keys.addAll(result.getSelectedFields());
        result.getRows().forEach(r -> keys.addAll(r.keySet()));
        // Compiler metadata may include unused fields; retain only returned columns when rows exist.
        if (!result.getRows().isEmpty()) keys.retainAll(result.getRows().getFirst().keySet());
        for (String key : keys) {
            Object sample = result.getRows().stream().map(r -> r.get(key)).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            String mask = result.getFieldMasks() == null ? null : result.getFieldMasks().get(key);
            String type = StringUtils.isNotBlank(mask) ? "string" : sample instanceof Number ? "number"
                    : sample instanceof Boolean ? "boolean" : sample instanceof java.util.Date || sample instanceof java.time.temporal.TemporalAccessor ? "date" : "string";
            table.getColumns().add(new cn.cordys.crm.aiagent.dto.response.AiAgentResultTable.Column(key, labelFor(result, key), type));
        }
        for (var row : result.getRows()) {
            var copy = new java.util.LinkedHashMap<String, Object>();
            for (String key : keys) {
                Object value = row.get(key);
                String mask = result.getFieldMasks() == null ? null : result.getFieldMasks().get(key);
                if (value != null && (StringUtils.isNotBlank(mask) || value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor)) value = formatValue(value, mask);
                copy.put(key, value);
            }
            table.getRows().add(copy);
        }
        return table;
    }

    private Map<String, Object> resultData(AiAgentQueryResult result, int maxRows) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("entity", result.getEntityLabel());
        data.put("queryType", result.getPlan().getQueryType());
        data.put("timeWindow", result.getTimeWindowLabel());
        data.put("returnedRows", result.getRows().size());
        data.put("queryLimit", result.getPlan().getLimit());
        data.put("topN", result.getPlan().getTopN());
        data.put("rankPartitions", result.getPlan().getRankPartitions());
        data.put("totalRows", result.getTotalRows());
        data.put("offset", offset(result));
        data.put("metrics", result.getMetrics());
        data.put("groupBy", result.getGroupByFields());
        data.put("fieldLabels", result.getFieldLabels());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : result.getRows().stream().limit(maxRows).toList()) {
            Map<String, Object> safeRow = new java.util.LinkedHashMap<>();
            row.forEach((field, value) -> {
                String mask = result.getFieldMasks() == null ? null : result.getFieldMasks().get(field);
                // Preserve numbers for analysis, but apply masks before persisting or sending any value.
                safeRow.put(field, StringUtils.isBlank(mask) && (value == null || value instanceof Number || value instanceof Boolean)
                        ? value : StringUtils.abbreviate(formatValue(value, mask), 2000));
            });
            rows.add(safeRow);
        }
        data.put("rows", rows);
        data.put("includedRows", rows.size());
        data.put("truncated", rows.size() < result.getRows().size());
        boolean queryComplete = !AiAgentResultWindow.isPaged(result.getPlan())
                || (result.getTotalRows() != null && offset(result) == 0 && result.getTotalRows() == result.getRows().size());
        data.put("queryComplete", queryComplete);
        data.put("complete", queryComplete && rows.size() == result.getRows().size());
        data.put("remainingRows", result.getTotalRows() == null ? null
                : Math.max(0, result.getTotalRows() - offset(result) - result.getRows().size()));
        data.put("coverage", "LIST".equals(result.getPlan().getQueryType())
                ? "仅本次返回的明细，不代表全部符合条件的数据；整体统计必须另行聚合查询。"
                : "数据库统计结果；如果返回组数达到查询上限，不能视为全部分组。不得混加不同币种或单位。");
        if (result.getPlan().getTopN() != null) {
            data.put("coverage", "数据库已在全部符合条件的数据上完成汇总及排名；仅返回各币种/单位前 "
                    + result.getPlan().getTopN() + " 名。totalRows 是榜单条数，不是全部候选客户/分组数。");
        }
        return data;
    }

    private String formatRow(AiAgentQueryResult result, Map<String, Object> row, long index) {
        StringBuilder builder = new StringBuilder("第 " + index + " 条");
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String field = entry.getKey();
            String label = labelFor(result, field);
            String mask = result.getFieldMasks() == null ? null : result.getFieldMasks().get(field);
            builder.append("\n")
                    .append(label)
                    .append("：")
                    .append(formatValue(entry.getValue(), mask));
        }
        return builder.toString();
    }

    private String labelFor(AiAgentQueryResult result, String field) {
        if ("ranking_position".equalsIgnoreCase(field)) return "排名";
        if ("sales_amount".equalsIgnoreCase(field)) return "销售额合计";
        if (result.getFieldLabels() != null && result.getFieldLabels().containsKey(field)) {
            return result.getFieldLabels().get(field);
        }
        for (AiAgentQueryMetric metric : safeMetrics(result)) {
            if (StringUtils.equals(metric.getAlias(), field)) {
                return switch (StringUtils.defaultString(metric.getFunction())) {
                    case "sum" -> "合计";
                    case "avg" -> "平均值";
                    case "max" -> "最大值";
                    case "min" -> "最小值";
                    default -> "数量";
                };
            }
        }
        return field;
    }

    private List<AiAgentQueryMetric> safeMetrics(AiAgentQueryResult result) {
        return result.getMetrics() == null ? new ArrayList<>() : result.getMetrics();
    }

    private String formatValue(Object value, String mask) {
        if (value == null) {
            return "未填写";
        }
        if (value instanceof Timestamp timestamp) {
            return LocalDateTime.ofInstant(timestamp.toInstant(), AGENT_ZONE).format(DATE_TIME_FORMATTER);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(DATE_TIME_FORMATTER);
        }
        String text = String.valueOf(value);
        if (StringUtils.equals(mask, "email")) {
            return maskEmail(text);
        }
        if (StringUtils.equals(mask, "phone")) {
            return maskPhone(text);
        }
        if (StringUtils.isNotBlank(mask)) {
            return "***";
        }
        return StringUtils.defaultIfBlank(text, "未填写");
    }

    private String maskEmail(String email) {
        if (StringUtils.isBlank(email) || !email.contains("@")) {
            return "未填写";
        }
        int at = email.indexOf('@');
        String prefix = email.substring(0, at);
        if (prefix.length() <= 2) {
            return prefix.charAt(0) + "***" + email.substring(at);
        }
        return prefix.substring(0, 2) + "***" + email.substring(at);
    }

    private String maskPhone(String phone) {
        if (StringUtils.isBlank(phone)) {
            return "未填写";
        }
        if (phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private AiAgentToolCallDTO tool(String name, String status, String summary, Long durationMs) {
        AiAgentToolCallDTO tool = new AiAgentToolCallDTO();
        tool.setName(name);
        tool.setStatus(status);
        tool.setSummary(summary);
        tool.setDurationMs(durationMs);
        tool.setEvidenceId("ev_" + name);
        return tool;
    }
}
