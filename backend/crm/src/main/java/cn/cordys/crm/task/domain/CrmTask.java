package cn.cordys.crm.task.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "crm_task")
public class CrmTask extends BaseModel {

    @Schema(description = "组织 ID")
    private String organizationId;

    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "任务来源：AI/MANAGER")
    private String source;

    @Schema(description = "自动任务业务唯一键")
    private String businessKey;

    @Schema(description = "联系专员 ID，为空时待管理层分配")
    private String assigneeId;

    @Schema(description = "客户 ID")
    private String customerId;

    @Schema(description = "任务说明")
    private String description;

    @Schema(description = "最晚完成时间")
    private Long deadline;

    @Schema(description = "持久化状态：PENDING/IN_PROGRESS/COMPLETED")
    private String status;

    @Schema(description = "汇报内容")
    private String reportContent;

    @Schema(description = "AI 建议回复")
    private String aiReply;

    @Schema(description = "开始执行时间")
    private Long startedAt;

    @Schema(description = "完成时间")
    private Long completedAt;

    @Schema(description = "汇报提交时间")
    private Long reportSubmittedAt;
}
