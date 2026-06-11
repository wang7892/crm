package cn.cordys.crm.integration.wecom.ingestion.dto.response;

import lombok.Data;

@Data
public class WecomIngestionSessionRow {
    private String sessionKey;
    private String chatType;
    private String roomid;
    private Long lastSendTime;
}
