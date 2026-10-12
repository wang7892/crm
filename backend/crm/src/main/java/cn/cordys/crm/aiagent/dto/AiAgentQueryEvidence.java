package cn.cordys.crm.aiagent.dto;

import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-produced query evidence; never populated from a chat request. */
@Data
public class AiAgentQueryEvidence {
    private String id;
    private String question;
    private long queriedAt;
    private boolean requiresRefresh;
    private AiAgentQueryContinuation continuation;
    private cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan queryPlan;
    private AiAgentTimeWindow timeWindow;
    private AiAgentChatResponse response;
    private Map<String, Object> data = new LinkedHashMap<>();
}
