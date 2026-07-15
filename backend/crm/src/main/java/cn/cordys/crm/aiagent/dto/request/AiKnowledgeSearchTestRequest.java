package cn.cordys.crm.aiagent.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiKnowledgeSearchTestRequest {
    @Schema(description = "问题")
    @NotBlank
    private String question;
    @Schema(description = "返回数量")
    @Min(1)
    @Max(20)
    private int topK = 8;
}
