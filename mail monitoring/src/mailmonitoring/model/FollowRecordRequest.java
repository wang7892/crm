package mailmonitoring.model;

public class FollowRecordRequest {
    private final String messageId;
    private final String type;
    private final String customerId;
    private final String contactId;
    private final String content;
    private final long followTime;
    private final String followMethod;
    private final String owner;

    public FollowRecordRequest(String messageId, String type, String customerId, String contactId,
                               String content, long followTime, String followMethod, String owner) {
        this.messageId = messageId;
        this.type = type;
        this.customerId = customerId;
        this.contactId = contactId;
        this.content = content;
        this.followTime = followTime;
        this.followMethod = followMethod;
        this.owner = owner;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getType() {
        return type;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getContactId() {
        return contactId;
    }

    public String getContent() {
        return content;
    }

    public long getFollowTime() {
        return followTime;
    }

    public String getFollowMethod() {
        return followMethod;
    }

    public String getOwner() {
        return owner;
    }
}
