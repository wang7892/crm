package mailmonitoring.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class MailEvent {
    private final String organizationId;
    private final String sourceMailbox;
    private final String targetMailbox;
    private final String messageId;
    private final String threadId;
    private final String fromAddress;
    private final List<String> toAddresses;
    private final List<String> ccAddresses;
    private final List<MailAttachment> attachments;
    private final String subject;
    private final String contentText;
    private final Instant sendTime;

    private ProcessStatus processStatus = ProcessStatus.NEW;
    private String followRecordId;
    private String errorMessage;
    private int retryCount;
    private Instant nextRetryAt;

    public MailEvent(String organizationId, String sourceMailbox, String targetMailbox, String messageId, String threadId,
                     String fromAddress, List<String> toAddresses, List<String> ccAddresses, String subject,
                     String contentText, Instant sendTime, List<MailAttachment> attachments) {
        this.organizationId = Objects.requireNonNull(organizationId);
        this.sourceMailbox = Objects.requireNonNull(sourceMailbox);
        this.targetMailbox = Objects.requireNonNull(targetMailbox);
        this.messageId = Objects.requireNonNull(messageId);
        this.threadId = threadId;
        this.fromAddress = Objects.requireNonNull(fromAddress);
        this.toAddresses = new ArrayList<>(toAddresses == null ? Collections.emptyList() : toAddresses);
        this.ccAddresses = new ArrayList<>(ccAddresses == null ? Collections.emptyList() : ccAddresses);
        this.attachments = new ArrayList<>(attachments == null ? Collections.emptyList() : attachments);
        this.subject = subject == null ? "" : subject;
        this.contentText = contentText == null ? "" : contentText;
        this.sendTime = sendTime == null ? Instant.now() : sendTime;
    }

    public String dedupeKey() {
        return organizationId + "|" + sourceMailbox + "|" + messageId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public String getSourceMailbox() {
        return sourceMailbox;
    }

    public String getTargetMailbox() {
        return targetMailbox;
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

    public List<MailAttachment> getAttachments() {
        return Collections.unmodifiableList(attachments);
    }

    public String getSubject() {
        return subject;
    }

    public String getContentText() {
        return contentText;
    }

    public Instant getSendTime() {
        return sendTime;
    }

    public ProcessStatus getProcessStatus() {
        return processStatus;
    }

    public void setProcessStatus(ProcessStatus processStatus) {
        this.processStatus = processStatus;
    }

    public String getFollowRecordId() {
        return followRecordId;
    }

    public void setFollowRecordId(String followRecordId) {
        this.followRecordId = followRecordId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void increaseRetryCount() {
        this.retryCount++;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }
}
