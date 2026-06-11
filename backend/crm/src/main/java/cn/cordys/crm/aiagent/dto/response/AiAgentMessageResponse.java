package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

@Data
public class AiAgentMessageResponse {
    private String id;
    private String role;
    private String content;
    private String intent;
    private String evidenceJson;
    private Long createTime;
}
