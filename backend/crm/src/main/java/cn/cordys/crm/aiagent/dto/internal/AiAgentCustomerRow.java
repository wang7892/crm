package cn.cordys.crm.aiagent.dto.internal;

import lombok.Data;

@Data
public class AiAgentCustomerRow {
    private String id;
    private String name;
    private String owner;
    private String ownerName;
    private String email;
    private String wecomExternalId;
    private String roomid;
    private String fullName;
    private String region;
    private String phone;
    private String address;
    private String remark;
    private Long followTime;
    private Long createTime;
    private Long updateTime;
}
