package cn.cordys.crm.integration.wecom.ingestion.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "wecom_ingestion_message")
public class WecomIngestionEvent extends BaseModel {

    @Schema(description = "日会话主记录 ID")
    @Column(name = "session_day_id")
    private String sessionDayId;

    @Schema(description = "组织 ID")
    @Column(name = "organization_id")
    private String organizationId;

    @Schema(description = "企业微信 corpId")
    @Column(name = "corp_id")
    private String corpId;

    @Schema(description = "企微消息幂等键")
    @Column(name = "wecom_msg_id")
    private String wecomMsgId;

    @Schema(description = "OUTBOUND / INBOUND")
    @Column(name = "message_direction")
    private String messageDirection;

    @Column(name = "sender_userid")
    private String senderUserid;

    @Column(name = "sender_external_userid")
    private String senderExternalUserid;

    @Column(name = "peer_userid")
    private String peerUserid;

    @Schema(description = "single / room")
    @Column(name = "chat_type")
    private String chatType;

    @Column(name = "external_userid")
    private String externalUserid;

    @Column(name = "roomid")
    private String roomid;

    @Column(name = "matched_external_userid")
    private String matchedExternalUserid;

    @Column(name = "msg_type")
    private String msgType;

    @Column(name = "content_text")
    private String contentText;

    @Column(name = "send_time")
    private Long sendTime;

    @Column(name = "extra_json")
    private String extraJson;

    @Schema(description = "PENDING / SUCCESS / FAIL")
    private String status;

    @Column(name = "follow_record_id")
    private String followRecordId;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "update_user")
    private String updateUser;

    @Column(name = "update_time")
    private Long updateTime;
}
