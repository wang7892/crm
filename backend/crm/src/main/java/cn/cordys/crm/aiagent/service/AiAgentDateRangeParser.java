package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.AiAgentTimeWindow;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;

/** Explicit ranges take precedence over single-month and relative-date fallbacks. */
public final class AiAgentDateRangeParser {
    private static final String MONTH = "(1[0-2]|0?[1-9]|十一|十二|十|[一二三四五六七八九])";
    private static final String SEP = "\\s*(?:至|到|[~～—–－-])\\s*";
    private static final Pattern DAYS = Pattern.compile("(20\\d{2})[年/-](\\d{1,2})[月/-](\\d{1,2})日?"
            + SEP + "(?:(20\\d{2})[年/-])?(\\d{1,2})[月/-](\\d{1,2})日?");
    private static final Pattern MONTHS = Pattern.compile("(?:(20\\d{2})年)?" + MONTH + "月?份?"
            + SEP + "(?:(20\\d{2})年)?" + MONTH + "月(?:份)?");
    private static final Pattern ISO_MONTHS = Pattern.compile("(20\\d{2})-(\\d{1,2})" + SEP + "(20\\d{2})-(\\d{1,2})(?![-\\d])");
    private static final Pattern DAY = Pattern.compile("(20\\d{2})[年/-](\\d{1,2})[月/-](\\d{1,2})日?");
    private static final Pattern MONTH_RANGE_LIKE = Pattern.compile("[0-9一二三四五六七八九十]+月?份?" + SEP
            + "(?:20\\d{2}年)?[0-9一二三四五六七八九十]+月");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private AiAgentDateRangeParser() { }

    public static AiAgentTimeWindow resolve(String question, LocalDate today) {
        String text = StringUtils.defaultString(question).replaceAll("\\s+", "");
        var days = DAYS.matcher(text);
        if (days.find()) {
            int year = Integer.parseInt(days.group(1));
            LocalDate start = LocalDate.of(year, Integer.parseInt(days.group(2)), Integer.parseInt(days.group(3)));
            LocalDate end = LocalDate.of(days.group(4) == null ? year : Integer.parseInt(days.group(4)),
                    Integer.parseInt(days.group(5)), Integer.parseInt(days.group(6)));
            if (days.find()) throw new IllegalArgumentException("包含多个日期范围，请分别查询或明确比较期间");
            return window(start, end.plusDays(1));
        }
        var months = MONTHS.matcher(text);
        if (months.find()) {
            int year = months.group(1) == null ? today.getYear() : Integer.parseInt(months.group(1));
            LocalDate start = LocalDate.of(year, month(months.group(2)), 1);
            LocalDate end = LocalDate.of(months.group(3) == null ? year : Integer.parseInt(months.group(3)), month(months.group(4)), 1);
            if (months.find()) throw new IllegalArgumentException("包含多个月份范围，请分别查询或明确比较期间");
            return window(start, end.plusMonths(1));
        }
        var iso = ISO_MONTHS.matcher(text);
        if (iso.find()) {
            return window(LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), 1),
                    LocalDate.of(Integer.parseInt(iso.group(3)), Integer.parseInt(iso.group(4)), 1).plusMonths(1));
        }
        if (MONTH_RANGE_LIKE.matcher(text).find()) throw new IllegalArgumentException("月份范围无效，请使用 1—12 月并写明跨年年份");
        var day = DAY.matcher(text);
        if (day.find()) {
            LocalDate date = LocalDate.of(Integer.parseInt(day.group(1)), Integer.parseInt(day.group(2)), Integer.parseInt(day.group(3)));
            if (day.find()) throw new IllegalArgumentException("日期范围连接方式不明确，请明确开始和结束日期");
            return window(date, date.plusDays(1));
        }
        return null;
    }

    public static boolean hasExplicitDate(String question) {
        String text = StringUtils.defaultString(question).replaceAll("\\s+", "");
        return MONTHS.matcher(text).find() || ISO_MONTHS.matcher(text).find() || DAY.matcher(text).find();
    }

    private static int month(String text) {
        return switch (text) {
            case "一" -> 1; case "二" -> 2; case "三" -> 3; case "四" -> 4; case "五" -> 5; case "六" -> 6;
            case "七" -> 7; case "八" -> 8; case "九" -> 9; case "十" -> 10; case "十一" -> 11; case "十二" -> 12;
            default -> Integer.parseInt(text);
        };
    }

    private static AiAgentTimeWindow window(LocalDate start, LocalDate end) {
        if (!start.isBefore(end)) throw new IllegalArgumentException("开始日期必须早于结束日期；跨年范围请写明年份");
        return new AiAgentTimeWindow(start.atStartOfDay(ZONE).toInstant().toEpochMilli(),
                end.atStartOfDay(ZONE).toInstant().toEpochMilli(), start + "至" + end.minusDays(1));
    }
}
