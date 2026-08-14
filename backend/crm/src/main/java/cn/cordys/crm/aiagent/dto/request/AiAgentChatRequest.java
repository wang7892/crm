package cn.cordys.crm.aiagent.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class AiAgentChatRequest {
    @Size(max = 128)
    private String requestId;
    private String sessionId;
    @NotBlank
    private String question;
    private Boolean stream = false;
    private String timeRange;
    private String dataScope;
    private String llmProvider;
    private Map<String, Object> context = new HashMap<>();
}
