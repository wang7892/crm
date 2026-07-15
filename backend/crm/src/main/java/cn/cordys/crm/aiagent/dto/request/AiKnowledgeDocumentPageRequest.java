package cn.cordys.crm.aiagent.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class AiKnowledgeDocumentPageRequest {
    @Schema(description = "当前页")
    @Min(1)
    private int current = 1;
    @Schema(description = "每页条数")
    @Min(1)
    @Max(100)
    private int pageSize = 20;
    @Schema(description = "关键词")
    private String keyword;
    @Schema(description = "文件类型")
    private String fileType;
    @Schema(description = "知识分类")
    private String category;
    @Schema(description = "解析状态")
    private String parseStatus;
    @Schema(description = "是否启用")
    private Integer enabled;
}
