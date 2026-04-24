package mailmonitoring.repository;

import mailmonitoring.model.MailAttachment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

public class JdbcCrmAttachmentRepository implements CrmAttachmentRepository {
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public JdbcCrmAttachmentRepository(String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception ex) {
            System.err.println("[BOOT] MySQL driver not found for CRM attachment repository.");
        }
    }

    @Override
    public void saveForEvent(String eventId, String organizationId, String userId, List<MailAttachment> attachments,
                             String attachmentPublicBaseUrl, String attachmentDownloadPath, String messageId) {
        if (eventId == null || eventId.isBlank() || attachments == null || attachments.isEmpty()) {
            return;
        }
        String base = removeTrailingSlash(attachmentPublicBaseUrl);
        if (base.isBlank()) {
            return;
        }
        String downloadPath = normalizePath(attachmentDownloadPath);
        String safeMessageId = toSafeMessageId(messageId);
        long now = System.currentTimeMillis();

        String sql = "INSERT INTO email_webhook_attachment(id, event_id, file_name, content_type, size_bytes, download_url, organization_id, create_time, update_time, create_user, update_user) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            // CRM webhook service may have already inserted attachments for this eventId.
            // If so, skip direct-write to avoid duplicated rows.
            if (existsByEventId(connection, eventId)) {
                return;
            }
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (MailAttachment attachment : attachments) {
                if (attachment == null || attachment.getFileName() == null || attachment.getFileName().isBlank()) {
                    continue;
                }
                String url = base + downloadPath + "/" + urlEncode(safeMessageId) + "/" + urlEncode(attachment.getFileName());
                stmt.setString(1, randomId());
                stmt.setString(2, eventId);
                stmt.setString(3, attachment.getFileName());
                stmt.setString(4, attachment.getContentType() == null || attachment.getContentType().isBlank()
                        ? "application/octet-stream" : attachment.getContentType());
                stmt.setLong(5, Math.max(attachment.getSizeBytes(), 0L));
                stmt.setString(6, url);
                stmt.setString(7, organizationId == null ? "" : organizationId);
                stmt.setLong(8, now);
                stmt.setLong(9, now);
                stmt.setString(10, userId == null ? "" : userId);
                stmt.setString(11, userId == null ? "" : userId);
                stmt.addBatch();
            }
            stmt.executeBatch();
            }
        } catch (Exception ex) {
            throw new RuntimeException("save CRM attachments failed: " + ex.getMessage(), ex);
        }
    }

    private boolean existsByEventId(Connection connection, String eventId) throws Exception {
        String checkSql = "SELECT 1 FROM email_webhook_attachment WHERE event_id = ? LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
            stmt.setString(1, eventId);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
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

    private String toSafeMessageId(String messageId) {
        if (messageId == null) {
            return "unknown";
        }
        String safe = messageId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }
}
