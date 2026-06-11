package cn.cordys.crm.aiagent.domain;

import cn.cordys.common.domain.BaseModel;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "ai_agent_message")
public class AiAgentMessage extends BaseModel {
    private String sessionId;
    private String role;
    private String content;
    private String intent;
    private String evidenceJson;
}
