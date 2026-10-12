package cn.cordys.crm.aiagent.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiKnowledgeDocumentEditRequest {
    @NotBlank
    @Size(max = 255)
    private String name;
    @Size(max = 1024)
    private String remark;
    @NotBlank
    @Size(max = 2_000_000)
    private String content;
    @NotNull
    @Min(0)
    private Integer expectedRevision;
}
