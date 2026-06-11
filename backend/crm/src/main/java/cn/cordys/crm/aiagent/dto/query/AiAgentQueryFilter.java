package cn.cordys.crm.aiagent.dto.query;

import lombok.Data;

@Data
public class AiAgentQueryFilter {
    private String field;
    private String operator;
    private Object value;
}
