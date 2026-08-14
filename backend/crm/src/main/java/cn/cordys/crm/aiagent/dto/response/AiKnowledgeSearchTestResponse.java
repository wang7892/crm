package cn.cordys.crm.aiagent.dto.response;

import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleContext;
import lombok.Data;

import java.util.List;

@Data
public class AiKnowledgeSearchTestResponse {
    private String question;
    private String rewriteQuestion;
    private List<AiKnowledgeSearchMatchResponse> matches;
    private String answerPreview;
    private String retrievalMode;
    private List<AiSemanticRuleMatchResponse> matchedRules;
    private AiAgentSemanticRuleContext injectedContextPreview;
    private String fallbackReason;
}
