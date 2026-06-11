package cn.cordys.crm.aiagent.domain;

import cn.cordys.common.domain.BaseModel;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "ai_agent_tool_call_log")
public class AiAgentToolCallLog extends BaseModel {
    private String messageId;
    private String toolName;
    private String inputJson;
    private String outputJson;
    private String status;
    private String errorMessage;
    private Long durationMs;
}
