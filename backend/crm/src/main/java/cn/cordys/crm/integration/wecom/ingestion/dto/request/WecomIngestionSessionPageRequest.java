package cn.cordys.crm.integration.wecom.ingestion.dto.request;

import cn.cordys.common.dto.BasePageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class WecomIngestionSessionPageRequest extends BasePageRequest {
}
