package cn.cordys.crm.order.dto.response;

import cn.cordys.common.domain.BaseModuleFieldValue;
import cn.cordys.crm.order.domain.Order;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class OrderListResponse extends Order {

    @Schema(description = "订单名称")
    private String name;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "合同名称")
    private String  contractName;

    @Schema(description = "联系专员名称")
    private String ownerName;

    @Schema(description = "创建人名称")
    private String createUserName;

    @Schema(description = "更新人名称")
    private String updateUserName;

    @Schema(description = "部门id")
    private String departmentId;

    @Schema(description = "部门名称")
    private String departmentName;

    @Schema(description = "关联的客户是否在公海")
    private Boolean inCustomerPool;

    @Schema(description = "客户公海id")
    private String poolId;

    @Schema(description = "商机阶段")
    private String stageName;

    @Schema(description = "订单状态")
    private String stage;

    @Schema(description = "自定义字段")
    private List<BaseModuleFieldValue> moduleFields;
}
