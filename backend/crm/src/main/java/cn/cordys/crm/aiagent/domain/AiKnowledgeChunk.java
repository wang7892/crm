package cn.cordys.crm.aiagent.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "ai_knowledge_chunk")
public class AiKnowledgeChunk extends BaseModel {
    @Schema(description = "组织 ID")
    private String organizationId;
    @Schema(description = "文档 ID")
    private String documentId;
    @Schema(description = "切片序号")
    private Integer chunkIndex;
    @Schema(description = "片段标题")
    private String title;
    @Schema(description = "片段文本")
    private String content;
    @Schema(description = "内容哈希")
    private String contentHash;
    @Schema(description = "PDF 页码")
    private Integer pageNo;
    @Schema(description = "Word 标题路径")
    private String sectionPath;
    @Schema(description = "估算 token 数")
    private Integer tokenCount;
    @Schema(description = "向量状态")
    private String embeddingStatus;
    @Schema(description = "向量库 ID")
    private String embeddingId;
    @Schema(description = "是否启用")
    private Integer enabled;
}
