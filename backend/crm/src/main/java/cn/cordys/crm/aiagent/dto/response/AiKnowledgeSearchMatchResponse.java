package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

@Data
public class AiKnowledgeSearchMatchResponse {
    private String documentId;
    private String documentName;
    private String chunkId;
    private Integer chunkIndex;
    private Integer pageNo;
    private String sectionPath;
    private double score;
    private String content;
}
