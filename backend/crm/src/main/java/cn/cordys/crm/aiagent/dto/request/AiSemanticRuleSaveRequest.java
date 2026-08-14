package cn.cordys.crm.aiagent.dto.request;

import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiSemanticRuleSaveRequest {
    @NotNull
    private Long expectedUpdateTime;
    @NotBlank
    @Size(max = 64)
    private String canonicalTerm;
    @Size(max = 20)
    private List<@Size(min = 1, max = 64) String> aliases = new ArrayList<>();
    @Size(max = 500)
    private String definition;
    @Valid
    @NotNull
    private EditableMapping mapping;
    @Size(max = 10)
    private List<AiAgentSemanticRule.ForbiddenMapping> forbiddenMappings = new ArrayList<>();
    @Size(max = 5)
    private List<AiAgentSemanticRule.Example> examples = new ArrayList<>();
    @Min(0)
    @Max(1000)
    private Integer priority;
    private Long effectiveFrom;
    private Long effectiveTo;

    @Data
    public static class EditableMapping {
        @NotBlank
        private String entity;
        @NotBlank
        private String field;
    }
}
