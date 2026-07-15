package cn.cordys.crm.aiagent.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "ai_knowledge_document")
public class AiKnowledgeDocument extends BaseModel {
    @Schema(description = "组织 ID")
    private String organizationId;
    @Schema(description = "文档名称")
    private String name;
    @Schema(description = "原始文件名")
    private String originalName;
    @Schema(description = "文件类型")
    private String fileType;
    @Schema(description = "文件大小")
    private Long fileSize;
    @Schema(description = "文件存储路径")
    private String storagePath;
    @Schema(description = "知识分类")
    private String category;
    @Schema(description = "解析状态")
    private String parseStatus;
    @Schema(description = "解析失败原因")
    private String parseError;
    @Schema(description = "知识切片数量")
    private Integer chunkCount;
    @Schema(description = "是否启用")
    private Integer enabled;
    @Schema(description = "备注")
    private String remark;
}
