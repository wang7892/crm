package cn.cordys.crm.aiagent.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "ai_knowledge_query_log")
public class AiKnowledgeQueryLog extends BaseModel {
    @Schema(description = "组织 ID")
    private String organizationId;
    @Schema(description = "会话 ID")
    private String sessionId;
    @Schema(description = "消息 ID")
    private String messageId;
    @Schema(description = "原问题")
    private String question;
    @Schema(description = "重写后问题")
    private String rewriteQuestion;
    @Schema(description = "检索模式")
    private String retrievalMode;
    @Schema(description = "命中的切片 JSON")
    private String matchedChunks;
    @Schema(description = "回答模式")
    private String answerMode;
}
