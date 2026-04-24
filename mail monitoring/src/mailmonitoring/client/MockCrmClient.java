package mailmonitoring.client;

import mailmonitoring.model.WebhookPushRequest;
import mailmonitoring.model.WebhookPushResponse;

import java.util.UUID;

public class MockCrmClient implements CrmClient {
    @Override
    public WebhookPushResponse pushMailEvent(WebhookPushRequest request) {
        String eventId = "evt-" + UUID.randomUUID().toString().substring(0, 8);
        System.out.printf("[WEBHOOK] push success, messageId=%s, matchedMailbox=%s, eventId=%s%n",
                request.getMessageId(), request.getMatchedTargetMailbox(), eventId);
        return new WebhookPushResponse(true, eventId);
    }
}
