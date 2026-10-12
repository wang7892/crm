package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Query values remain separate from their labels and display formatting. */
@Data
public class AiAgentResultTable {
    private List<Column> columns = new ArrayList<>();
    private List<Map<String, Object>> rows = new ArrayList<>();
    public record Column(String key, String label, String type) { }
}
