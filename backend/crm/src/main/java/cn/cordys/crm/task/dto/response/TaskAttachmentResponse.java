package cn.cordys.crm.task.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class TaskAttachmentResponse {

    @Schema(description = "附件 ID")
    private String id;

    @Schema(description = "任务 ID")
    private String taskId;

    @Schema(description = "TASK/REPORT")
    private String scene;

    @Schema(description = "原始文件名")
    private String originalName;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "文件大小")
    private Long sizeBytes;

    @Schema(description = "上传时间")
    private Long createTime;
}
