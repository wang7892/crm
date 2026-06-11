package cn.cordys.crm.integration.wecom.ingestion.dto.request;

import cn.cordys.common.dto.BasePageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class WecomIngestionMessagesPageRequest extends BasePageRequest {

    @NotBlank
    @Schema(description = "会话键：room:{roomid} 或 single:{external}:{staffUserid}，与列表接口一致")
    private String sessionKey;
}
