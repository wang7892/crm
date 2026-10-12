package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.response.AiAgentPageInfo;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/** Shared display policy, independent of the model or the queried business entity. */
public final class AiAgentResultWindow {
    public static final int DEFAULT_SIZE = 1000;
    public static final int MAX_SIZE = 1000;
    /** Zero means no transport row limit; an explicit business TOP/LIMIT still applies. */
    public static final int UNLIMITED = 0;
    private static final Pattern EXPLICIT_SIZE = Pattern.compile("(?:前|最多|只要|只看|仅看|展示|显示|返回|top\\s*)(\\d{1,9})\\s*(?:条|个|笔|项|组|名)?", Pattern.CASE_INSENSITIVE);

    private AiAgentResultWindow() { }

    public static boolean isPaged(AiAgentQueryPlan plan) {
        return "LIST".equals(plan.getQueryType()) || (plan.getGroupBy() != null && !plan.getGroupBy().isEmpty());
    }

    public static int requestedSize(String question) {
        return requestedSize(question, true);
    }

    public static int requestedSize(String question, boolean limitRows) {
        String text = StringUtils.defaultString(question).replaceAll("\\s+", "");
        var explicit = EXPLICIT_SIZE.matcher(text);
        if (explicit.find()) {
            return normalizeSize(Integer.parseInt(explicit.group(1)), limitRows);
        }
        return limitRows ? DEFAULT_SIZE : UNLIMITED;
    }

    public static int normalizeSize(Integer size, boolean limitRows) {
        if (size == null || size < 0) return limitRows ? DEFAULT_SIZE : UNLIMITED;
        if (size == UNLIMITED) return limitRows ? DEFAULT_SIZE : UNLIMITED;
        return limitRows ? Math.min(MAX_SIZE, size) : size;
    }

    public static AiAgentPageInfo page(Long total, long offset, int returned, int limit, String unit, boolean continuable) {
        AiAgentPageInfo page = new AiAgentPageInfo();
        page.setTotal(total);
        page.setOffset(offset);
        page.setReturned(returned);
        page.setUnit(unit);
        page.setRemaining(total == null ? null : Math.max(0, total - offset - returned));
        page.setHasMore(total == null ? limit > 0 && returned >= limit : total > offset + returned);
        page.setContinuable(continuable && page.isHasMore() && returned > 0);
        return page;
    }

    public static String describe(String subject, AiAgentPageInfo page) {
        String unit = page.getUnit();
        if (page.getTotal() == null) {
            return subject + "本次返回 " + page.getReturned() + " " + unit
                    + "；未能核实全部符合条件的总数和剩余数量，不能视为完整结果。"
                    + (page.isContinuable() ? "可点击“继续查看”获取后续记录。" : "");
        }
        if (page.getTotal() == 0) {
            return subject + "共 0 " + unit + "，未找到符合条件的记录。";
        }
        String shown = page.getOffset() == 0
                ? (page.isHasMore() ? "当前展示前 " : "已返回全部 ") + page.getReturned() + " " + unit
                : page.getReturned() == 0 ? "当前页没有记录"
                : "当前展示第 " + (page.getOffset() + 1) + "—" + (page.getOffset() + page.getReturned()) + " " + unit;
        return subject + "共 " + page.getTotal() + " " + unit + "，" + shown
                + (page.isHasMore() ? "，还有 " + page.getRemaining() + " " + unit
                + (page.isContinuable() ? "可继续查看。" : "未展示，请重新查询。") : "。");
    }

    /** Legacy composite queries cannot prove an exact total from capped input lists. */
    public static void markUnverifiedList(AiAgentChatResponse response) {
        if (response == null || response.getPageInfo() != null) return;
        String intent = StringUtils.defaultString(response.getIntent());
        if (!intent.endsWith("_LIST") && !StringUtils.equalsAny(intent,
                "CUSTOMER_ORDER_CONTRACT_YEAR_GAP", "ORDER_AMOUNT_INSIGHT", "ORDER_AMOUNT_SUMMARY",
                "ORDER_MATERIAL_SUMMARY", "LOW_COMMUNICATION_HIGH_VALUE_CUSTOMER", "SPECIALIST_COMMUNICATION_SUMMARY",
                "LARGEST_ORDER_LOOKUP")) return;
        int returned = response.getPoints().size();
        var page = page(null, 0, returned, Math.max(1, returned), "条", false);
        response.setPageInfo(page);
        response.setAnswer("本次返回 " + returned + " 条结果；全部符合条件的总数及剩余数量尚未核实。以下结论仅基于本次取得的数据：\n"
                + StringUtils.defaultString(response.getAnswer()).replace("以下展示全部结果", "以下展示本次结果"));
        response.getWarnings().add("本次结果可能来自受条数限制的明细或组合查询，不能据此确认完整名单、总体排名、总金额或不存在其他记录；请缩小范围或查询数据库聚合统计。");
    }
}
