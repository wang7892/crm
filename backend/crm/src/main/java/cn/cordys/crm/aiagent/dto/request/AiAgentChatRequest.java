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
    @Size(max = 128)
    private String continueFromMessageId;
    @Size(max = 128)
    private String referenceMessageId;
    @jakarta.validation.constraints.Pattern(regexp = "QUERY|ANALYSIS|PREDICT|REGENERATE")
    private String action = "QUERY";
    @NotBlank
    private String question;
    private Boolean stream = false;
    private Boolean limitRows = true;
    private String timeRange;
    private String dataScope;
    private String llmProvider;
    @Size(max = 64)
    private String datasourceId;
    private Map<String, Object> context = new HashMap<>();
}
