package cn.cordys.crm.integration.wecom.ingestion.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WecomIngestionDeleteSessionRequest {

    @NotBlank
    @Schema(description = "会话主记录 ID")
    private String sessionKey;
}
