package cn.cordys.crm.aiagent.dto;

import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.internal.AiAgentExternalOrderQuery;
import lombok.Data;

/** Only persisted server-side; a client can reference its message, never submit a plan. */
@Data
public class AiAgentQueryContinuation {
    private AiAgentQueryPlan plan;
    private String generatedSql;
    private AiAgentExternalOrderQuery externalQuery;
    private int pageSize;
    private AiAgentTimeWindow timeWindow;
    private String question;
    private long nextOffset;
}
