package cn.cordys.crm.aiagent.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiAgentFeedbackRequest {
    @NotBlank
    private String messageId;
    @NotBlank
    private String rating;
    private String comment;
    private String correctAnswer;
}
