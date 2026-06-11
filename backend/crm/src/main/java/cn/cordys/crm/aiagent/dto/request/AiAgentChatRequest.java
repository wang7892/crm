package cn.cordys.crm.aiagent.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class AiAgentChatRequest {
    private String sessionId;
    @NotBlank
    private String question;
    private Boolean stream = false;
    private String timeRange;
    private String dataScope;
    private String llmProvider;
    private Map<String, Object> context = new HashMap<>();
}
