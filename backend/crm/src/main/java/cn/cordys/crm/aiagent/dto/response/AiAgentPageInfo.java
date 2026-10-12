package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

/** Counts refer to the same filtered query, before its display limit. */
@Data
public class AiAgentPageInfo {
    private Long total;
    private long offset;
    private int returned;
    private Long remaining;
    private boolean hasMore;
    private boolean continuable;
    private String unit = "条";
}
