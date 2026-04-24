package mailmonitoring.service;

import mailmonitoring.client.CrmClient;
import mailmonitoring.client.MailProviderClient;
import mailmonitoring.model.MailBatchResult;
import mailmonitoring.model.MailAttachment;
import mailmonitoring.model.MailEvent;
import mailmonitoring.model.WebhookAttachment;
import mailmonitoring.model.WebhookPushRequest;
import mailmonitoring.model.WebhookPushResponse;
import mailmonitoring.repository.CrmAttachmentRepository;
import mailmonitoring.repository.MailEventRepository;
import mailmonitoring.repository.SyncCursorRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public class MailMonitorService {
    private final String organizationId;
    private final String sourceMailbox;
    private final List<String> targetMailboxes;
    private final MailProviderClient mailProviderClient;
    private final CrmClient crmClient;
    private final MailEventRepository eventRepository;
    private final SyncCursorRepository cursorRepository;
    private final CrmAttachmentRepository crmAttachmentRepository;
    private final RetryPolicy retryPolicy;
    private final String attachmentPublicBaseUrl;
    private final String attachmentDownloadPath;
    private final String attachmentSaveDir;

    public MailMonitorService(String organizationId, String sourceMailbox, List<String> targetMailboxes,
                              MailProviderClient mailProviderClient, CrmClient crmClient,
                              MailEventRepository eventRepository,
                              SyncCursorRepository cursorRepository,
                              CrmAttachmentRepository crmAttachmentRepository,
                              RetryPolicy retryPolicy,
                              String attachmentPublicBaseUrl,
                              String attachmentDownloadPath,
                              String attachmentSaveDir) {
        this.organizationId = organizationId;
        this.sourceMailbox = sourceMailbox;
        this.targetMailboxes = targetMailboxes;
        this.mailProviderClient = mailProviderClient;
        this.crmClient = crmClient;
        this.eventRepository = eventRepository;
        this.cursorRepository = cursorRepository;
        this.crmAttachmentRepository = crmAttachmentRepository;
        this.retryPolicy = retryPolicy;
        this.attachmentPublicBaseUrl = attachmentPublicBaseUrl;
        this.attachmentDownloadPath = attachmentDownloadPath;
        this.attachmentSaveDir = attachmentSaveDir;
    }

    public void pollOnce() {
        String cursor = cursorRepository.getCursor(sourceMailbox);
        MailBatchResult batch = mailProviderClient.fetchNewEvents(sourceMailbox, cursor);
        cursorRepository.saveCursor(sourceMailbox, batch.getNextCursor());
        for (MailEvent event : batch.getEvents()) {
            handleIncomingEvent(event);
        }
        processRetries();
    }

    private void handleIncomingEvent(MailEvent event) {
        if (!event.getFromAddress().equalsIgnoreCase(sourceMailbox)) {
            return;
        }
        String matchedTarget = findMatchedMailbox(event.getToAddresses());
        if (matchedTarget == null) {
            return;
        }
        if (!eventRepository.saveIfAbsent(event)) {
            System.out.printf("[DEDUP] skip duplicated message: %s%n", event.getMessageId());
            return;
        }
        processWebhook(event, matchedTarget);
    }

    private void processRetries() {
        List<MailEvent> retryable = eventRepository.findRetryable(Instant.now());
        for (MailEvent event : retryable) {
            String matchedTarget = findMatchedMailbox(event.getToAddresses());
            if (matchedTarget != null) {
                processWebhook(event, matchedTarget);
            }
        }
    }

    private void processWebhook(MailEvent event, String matchedTargetMailbox) {
        try {
            WebhookPushRequest request = buildRequest(event, matchedTargetMailbox);
            WebhookPushResponse response = crmClient.pushMailEvent(request);
            if (!response.isSuccess()) {
                String detail = response.getEventId() == null ? "" : response.getEventId();
                throw new IllegalStateException("Webhook response success=false, body=" + detail);
            }
            crmAttachmentRepository.saveForEvent(
                    response.getEventId(),
                    organizationId,
                    sourceMailbox,
                    event.getAttachments(),
                    attachmentPublicBaseUrl,
                    attachmentDownloadPath,
                    event.getMessageId()
            );
            eventRepository.markProcessed(event, response.getEventId());
            System.out.printf("[SUCCESS] matched email sent event, messageId=%s, matchedMailbox=%s, eventId=%s%n",
                    event.getMessageId(), matchedTargetMailbox, response.getEventId());
        } catch (Exception ex) {
            Duration delay = retryPolicy.nextDelay(event.getRetryCount());
            if (delay == null) {
                eventRepository.markDead(event, ex.getMessage());
                System.out.printf("[DEAD] messageId=%s, error=%s%n", event.getMessageId(), ex.getMessage());
                return;
            }
            Instant nextRetryAt = Instant.now().plus(delay);
            eventRepository.markFailed(event, ex.getMessage(), nextRetryAt);
            System.out.printf("[RETRY] messageId=%s, retryCount=%d, nextRetryAt=%s, error=%s%n",
                    event.getMessageId(), event.getRetryCount(), nextRetryAt, ex.getMessage());
        }
    }

    private WebhookPushRequest buildRequest(MailEvent event, String matchedTargetMailbox) {
        List<WebhookAttachment> attachments = buildAttachments(event);
        return new WebhookPushRequest(
                organizationId,
                sourceMailbox,
                event.getMessageId(),
                event.getThreadId(),
                event.getFromAddress(),
                event.getToAddresses(),
                event.getCcAddresses(),
                event.getSubject(),
                event.getContentText(),
                event.getSendTime().toEpochMilli(),
                matchedTargetMailbox,
                attachments
        );
    }

    private List<WebhookAttachment> buildAttachments(MailEvent event) {
        List<MailAttachment> items = event.getAttachments();
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (attachmentPublicBaseUrl == null || attachmentPublicBaseUrl.isBlank()) {
            return List.of();
        }

        String safeMessageId = toSafeMessageId(event.getMessageId());
        String base = removeTrailingSlash(attachmentPublicBaseUrl);
        String path = normalizePath(attachmentDownloadPath);

        java.util.ArrayList<WebhookAttachment> result = new java.util.ArrayList<>();
        for (MailAttachment item : items) {
            if (item == null) {
                continue;
            }
            String fileName = item.getFileName();
            if (fileName == null || fileName.isBlank()) {
                continue;
            }
            String url = base + path + "/" + urlEncode(safeMessageId) + "/" + urlEncode(fileName);
            result.add(new WebhookAttachment(fileName, item.getContentType(), item.getSizeBytes(), url));
        }
        return result;
    }

    private String toSafeMessageId(String messageId) {
        if (messageId == null) {
            return "unknown";
        }
        String safe = messageId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/api/attachments";
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String removeTrailingSlash(String input) {
        if (input == null) {
            return "";
        }
        String result = input.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String findMatchedMailbox(List<String> addresses) {
        if (addresses == null || targetMailboxes == null || targetMailboxes.isEmpty()) {
            return null;
        }
        for (String configured : targetMailboxes) {
            String normalizedConfigured = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
            if (normalizedConfigured.isBlank()) {
                continue;
            }
            for (String address : addresses) {
                if (address != null && address.trim().toLowerCase(Locale.ROOT).equals(normalizedConfigured)) {
                    return normalizedConfigured;
                }
            }
        }
        return null;
    }
}
