package cn.cordys.crm.follow.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class FollowSpecialistResponse {

    @Schema(description = "联系专员ID")
    private String owner;

    @Schema(description = "联系专员名称")
    private String ownerName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "部门ID")
    private String departmentId;

    @Schema(description = "部门名称")
    private String departmentName;

    @Schema(description = "跟进客户数")
    private Long customerCount;

    @Schema(description = "跟进次数")
    private Long recordCount;

    @Schema(description = "最近跟进时间")
    private Long latestFollowTime;
}
