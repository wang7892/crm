package cn.cordys.crm.aiagent.dto.query;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class AiAgentQueryResult {
    private AiAgentQueryPlan plan;
    private String entityLabel;
    private String evidence;
    private List<String> selectedFields = new ArrayList<>();
    private List<String> groupByFields = new ArrayList<>();
    private List<AiAgentQueryMetric> metrics = new ArrayList<>();
    private Map<String, String> fieldLabels;
    private Map<String, String> fieldMasks;
    private List<Map<String, Object>> rows = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private long durationMs;
    private String toolName;
}
