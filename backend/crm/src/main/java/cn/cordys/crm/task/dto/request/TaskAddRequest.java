package cn.cordys.crm.task.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskAddRequest {

    @NotBlank
    @Size(max = 255)
    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @NotBlank
    @Size(max = 32)
    @Schema(description = "联系专员 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String assigneeId;

    @Size(max = 32)
    @Schema(description = "客户 ID")
    private String customerId;

    @Size(max = 10000)
    @Schema(description = "任务说明")
    private String description;

    @NotNull
    @Schema(description = "最晚完成时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long deadline;
}
