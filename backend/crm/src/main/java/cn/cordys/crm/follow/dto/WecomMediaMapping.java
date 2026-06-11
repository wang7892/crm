package cn.cordys.crm.follow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class WecomMediaMapping {

    @Schema(description = "Follow record ID")
    private String followRecordId;

    @Schema(description = "Message media type")
    private String msgMediaType;

    @Schema(description = "Media index in message")
    private Integer mediaIndex;

    @Schema(description = "File name")
    private String fileName;

    @Schema(description = "MIME type")
    private String mimeType;

    @Schema(description = "File size in bytes")
    private Long sizeBytes;

    @Schema(description = "Duration in milliseconds")
    private Integer durationMs;

    @Schema(description = "Fetch status")
    private String fetchStatus;

    @Schema(description = "CRM attachment id or URL")
    private String crmAssetRef;

    @Schema(description = "Preview URL")
    private String previewUrl;
}
