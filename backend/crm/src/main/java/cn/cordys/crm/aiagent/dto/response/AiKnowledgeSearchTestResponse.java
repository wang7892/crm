package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class AiKnowledgeSearchTestResponse {
    private String question;
    private String rewriteQuestion;
    private List<AiKnowledgeSearchMatchResponse> matches;
    private String answerPreview;
}
