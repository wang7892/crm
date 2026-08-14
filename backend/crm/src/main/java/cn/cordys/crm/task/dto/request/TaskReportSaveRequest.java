package cn.cordys.crm.task.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskReportSaveRequest {

    @NotBlank
    @Size(max = 32)
    @Schema(description = "任务 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;

    @Size(max = 100000)
    @Schema(description = "汇报内容")
    private String reportContent;

    @Size(max = 20000)
    @Schema(description = "修改后的 AI 建议回复")
    private String aiReply;
}
