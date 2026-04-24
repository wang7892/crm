package cn.cordys.crm.integration.webhook.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailWebhookResponse {
    private boolean success;
    private String eventId;
    private String code;
    private String message;
}

