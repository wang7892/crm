package cn.cordys.crm.integration.webhook.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class EmailWebhookAttachmentRequest {
    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "内容类型")
    private String contentType;

    @Schema(description = "字节大小")
    private Long sizeBytes;

    @Schema(description = "下载地址")
    private String downloadUrl;
}

