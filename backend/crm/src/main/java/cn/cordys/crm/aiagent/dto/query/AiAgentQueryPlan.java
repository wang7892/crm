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
    private Long offset;
    /** Ranking scope, independent of the transport page size. */
    private Integer topN;
    private List<String> rankPartitions = new ArrayList<>();
    private Boolean needClarification;
    private String clarificationQuestion;
}
