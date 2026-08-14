package cn.cordys.crm.aiagent.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AiSemanticRuleVersionSwitchRequest {
    @NotBlank
    private String ruleId;
    @NotNull
    @Min(1)
    private Integer targetVersion;
    private Integer expectedActiveVersion;
}
