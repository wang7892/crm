package cn.cordys.crm.aiagent.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiSemanticRuleVersionPageRequest {
    @NotBlank
    private String ruleId;
    @Min(1)
    private int current = 1;
    @Min(1)
    @Max(100)
    private int pageSize = 20;
}
