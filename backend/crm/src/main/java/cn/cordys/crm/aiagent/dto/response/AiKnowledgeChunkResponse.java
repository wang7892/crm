package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

@Data
public class AiKnowledgeChunkResponse {
    private String id;
    private String documentId;
    private String documentName;
    private Integer chunkIndex;
    private String title;
    private String content;
    private Integer pageNo;
    private String sectionPath;
    private Integer tokenCount;
    private String embeddingStatus;
    private Integer enabled;
    private Long createTime;
    private Long updateTime;
}
