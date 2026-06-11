package cn.cordys.crm.aiagent.dto;

import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import lombok.Data;

@Data
public class ParsedAiAgentQuestion {
    private String rawQuestion;
    private String normalizedQuestion;
    private String intent;
    private String customerName;
    private String specialistName;
    private String keyword;
    private String productName;
    private String orderNo;
    private String timeRange;
    private Boolean activeOnly;
    private boolean sqlRequired;
    private String candidateSql;
    private boolean needClarification;
    private String clarificationQuestion;
    private AiAgentQueryPlan queryPlan;
    private String source = "RULE";
    private double confidence;
}
