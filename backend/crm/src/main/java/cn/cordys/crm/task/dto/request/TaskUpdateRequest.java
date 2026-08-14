package cn.cordys.crm.task.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class TaskUpdateRequest extends TaskAddRequest {

    @NotBlank
    @Size(max = 32)
    @Schema(description = "任务 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;
}
