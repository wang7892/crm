package cn.cordys.crm.customer.dto.request;

import cn.cordys.common.domain.BaseModuleFieldValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;


/**
 * @author jianxing
 * @date 2025-02-08 16:24:22
 */
@Data
public class CustomerAddRequest {

    @NotBlank
    @Size(max = 255)
    @Schema(description = "客户名称")
    private String name;

    @Size(max = 32)
    @Schema(description = "负责人")
    private String owner;

    @Size(max = 255)
    @Schema(description = "Customer WeCom external ID")
    private String wecomExternalId;

    @Size(max = 1024)
    @Schema(description = "企业微信群聊 roomid，多个 roomid 可用英文逗号分隔")
    private String roomid;

    @Size(max = 255)
    @Schema(description = "Email")
    private String email;

    @Size(max = 255)
    @Schema(description = "Full name")
    private String fullName;

    @Size(max = 255)
    @Schema(description = "Credit limit")
    private String creditLimit;

    @Size(max = 255)
    @Schema(description = "Customs code")
    private String customsCode;

    @Size(max = 255)
    @Schema(description = "Region")
    private String region;

    @Size(max = 255)
    @Schema(description = "Phone")
    private String phone;

    @Size(max = 512)
    @Schema(description = "Address")
    private String address;

    @Size(max = 512)
    @Schema(description = "Remark")
    private String remark;

    @Size(max = 255)
    @Schema(description = "Customer available")
    private String customerAvailable;

    @Size(max = 255)
    @Schema(description = "Customer source")
    private String customerSource;

    @Schema(description = "模块字段值")
    private List<BaseModuleFieldValue> moduleFields;

    @Schema(description = "最新跟进人(转客户时需录入)")
    private String follower;

    @Schema(description = "最新跟进时间(转客户时需录入)")
    private Long followTime;
}
