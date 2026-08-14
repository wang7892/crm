package cn.cordys.crm.task.dto.request;

import cn.cordys.common.dto.BasePageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskPageRequest extends BasePageRequest {

    @Size(max = 255)
    @Schema(description = "任务、客户或联系专员关键词")
    private String keyword;

    @Size(max = 32)
    @Schema(description = "显示状态：PENDING/IN_PROGRESS/OVERDUE/COMPLETED")
    private String status;

    @Size(max = 64)
    @Schema(description = "联系专员 ID；__UNASSIGNED__ 表示待管理层分配")
    private String assigneeId;
}
