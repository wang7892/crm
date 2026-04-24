package mailmonitoring.repository;

import mailmonitoring.model.MailAttachment;

import java.util.List;

public interface CrmAttachmentRepository {
    void saveForEvent(String eventId, String organizationId, String userId, List<MailAttachment> attachments,
                      String attachmentPublicBaseUrl, String attachmentDownloadPath, String messageId);
}
