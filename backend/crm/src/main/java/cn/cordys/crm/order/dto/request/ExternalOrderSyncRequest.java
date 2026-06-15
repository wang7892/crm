package cn.cordys.crm.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ExternalOrderSyncRequest {

    @Schema(description = "本次最多同步条数，默认 5000")
    private Integer limit;

    @Schema(description = "只同步外部 order_info.id 大于该值的数据")
    private Long minId;
}
