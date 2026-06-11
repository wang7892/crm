package cn.cordys.crm.aiagent.domain;

import cn.cordys.common.domain.BaseModel;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "ai_agent_session")
public class AiAgentSession extends BaseModel {
    private String organizationId;
    private String userId;
    private String title;
}
