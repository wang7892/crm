package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

@Data
public class AiSemanticRuleStats {
    private int total;
    private int pending;
    private int approved;
    private int rejected;
    private int invalid;
}
