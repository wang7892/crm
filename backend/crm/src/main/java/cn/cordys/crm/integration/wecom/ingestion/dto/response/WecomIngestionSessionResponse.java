package cn.cordys.crm.integration.wecom.ingestion.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class WecomIngestionSessionResponse {

    @Schema(description = "会话键")
    private String sessionKey;

    @Schema(description = "single / room")
    private String chatType;

    @Schema(description = "企业微信群聊 roomid")
    private String roomid;

    @Schema(description = "会话日期 yyyy-MM-dd")
    private String chatDate;

    @Schema(description = "最后一条消息时间")
    private Long lastSendTime;

    @Schema(description = "当天消息数")
    private Integer messageCount;

    @Schema(description = "同步状态：PENDING / SUCCESS / FAIL")
    private String status;

    @Schema(description = "跟进记录 ID")
    private String followRecordId;

    @Schema(description = "列表预览文案")
    private String lastPreview;

    @Schema(description = "解析出的客户企微 external_userid，展示用")
    private String wecomCustomerExternalUserid;

    @Schema(description = "解析出的专员企微 userid")
    private String wecomStaffUserid;

    @Schema(description = "匹配到的 CRM 客户名称，未匹配为空")
    private String matchedCustomerName;

    @Schema(description = "匹配到的 CRM 用户名称")
    private String matchedStaffName;

    @Schema(description = "匹配规则说明")
    private String matchRuleSummary;
}
