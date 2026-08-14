package cn.cordys.crm.aiagent.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiSemanticRulePageRequest {
    @NotBlank
    private String documentId;
    @Min(1)
    private int current = 1;
    @Min(1)
    @Max(100)
    private int pageSize = 20;
    private String keyword;
    private String reviewStatus;
}
