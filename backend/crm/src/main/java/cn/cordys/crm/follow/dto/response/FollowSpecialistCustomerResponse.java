package cn.cordys.crm.follow.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class FollowSpecialistCustomerResponse {

    @Schema(description = "客户ID")
    private String customerId;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "跟进次数")
    private Long recordCount;

    @Schema(description = "最近跟进时间")
    private Long latestFollowTime;

    @Schema(description = "最近跟进内容")
    private String latestContent;

    @Schema(description = "最近联系人ID")
    private String contactId;

    @Schema(description = "最近联系人名称")
    private String contactName;

    @Schema(description = "最近联系人电话")
    private String contactPhone;
}
