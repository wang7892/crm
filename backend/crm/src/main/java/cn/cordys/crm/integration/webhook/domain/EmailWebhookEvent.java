package cn.cordys.crm.integration.webhook.domain;

import cn.cordys.common.domain.BaseModel;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Table(name = "email_webhook_event")
public class EmailWebhookEvent extends BaseModel {
    private String sourceMailbox;
    private String messageId;
    private String threadId;
    private String fromAddress;
    private String matchedTargetMailbox;
    private String subject;
    private String contentText;
    /**
     * JSON string: ["a@x.com","b@y.com"]
     */
    private String toAddresses;
    /**
     * JSON string: []
     */
    private String ccAddresses;
    private String followRecordId;
    private String status;
    private String errorMessage;
    private String organizationId;
}

