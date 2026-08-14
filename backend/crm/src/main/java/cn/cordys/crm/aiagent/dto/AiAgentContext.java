package cn.cordys.crm.aiagent.dto;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeSearchTestResponse;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentContext {
    private String userId;
    private String organizationId;
    private DeptDataPermissionDTO dataPermission;
    private DeptDataPermissionDTO customerDataPermission;
    private DeptDataPermissionDTO contractDataPermission;
    private DeptDataPermissionDTO orderDataPermission;
    private String dataScope;
    private String llmProvider;
    private AiAgentTimeWindow timeWindow;
    private boolean llmParseAttempted;
    private ParsedAiAgentQuestion llmParsedQuestion;
    private AiKnowledgeSearchTestResponse knowledgeSearch;
    private List<AiAgentSemanticRuleMatch> semanticRuleMatches = new ArrayList<>();
    private boolean semanticRuleConflict;
    private String semanticRuleFallbackReason;
}
