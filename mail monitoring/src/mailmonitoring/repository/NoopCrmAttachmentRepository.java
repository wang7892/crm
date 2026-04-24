package mailmonitoring.repository;

import mailmonitoring.model.MailAttachment;

import java.util.List;

public class NoopCrmAttachmentRepository implements CrmAttachmentRepository {
    @Override
    public void saveForEvent(String eventId, String organizationId, String userId, List<MailAttachment> attachments,
                             String attachmentPublicBaseUrl, String attachmentDownloadPath, String messageId) {
        // no-op
    }
}
