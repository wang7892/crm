package cn.cordys.crm.system.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.util.List;

@Data
public class UserAddRequest {

    @Size(max = 255)
    @Schema(description = "姓名")
    @NotBlank
    private String name;

    @Size(max = 11)
    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "性别(false-男/true-女)")
    @NotNull
    private Boolean gender;

    @Pattern(regexp = "^$|^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$", message = "{email.format_error}")
    @Schema(description = "邮箱")
    private String email;

    @Size(max = 255)
    @Schema(description = "邮箱授权码")
    @JsonProperty("emailAuthCode")
    @JsonAlias("email_auth_code")
    private String emailAuthCode;

    /**
     * 兼容前端 snake_case 入参：`email_auth_code`。
     * <p>
     * `emailAuthCode` 会依赖字段名默认反序列化；这里只补齐 snake_case，避免由于命名策略差异导致字段反序列化为空。
     */
    @JsonSetter("email_auth_code")
    public void setEmailAuthCodeFromSnakeCase(String emailAuthCode) {
        this.emailAuthCode = emailAuthCode;
    }

    @Size(max = 255)
    @Schema(description = "企微id")
    @JsonProperty("wecomId")
    @JsonAlias("wecom_id")
    private String wecomId;

    @Size(max = 1024)
    @Schema(description = "企业微信群聊 roomid，多个 roomid 可用英文逗号分隔")
    private String roomid;

    @JsonSetter("wecom_id")
    public void setWecomIdFromSnakeCase(String wecomId) {
        this.wecomId = wecomId;
    }

    @Schema(description = "部门id")
    @NotBlank
    private String departmentId;

    @Schema(description = "工号")
    private String employeeId;

    @Schema(description = "员工类型")
    private String employeeType;

    @Schema(description = "直属上级")
    private String supervisorId;

    @Schema(description = "职位")
    private String position;

    @Schema(description = "工作城市")
    private String workCity;

    @Schema(description = "角色")
    @NotEmpty
    private List<String> roleIds;

    @Schema(description = "用户组")
    private List<String> userGroupIds;

    @Schema(description = "是否启用")
    private Boolean enable;

    @Schema(description = "入职时间")
    private Long onboardingDate;

}
