package cn.cordys.crm.aiagent.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "ai_knowledge_parse_job")
public class AiKnowledgeParseJob extends BaseModel {
    @Schema(description = "组织 ID")
    private String organizationId;
    @Schema(description = "文档 ID")
    private String documentId;
    @Schema(description = "任务状态")
    private String status;
    @Schema(description = "当前步骤")
    private String step;
    @Schema(description = "状态说明")
    private String message;
    @Schema(description = "失败堆栈")
    private String errorStack;
    @Schema(description = "开始时间")
    private Long startTime;
    @Schema(description = "完成时间")
    private Long finishTime;
}
