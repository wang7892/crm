package cn.cordys.crm.integration.mls.service;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extracts an unambiguous country/region from customer text.  The matcher is
 * deliberately deterministic: aliases are checked longest-first and a row
 * containing different regions is left unchanged instead of guessing.
 */
final class CustomerRegionExtractor {

    private static final Map<String, String> ALIASES = aliases();
    private static final List<String> TERMS = ALIASES.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    private CustomerRegionExtractor() {
    }

    static String extract(String... values) {
        Set<String> matches = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (StringUtils.isBlank(value)) {
                    continue;
                }
                String normalized = value.toLowerCase(Locale.ROOT);
                List<int[]> acceptedSpans = new ArrayList<>();
                for (String term : TERMS) {
                    String needle = term.toLowerCase(Locale.ROOT);
                    int from = 0;
                    while ((from = normalized.indexOf(needle, from)) >= 0) {
                        int matchStart = from;
                        int end = matchStart + needle.length();
                        if (isTokenBoundary(normalized, matchStart, end, term)
                                && acceptedSpans.stream().noneMatch(span -> matchStart < span[1] && end > span[0])) {
                            matches.add(ALIASES.get(term));
                            acceptedSpans.add(new int[]{matchStart, end});
                            break;
                        }
                        from++;
                    }
                }
            }
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    private static boolean isTokenBoundary(String value, int start, int end, String term) {
        // Chinese names are naturally delimited by characters.  For English
        // aliases require non-letter boundaries to avoid matching "India" in
        // a longer word such as "Indian".
        if (!isAsciiLetters(term)) {
            return true;
        }
        boolean left = start == 0 || !Character.isLetter(value.charAt(start - 1));
        boolean right = end == value.length() || !Character.isLetter(value.charAt(end));
        return left && right;
    }

    private static boolean isAsciiLetters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 127 || !Character.isLetter(c)) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private static Map<String, String> aliases() {
        Map<String, String> map = new LinkedHashMap<>();
        add(map, "中国", "中国", "China");
        add(map, "印度", "印度", "India");
        add(map, "印度尼西亚", "印度尼西亚", "Indonesia");
        add(map, "巴基斯坦", "巴基斯坦", "Pakistan");
        add(map, "孟加拉国", "孟加拉国", "Bangladesh");
        add(map, "斯里兰卡", "斯里兰卡", "Sri Lanka");
        add(map, "尼泊尔", "尼泊尔", "Nepal");
        add(map, "埃及", "埃及", "Egypt");
        add(map, "沙特阿拉伯", "沙特阿拉伯", "Saudi Arabia", "Saudi");
        add(map, "沙特", "沙特阿拉伯");
        add(map, "阿联酋", "阿联酋", "United Arab Emirates");
        add(map, "卡塔尔", "卡塔尔", "Qatar");
        add(map, "科威特", "科威特", "Kuwait");
        add(map, "阿曼", "阿曼", "Oman");
        add(map, "也门", "也门", "Yemen");
        add(map, "伊朗", "伊朗", "Iran");
        add(map, "伊拉克", "伊拉克", "Iraq");
        add(map, "以色列", "以色列", "Israel");
        add(map, "约旦", "约旦", "Jordan");
        add(map, "黎巴嫩", "黎巴嫩", "Lebanon");
        add(map, "叙利亚", "叙利亚", "Syria");
        add(map, "土耳其", "土耳其", "Turkey");
        add(map, "亚美尼亚", "亚美尼亚", "Armenia");
        add(map, "阿塞拜疆", "阿塞拜疆", "Azerbaijan");
        add(map, "格鲁吉亚", "格鲁吉亚", "Georgia");
        add(map, "阿尔及利亚", "阿尔及利亚", "Algeria");
        add(map, "摩洛哥", "摩洛哥", "Morocco");
        add(map, "突尼斯", "突尼斯", "Tunisia");
        add(map, "利比亚", "利比亚", "Libya");
        add(map, "南非", "南非", "South Africa");
        add(map, "尼日利亚", "尼日利亚", "Nigeria");
        add(map, "肯尼亚", "肯尼亚", "Kenya");
        add(map, "坦桑尼亚", "坦桑尼亚", "Tanzania");
        add(map, "巴西", "巴西", "Brazil");
        add(map, "阿根廷", "阿根廷", "Argentina");
        add(map, "墨西哥", "墨西哥", "Mexico");
        add(map, "美国", "美国", "United States", "USA");
        add(map, "加拿大", "加拿大", "Canada");
        add(map, "英国", "英国", "United Kingdom", "Britain");
        add(map, "法国", "法国", "France");
        add(map, "德国", "德国", "Germany");
        add(map, "意大利", "意大利", "Italy");
        add(map, "西班牙", "西班牙", "Spain");
        add(map, "葡萄牙", "葡萄牙", "Portugal");
        add(map, "俄罗斯", "俄罗斯", "Russia");
        add(map, "乌克兰", "乌克兰", "Ukraine");
        add(map, "澳大利亚", "澳大利亚", "Australia");
        add(map, "新西兰", "新西兰", "New Zealand");
        add(map, "日本", "日本", "Japan");
        add(map, "韩国", "韩国", "South Korea", "Korea");
        add(map, "越南", "越南", "Vietnam");
        add(map, "泰国", "泰国", "Thailand");
        add(map, "马来西亚", "马来西亚", "Malaysia");
        add(map, "新加坡", "新加坡", "Singapore");
        add(map, "菲律宾", "菲律宾", "Philippines");
        add(map, "中东", "中东", "Middle East");
        return Map.copyOf(map);
    }

    private static void add(Map<String, String> map, String canonical, String... terms) {
        for (String term : terms) {
            map.put(term, canonical);
        }
    }
}
