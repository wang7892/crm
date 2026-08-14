package cn.cordys.crm.contract.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Table(name = "contract")
public class Contract extends BaseModel {

    @Schema(description = "合同名称")
    private String name;

    @Schema(description = "客户id")
    private String customerId;

    @Schema(description = "合同负责人")
    private String owner;

    @Schema(description = "金额")
    private BigDecimal amount;

    @Schema(description = "订单状态")
    private String orderStatus;

    @Schema(description = "币种")
    private String currency;

    @Schema(description = "编号")
    private String number;

    @Schema(description = "审核状态")
    private String approvalStatus;

    @Schema(description = "合同开始时间")
    private Long startTime;

    @Schema(description = "合同结束时间")
    private Long endTime;

    @Schema(description = "组织id")
    private String organizationId;
}
