package cn.cordys.crm.integration.wecom.ingestion.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "wecom_ingestion_session_day")
public class WecomIngestionSessionDay extends BaseModel {

    @Schema(description = "组织 ID")
    @Column(name = "organization_id")
    private String organizationId;

    @Schema(description = "企业微信 corpId")
    @Column(name = "corp_id")
    private String corpId;

    @Schema(description = "会话日期 yyyy-MM-dd")
    @Column(name = "chat_date")
    private String chatDate;

    @Schema(description = "日会话唯一键")
    @Column(name = "session_key")
    private String sessionKey;

    @Schema(description = "single / room")
    @Column(name = "chat_type")
    private String chatType;

    @Column(name = "external_userid")
    private String externalUserid;

    @Column(name = "specialist_userid")
    private String specialistUserid;

    @Column(name = "roomid")
    private String roomid;

    @Column(name = "first_send_time")
    private Long firstSendTime;

    @Column(name = "last_send_time")
    private Long lastSendTime;

    @Column(name = "message_count")
    private Integer messageCount;

    @Column(name = "media_count")
    private Integer mediaCount;

    @Column(name = "merged_content")
    private String mergedContent;

    @Schema(description = "PENDING / SUCCESS / FAIL")
    private String status;

    @Column(name = "follow_record_id")
    private String followRecordId;

    @Column(name = "error_message")
    private String errorMessage;
}
