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
        response.setIntent("CRM_DATABASE_QUERY");
        response.getEvidence().add(result.getEvidence());
        response.getWarnings().addAll(result.getWarnings());
        response.getTools().add(tool(result.getToolName(), result.getWarnings().isEmpty() ? "SUCCESS" : "SKIPPED",
                "执行受控数据库查询：" + result.getEntityLabel(), result.getDurationMs()));

        if (!result.getWarnings().isEmpty() && result.getRows().isEmpty()) {
            response.setAnswer("这个问题需要查询数据库，但当前未能执行。");
            return response;
        }
        if (result.getRows().isEmpty()) {
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
        int rowCount = Math.min(result.getRows().size(), 10);
        response.setAnswer("当前权限范围内找到 " + result.getRows().size() + " 条"
                + result.getEntityLabel() + "数据，以下展示前 " + rowCount + " 条。");
        for (int index = 0; index < rowCount; index++) {
            response.getPoints().add(formatRow(result, result.getRows().get(index), index + 1));
        }
        if (result.getRows().size() > rowCount) {
            response.getWarnings().add("当前最多展示前 " + rowCount + " 条明细。");
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
        int rowCount = Math.min(result.getRows().size(), 10);
        response.setAnswer("当前权限范围内已完成 " + result.getEntityLabel() + "统计，以下展示前 " + rowCount + " 组。");
        for (int index = 0; index < rowCount; index++) {
            response.getPoints().add(formatRow(result, result.getRows().get(index), index + 1));
        }
        if (result.getRows().size() > rowCount) {
            response.getWarnings().add("当前最多展示前 " + rowCount + " 组统计结果。");
        }
    }

    private String formatRow(AiAgentQueryResult result, Map<String, Object> row, int index) {
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
        if (value instanceof Number number && number.longValue() > 100000000000L
                && number.longValue() < 4102444800000L) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), AGENT_ZONE).format(DATE_TIME_FORMATTER);
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
