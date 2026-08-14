package cn.cordys.crm.aiagent.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiSemanticRuleReviewRequest {
    @NotBlank
    private String status;
    @Size(max = 500)
    private String comment;
    @NotNull
    private Long expectedUpdateTime;
}
