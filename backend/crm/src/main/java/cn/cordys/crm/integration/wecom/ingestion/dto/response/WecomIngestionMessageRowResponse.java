package cn.cordys.crm.integration.wecom.ingestion.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class WecomIngestionMessageRowResponse {

    private String id;

    @Schema(description = "消息方向 OUTBOUND / INBOUND")
    private String messageDirection;

    @Schema(description = "消息类型")
    private String msgType;

    @Schema(description = "single / room")
    private String chatType;

    @Schema(description = "企业微信群聊 roomid")
    private String roomid;

    @Schema(description = "文本摘要")
    private String contentText;

    @Schema(description = "发送时间毫秒")
    private Long sendTime;

    @Schema(description = "展示用：外部联系人 id")
    private String wecomCustomerExternalUserid;

    @Schema(description = "展示用：内部成员 userid")
    private String wecomStaffUserid;

    @Schema(description = "CRM 客户名")
    private String matchedCustomerName;

    @Schema(description = "CRM 专员姓名")
    private String matchedStaffName;

    @Schema(description = "匹配规则摘要")
    private String matchRuleSummary;

    @Schema(description = "是否已生成跟进")
    private Boolean synced;

    private String followRecordId;
}
