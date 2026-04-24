package mailmonitoring.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WebhookPushRequest {
    private final String organizationId;
    private final String sourceMailbox;
    private final String messageId;
    private final String threadId;
    private final String fromAddress;
    private final List<String> toAddresses;
    private final List<String> ccAddresses;
    private final String subject;
    private final String contentText;
    private final long sendTime;
    private final String matchedTargetMailbox;
    private final List<WebhookAttachment> attachments;

    public WebhookPushRequest(String organizationId, String sourceMailbox, String messageId, String threadId,
                              String fromAddress, List<String> toAddresses, List<String> ccAddresses, String subject,
                              String contentText, long sendTime, String matchedTargetMailbox,
                              List<WebhookAttachment> attachments) {
        this.organizationId = organizationId;
        this.sourceMailbox = sourceMailbox;
        this.messageId = messageId;
        this.threadId = threadId;
        this.fromAddress = fromAddress;
        this.toAddresses = new ArrayList<>(toAddresses == null ? Collections.emptyList() : toAddresses);
        this.ccAddresses = new ArrayList<>(ccAddresses == null ? Collections.emptyList() : ccAddresses);
        this.subject = subject == null ? "" : subject;
        this.contentText = contentText == null ? "" : contentText;
        this.sendTime = sendTime;
        this.matchedTargetMailbox = matchedTargetMailbox;
        this.attachments = new ArrayList<>(attachments == null ? Collections.emptyList() : attachments);
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public String getSourceMailbox() {
        return sourceMailbox;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public List<String> getToAddresses() {
        return Collections.unmodifiableList(toAddresses);
    }

    public List<String> getCcAddresses() {
        return Collections.unmodifiableList(ccAddresses);
    }

    public String getSubject() {
        return subject;
    }

    public String getContentText() {
        return contentText;
    }

    public long getSendTime() {
        return sendTime;
    }

    public String getMatchedTargetMailbox() {
        return matchedTargetMailbox;
    }

    public List<WebhookAttachment> getAttachments() {
        return Collections.unmodifiableList(attachments);
    }
}
