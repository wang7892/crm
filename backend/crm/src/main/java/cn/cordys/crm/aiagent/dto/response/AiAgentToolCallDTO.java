package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

@Data
public class AiAgentToolCallDTO {
    private String name;
    private String status;
    private String evidenceId;
    private String summary;
    private Long durationMs;
}
