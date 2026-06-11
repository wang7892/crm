package cn.cordys.crm.aiagent.dto.internal;

import lombok.Data;

@Data
public class AiAgentUnansweredQuestionRow {
    private String id;
    private String question;
    private String missReason;
    private Long occurCount;
    private Long lastAskTime;
}
