package cn.cordys.crm.aiagent.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiKnowledgeChunkPageRequest {
    @Schema(description = "当前页")
    @Min(1)
    private int current = 1;
    @Schema(description = "每页条数")
    @Min(1)
    @Max(100)
    private int pageSize = 20;
    @Schema(description = "文档 ID")
    @NotBlank
    private String documentId;
}
