package cn.cordys.crm.aiagent.service;
import cn.cordys.crm.aiagent.dto.response.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AiAgentChartServiceTest {
    private final AiAgentChartService service = new AiAgentChartService();
    private AiAgentResultTable table(List<Map<String,Object>> rows) {
        var table = new AiAgentResultTable(); table.setRows(rows);
        table.setColumns(List.of(new AiAgentResultTable.Column("month", "月份", "string"),
                new AiAgentResultTable.Column("amount", "金额", "number"),
                new AiAgentResultTable.Column("product", "产品", "string")));
        return table;
    }
    private Map<String,Object> config(String type) {
        Object y = Map.of("name", "金额", "value", "amount");
        var axis = new LinkedHashMap<String,Object>();
        axis.put("x", Map.of("name", "月份", "value", "month")); axis.put("y", "pie".equals(type) ? y : List.of(y));
        if ("pie".equals(type)) axis.put("series", Map.of("name", "月份", "value", "month"));
        return Map.of("type", type, "title", "销售趋势", "axis", axis);
    }
    @ParameterizedTest @ValueSource(strings = {"column", "bar", "line", "pie"})
    void honorsModelMappingAndUsesRealValues(String type) {
        var data = table(List.of(Map.of("month","2026-01","amount",100),Map.of("month","2026-02","amount",200)));
        var chart = service.build(data,config(type),false).getFirst();
        assertThat(chart.getType()).isEqualTo(type);
        assertThat(chart.getCategories()).containsExactly("2026-01","2026-02");
        assertThat(chart.getSeries().getFirst().values()).containsExactly(100d,200d);
    }
    @Test void classificationProducesSeparateSeriesWithMissingValues() {
        var data = table(List.of(Map.of("month","2026-01","amount",10,"product","A"),
                Map.of("month","2026-01","amount",20,"product","B"),Map.of("month","2026-02","amount",30,"product","A")));
        var axis = new LinkedHashMap<>((Map<String,Object>)config("line").get("axis"));
        axis.put("series",Map.of("name","产品","value","product"));
        var chart = service.build(data,Map.of("type","line","axis",axis),true).getFirst();
        assertThat(chart.getSeries()).hasSize(2);
        assertThat(chart.getSeries().get(0).values()).containsExactly(10d,30d);
        assertThat(chart.getSeries().get(1).values()).containsExactly(20d,null);
        assertThat(chart.isPartial()).isTrue();
    }
    @Test void invalidMappingsDoNotManufactureValues() {
        var data = table(List.of(Map.of("month","一月","amount",10)));
        assertThat(service.build(data,Map.of("type","line","axis",Map.of("x",Map.of("value","secret"),"y",List.of(Map.of("value","amount")))),false)).isEmpty();
        assertThat(service.build(data,config("table"),false)).isEmpty();
        assertThat(service.build(table(List.of(Map.of("month","一月","amount","10"))),config("line"),false)).isEmpty();
    }
    @Test void keepsAllReturnedCategoriesRatherThanFixedPreview() {
        var rows = new ArrayList<Map<String,Object>>();
        for (int i=0;i<150;i++) rows.add(Map.of("month","period-"+i,"amount",i));
        assertThat(service.build(table(rows),config("line"),false).getFirst().getCategories()).hasSize(150);
    }
}
