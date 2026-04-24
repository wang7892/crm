package cn.cordys.crm.integration.webhook.domain;

import cn.cordys.common.domain.BaseModel;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "email_webhook_attachment")
public class EmailWebhookAttachment extends BaseModel {
    private String eventId;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private String downloadUrl;
    private String organizationId;
}

