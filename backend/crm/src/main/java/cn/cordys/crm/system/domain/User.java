package cn.cordys.crm.system.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "sys_user")
public class User extends BaseModel {

    @Schema(description = "用户名")
    private String name;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "邮箱授权码")
    @Column(name = "email_auth_code")
    private String emailAuthCode;

    @Schema(description = "企微id")
    @Column(name = "wecom_id")
    private String wecomId;

    @Schema(description = "企业微信群聊 roomid，多个 roomid 可用英文逗号分隔")
    private String roomid;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "性别(0-男/1-女)")
    private Boolean gender;

    @Schema(description = "语言")
    private String language;

    @Schema(description = "当前组织ID")
    private String lastOrganizationId;
}
