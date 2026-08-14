package cn.cordys.crm.aiagent.dto.response;

import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRule;
import lombok.Data;

@Data
public class AiSemanticRuleResponse {
    private String chunkId;
    private String documentId;
    private String documentName;
    private Integer chunkIndex;
    private Integer enabled;
    private Long createTime;
    private Long updateTime;
    private AiAgentSemanticRule rule;
}
