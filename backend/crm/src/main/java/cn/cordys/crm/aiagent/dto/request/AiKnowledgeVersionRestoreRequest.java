package cn.cordys.crm.aiagent.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AiKnowledgeVersionRestoreRequest {
    @NotNull
    @Min(0)
    private Integer expectedRevision;
}
