package mailmonitoring.model;

public class WebhookPushResponse {
    private final boolean success;
    private final String eventId;

    public WebhookPushResponse(boolean success, String eventId) {
        this.success = success;
        this.eventId = eventId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getEventId() {
        return eventId;
    }
}
