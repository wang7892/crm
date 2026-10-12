package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

@Data
public class AiKnowledgeDocumentEditResponse {
    private String documentId;
    private String name;
    private String originalName;
    private String fileType;
    private String remark;
    private String content;
    private Integer activeVersion;
    private Integer revision;
    private String parseStatus;
}
