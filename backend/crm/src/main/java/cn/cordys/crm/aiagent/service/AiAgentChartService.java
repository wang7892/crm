package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.response.AiAgentChart;
import cn.cordys.crm.aiagent.dto.response.AiAgentResultTable;
import org.springframework.stereotype.Service;
import java.util.*;

/** Interprets SQLBot's field mappings using only the actual query values. */
@Service
public class AiAgentChartService {
    public List<AiAgentChart> build(AiAgentResultTable table, Map<String, Object> config, boolean partial) {
        String type = Objects.toString(config.get("type"), "table");
        if (table == null || table.getRows().isEmpty() || !Set.of("column", "bar", "line", "pie").contains(type)) return List.of();
        Map<?, ?> axis = config.get("axis") instanceof Map<?, ?> m ? m : Map.of();
        String x = field(axis.get("pie".equals(type) ? "series" : "x"));
        String group = "pie".equals(type) ? "" : field(axis.get("series"));
        List<?> rawY = axis.get("y") instanceof List<?> list ? list : List.of(axis.get("y") == null ? Map.of() : axis.get("y"));
        List<String> ys = rawY.stream().map(this::field).filter(s -> !s.isBlank()).toList();
        Set<String> keys = new HashSet<>(table.getColumns().stream().map(AiAgentResultTable.Column::key).toList());
        if (!keys.contains(x) || ys.isEmpty() || !keys.containsAll(ys) || (!group.isEmpty() && !keys.contains(group))) return List.of();
        for (var row : table.getRows()) for (String y : ys)
            if (row.get(y) != null && (!(row.get(y) instanceof Number n) || !Double.isFinite(n.doubleValue()))) return List.of();
        var chart = new AiAgentChart(); chart.setType(type); chart.setTitle(Objects.toString(config.get("title"), "查询结果"));
        chart.setDimension(label(axis.get("pie".equals(type) ? "series" : "x"), x));
        chart.setPartial(partial); chart.setCoverage(partial ? "本次返回的数据" : "本次查询数据");
        var categories = new LinkedHashSet<String>();
        var values = new LinkedHashMap<String, Map<String, Double>>();
        for (var row : table.getRows()) {
            String category = Objects.toString(row.get(x), ""); categories.add(category);
            for (int i = 0; i < ys.size(); i++) {
                String name = group.isEmpty() ? label(rawY.get(i), ys.get(i)) : Objects.toString(row.get(group), "");
                var data = values.computeIfAbsent(name, k -> new LinkedHashMap<>());
                if (row.get(ys.get(i)) instanceof Number number) data.merge(category, number.doubleValue(), Double::sum);
                else data.putIfAbsent(category, null);
            }
        }
        chart.setCategories(new ArrayList<>(categories));
        chart.setSeries(values.entrySet().stream().map(e -> new AiAgentChart.Series(e.getKey(),
                chart.getCategories().stream().map(c -> e.getValue().get(c)).toList())).toList());
        return List.of(chart);
    }
    private String field(Object item) { return item instanceof Map<?, ?> m ? Objects.toString(m.get("value"), "") : ""; }
    private String label(Object item, String fallback) { return item instanceof Map<?, ?> m ? Objects.toString(m.get("name"), fallback) : fallback; }
}
