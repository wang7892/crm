package cn.cordys.crm.task.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "crm_task_attachment")
public class CrmTaskAttachment extends BaseModel {

    @Schema(description = "任务 ID")
    private String taskId;

    @Schema(description = "组织 ID")
    private String organizationId;

    @Schema(description = "附件场景：TASK/REPORT")
    private String scene;

    @Schema(description = "附件根目录下的相对路径")
    private String storagePath;

    @Schema(description = "原始文件名")
    private String originalName;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "文件大小")
    private Long sizeBytes;

    @Schema(description = "SHA-256 指纹")
    private String sha256Hex;
}
