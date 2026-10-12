package cn.cordys.crm.aiagent.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "ai_knowledge_document_version")
public class AiKnowledgeDocumentVersion extends BaseModel {
    @Schema(description = "组织 ID")
    private String organizationId;
    @Schema(description = "文档 ID")
    private String documentId;
    @Schema(description = "版本号")
    private Integer version;
    @Schema(description = "知识文档名称")
    private String name;
    @Schema(description = "备注")
    private String remark;
    @Schema(description = "Markdown 知识正文")
    private String content;
    @Schema(description = "版本状态")
    private String status;
    @Schema(description = "版本来源")
    private String source;
    @Schema(description = "回退来源版本号")
    private Integer sourceVersion;
    @Schema(description = "解析失败原因")
    private String errorMessage;
}
