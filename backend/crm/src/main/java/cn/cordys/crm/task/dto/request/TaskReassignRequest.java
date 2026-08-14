package cn.cordys.crm.task.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskReassignRequest {

    @NotBlank
    @Size(max = 32)
    @Schema(description = "任务 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;

    @NotBlank
    @Size(max = 32)
    @Schema(description = "新的联系专员 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String assigneeId;
}
