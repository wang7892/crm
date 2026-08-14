package cn.cordys.crm.order.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Table(name = "sales_order")
public class Order extends BaseModel {

    @Schema(description = "客户id")
    private String customerId;

    @Schema(description = "合同id")
    private String contractId;

    @Schema(description = "联系专员")
    private String owner;

    @Schema(description = "金额")
    private BigDecimal amount;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "加工单号")
    private String processOrderNo;

    @Schema(description = "加工商")
    private String processor;

    @Schema(description = "跟单员")
    private String merchandiser;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "颜色")
    private String color;

    @Schema(description = "色号")
    private String colorCode;

    @Schema(description = "成分")
    private String composition;

    @Schema(description = "原料名称")
    private String materialName;

    @Schema(description = "原料类型")
    private String materialType;

    @Schema(description = "加工工艺")
    private String processTechnology;

    @Schema(description = "下单时间")
    private Long orderTime;

    @Schema(description = "仓库实际发货日期")
    private Long warehouseActualShipDate;

    @Schema(description = "数量")
    private BigDecimal quantity;

    @Schema(description = "单位")
    private String unit;

    @Schema(description = "单价")
    private BigDecimal unitPrice;

    @Schema(description = "币种")
    private String currency;

    @Schema(description = "组织id")
    private String organizationId;
}
