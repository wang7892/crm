package cn.cordys.crm.aiagent.domain;

import cn.cordys.common.domain.BaseModel;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "ai_agent_feedback")
public class AiAgentFeedback extends BaseModel {
    private String messageId;
    private String userId;
    private String rating;
    private String comment;
    private String correctAnswer;
}
