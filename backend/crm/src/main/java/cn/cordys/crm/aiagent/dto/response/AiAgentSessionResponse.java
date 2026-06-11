package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

@Data
public class AiAgentSessionResponse {
    private String id;
    private String title;
    private String agentName;
    private Long updateTime;
}
