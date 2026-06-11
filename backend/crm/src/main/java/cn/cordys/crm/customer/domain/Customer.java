package cn.cordys.crm.customer.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;


/**
 * 客户
 *
 * @author jianxing
 * @date 2025-02-08 16:24:22
 */
@Data
@Table(name = "customer")
public class Customer extends BaseModel {

    @Schema(description = "客户名称")
    private String name;

    @Schema(description = "Customer WeCom external ID")
    private String wecomExternalId;

    @Schema(description = "企业微信群聊 roomid，多个 roomid 可用英文逗号分隔")
    private String roomid;

    @Schema(description = "Email")
    private String email;

    @Schema(description = "Full name")
    private String fullName;

    @Schema(description = "Credit limit")
    private String creditLimit;

    @Schema(description = "Customs code")
    private String customsCode;

    @Schema(description = "Region")
    private String region;

    @Schema(description = "Phone")
    private String phone;

    @Schema(description = "Address")
    private String address;

    @Schema(description = "Remark")
    private String remark;

    @Schema(description = "Customer available")
    private String customerAvailable;

    @Schema(description = "Customer source")
    private String customerSource;

    @Schema(description = "负责人")
    private String owner;

    @Schema(description = "领取时间")
    private Long collectionTime;

    @Schema(description = "公海ID")
    private String poolId;

    @Schema(description = "是否在公海池")
    private Boolean inSharedPool;

    @Schema(description = "组织id")
    private String organizationId;

    @Schema(description = "最新跟进人")
    private String follower;

    @Schema(description = "最新跟进时间")
    private Long followTime;

    @Schema(description = "公海原因ID")
    private String reasonId;
}
