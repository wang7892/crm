package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.query.*;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Ranking is a database operation over the full filtered population, never a sort of a display page. */
public final class AiAgentRankingPolicy {
    private static final Pattern TOP = Pattern.compile("(?:前|top\\s*)([0-9]{1,9}|[一二三四五六七八九十百]+)", Pattern.CASE_INSENSITIVE);
    private AiAgentRankingPolicy() { }

    public static boolean salesAmount(String question) {
        return StringUtils.containsAny(question, "销售额", "销售金额", "销售总额");
    }

    public static Integer topN(String question) {
        var matcher = TOP.matcher(StringUtils.defaultString(question).replaceAll("\\s+", ""));
        if (!matcher.find()) return null;
        String value = matcher.group(1);
        if (value.matches("[0-9]+")) return Integer.parseInt(value);
        int total = 0, digit = 0;
        for (char ch : value.toCharArray()) {
            if (ch == '十' || ch == '百') { total += Math.max(1, digit) * (ch == '十' ? 10 : 100); digit = 0; }
            else digit = "零一二三四五六七八九".indexOf(ch);
        }
        return total + digit;
    }

    public static boolean sorting(String question) {
        return StringUtils.containsAny(question, "排名", "排行", "排序", "排列", "从高到低", "从低到高", "降序", "升序", "最高", "最低", "最多", "最少")
                || topN(question) != null;
    }

    public static String direction(String question) {
        return StringUtils.containsAny(question, "从低到高", "从小到大", "升序", "最低", "最少") ? "asc" : "desc";
    }

    public static void apply(AiAgentQueryPlan plan, String question) {
        plan.setTopN(null);
        if ("LIST".equalsIgnoreCase(plan.getQueryType()) || !sorting(question)) return;
        if (plan.getGroupBy() == null || plan.getGroupBy().isEmpty()) {
            throw new IllegalArgumentException("排名需要明确分组对象，例如客户、销售人员或产品");
        }
        List<AiAgentQueryMetric> metrics = plan.getMetrics() == null ? List.of() : plan.getMetrics();
        List<AiAgentQueryMetric> candidates = metrics.stream().filter(m ->
                StringUtils.containsAny(question, "金额", "销售额") ? "amount".equals(m.getField())
                        : StringUtils.containsAny(question, "数量", "笔数", "次数")
                        ? List.of("quantity", "total_quantity").contains(m.getField()) || StringUtils.startsWith(m.getFunction(), "count")
                        : true).toList();
        if (candidates.size() != 1) throw new IllegalArgumentException("无法确定排名指标，请明确按金额、数量或哪个统计值排序");
        var metric = candidates.getFirst();
        String alias = StringUtils.defaultIfBlank(metric.getAlias(), metric.getFunction() + "_" + metric.getField());
        metric.setAlias(alias);
        var order = new AiAgentQueryOrder(); order.setField(alias); order.setDirection(direction(question));
        plan.setOrderBy(new ArrayList<>(List.of(order)));
        plan.setTopN(topN(question));
    }
}
