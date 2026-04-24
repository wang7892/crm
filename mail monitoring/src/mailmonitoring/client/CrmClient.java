package mailmonitoring.client;

import mailmonitoring.model.WebhookPushRequest;
import mailmonitoring.model.WebhookPushResponse;

public interface CrmClient {
    WebhookPushResponse pushMailEvent(WebhookPushRequest request) throws Exception;
}
