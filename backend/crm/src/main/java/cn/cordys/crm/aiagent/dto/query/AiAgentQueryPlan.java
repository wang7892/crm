package cn.cordys.crm.aiagent.dto.query;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentQueryPlan {
    private String intent;
    private String queryType;
    private String entity;
    private List<String> selectFields = new ArrayList<>();
    private List<AiAgentQueryFilter> filters = new ArrayList<>();
    private List<AiAgentQueryMetric> metrics = new ArrayList<>();
    private List<String> groupBy = new ArrayList<>();
    private List<AiAgentQueryOrder> orderBy = new ArrayList<>();
    private Integer limit;
    private Boolean needClarification;
    private String clarificationQuestion;
}
