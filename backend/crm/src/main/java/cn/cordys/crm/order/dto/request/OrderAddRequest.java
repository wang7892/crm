package cn.cordys.crm.order.dto.request;

import cn.cordys.common.domain.BaseModuleFieldValue;
import cn.cordys.crm.system.dto.response.ModuleFormConfigDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderAddRequest {

    @Size(max = 255)
    @Schema(description = "订单名称")
    private String name;

    @Size(max = 32)
    @Schema(description = "客户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String customerId;

    @Size(max = 32)
    @Schema(description = "合同id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String contractId;

    @Size(max = 32)
    @Schema(description = "联系专员", requiredMode = Schema.RequiredMode.REQUIRED)
    private String owner;

    @Schema(description = "累计金额")
    private String amount;

    @Schema(description = "自定义字段")
    private List<BaseModuleFieldValue> moduleFields;

    @Schema(description = "表单配置")
    private ModuleFormConfigDTO moduleFormConfigDTO;

    @Schema(description = "订单号")
    @Size(max = 50)
    private String orderNo;

    @Schema(description = "加工单号")
    @Size(max = 50)
    private String processOrderNo;

    @Schema(description = "加工商")
    @Size(max = 100)
    private String processor;

    @Schema(description = "跟单员")
    @Size(max = 50)
    private String merchandiser;

    @Schema(description = "状态")
    @Size(max = 50)
    private String status;

    @Schema(description = "颜色")
    @Size(max = 50)
    private String color;

    @Schema(description = "色号")
    @Size(max = 50)
    private String colorCode;

    @Schema(description = "成分")
    @Size(max = 200)
    private String composition;

    @Schema(description = "原料名称")
    @Size(max = 100)
    private String materialName;

    @Schema(description = "原料类型")
    @Size(max = 50)
    private String materialType;

    @Schema(description = "加工工艺")
    @Size(max = 100)
    private String processTechnology;

    @Schema(description = "下单时间")
    private Long orderTime;

    @Schema(description = "数量")
    private BigDecimal quantity;

    @Schema(description = "单位")
    @Size(max = 20)
    private String unit;

    @Schema(description = "单价")
    private BigDecimal unitPrice;

    @Schema(description = "币种")
    @Size(max = 20)
    private String currency;
}
