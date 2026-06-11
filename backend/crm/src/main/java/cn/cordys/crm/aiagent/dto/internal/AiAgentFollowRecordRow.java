package cn.cordys.crm.aiagent.dto.internal;

import lombok.Data;

@Data
public class AiAgentFollowRecordRow {
    private String id;
    private String customerId;
    private String customerName;
    private String ownerId;
    private String ownerName;
    private String followMethod;
    private String content;
    private Long followTime;
}
