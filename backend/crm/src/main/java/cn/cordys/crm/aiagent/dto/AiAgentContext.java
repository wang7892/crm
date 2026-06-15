package cn.cordys.crm.aiagent.dto;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import lombok.Data;

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
}
