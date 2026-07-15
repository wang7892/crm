package cn.cordys.crm.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderSummaryResponse {

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "加工商")
    private String processor;

    @Schema(description = "联系专员")
    private String owner;

    @Schema(description = "联系专员名称")
    private String ownerName;

    @Schema(description = "跟单员")
    private String merchandiser;

    @Schema(description = "下单时间")
    private Long orderTime;

    @Schema(description = "数量")
    private BigDecimal quantity;

    @Schema(description = "单位")
    private String unit;

    @Schema(description = "金额")
    private BigDecimal amount;

    @Schema(description = "币种")
    private String currency;
}
