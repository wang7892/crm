package cn.cordys.crm.aiagent.dto.internal;

import lombok.Data;

/** Server-owned parameters for repeating a legacy external-order query under current permissions. */
@Data
public class AiAgentExternalOrderQuery {
    private String specialistName;
    private String customerKeyword;
    private boolean visibleCustomers;
    private boolean activeOnly;
    private boolean recent;
    private String orderNo;
    private String productName;
}
