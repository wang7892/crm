package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

@Data
public class AiKnowledgeDocumentVersionResponse {
    private String id;
    private String documentId;
    private Integer version;
    private String name;
    private String remark;
    private String content;
    private String status;
    private String source;
    private Integer sourceVersion;
    private String errorMessage;
    private String createUser;
    private String createUserName;
    private Long createTime;
    private Long updateTime;
}
