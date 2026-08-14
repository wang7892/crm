package cn.cordys.crm.aiagent.dto.response;

import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import lombok.Data;

@Data
public class AiSemanticRuleMatchResponse {
    private String ruleId;
    private Integer version;
    private String term;
    private String matchedBy;
    private double score;
    private AiAgentSemanticRuleMatch.Target target;
    private String documentId;
    private String chunkId;
    private Integer pageNo;
    private String sectionPath;
}
