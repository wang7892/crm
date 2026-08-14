package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

@Data
public class AiKnowledgeDocumentResponse {
    private String id;
    private String organizationId;
    private String name;
    private String originalName;
    private String fileType;
    private Long fileSize;
    private String category;
    private String parseStatus;
    private String parseError;
    private Integer chunkCount;
    private Integer enabled;
    private String remark;
    private String semanticStatus;
    private AiSemanticRuleStats ruleStats;
    private String createUser;
    private String updateUser;
    private Long createTime;
    private Long updateTime;
}
