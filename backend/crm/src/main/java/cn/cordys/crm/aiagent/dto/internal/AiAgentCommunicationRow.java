package cn.cordys.crm.aiagent.dto.internal;

import lombok.Data;

@Data
public class AiAgentCommunicationRow {
    private String customerId;
    private String customerName;
    private String ownerId;
    private String ownerName;
    private Long wecomMessageCount;
    private Long emailCount;
    private Long followRecordCount;
    private Long lastCommunicationTime;
}
