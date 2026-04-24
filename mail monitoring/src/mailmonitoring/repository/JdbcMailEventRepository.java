package mailmonitoring.repository;

import mailmonitoring.model.MailAttachment;
import mailmonitoring.model.MailEvent;
import mailmonitoring.model.ProcessStatus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JdbcMailEventRepository implements MailEventRepository {
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public JdbcMailEventRepository(String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception ex) {
            System.err.println("[BOOT] MySQL driver not found. If you enabled DB, make sure mysql-connector-j is on the classpath.");
        }
    }

    @Override
    public boolean saveIfAbsent(MailEvent event) {
        String checkSql = "SELECT id FROM mail_event WHERE organization_id=? AND message_id=?";
        String insertSql = "INSERT INTO mail_event(organization_id, message_id, thread_id, from_address, to_addresses, cc_addresses, subject, content_text, send_time, process_status, retry_count) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
            checkStmt.setString(1, event.getOrganizationId());
            checkStmt.setString(2, event.getMessageId());
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next()) {
                    return false;
                }
            }

            connection.setAutoCommit(false);
            long eventId;
            try (PreparedStatement insertStmt = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                insertStmt.setString(1, event.getOrganizationId());
                insertStmt.setString(2, event.getMessageId());
                insertStmt.setString(3, event.getThreadId());
                insertStmt.setString(4, event.getFromAddress());
                insertStmt.setString(5, String.join(",", event.getToAddresses()));
                insertStmt.setString(6, String.join(",", event.getCcAddresses()));
                insertStmt.setString(7, event.getSubject());
                insertStmt.setString(8, event.getContentText());
                insertStmt.setTimestamp(9, Timestamp.from(event.getSendTime()));
                insertStmt.setString(10, event.getProcessStatus().name());
                insertStmt.setInt(11, event.getRetryCount());
                insertStmt.executeUpdate();
                try (ResultSet keys = insertStmt.getGeneratedKeys()) {
                    if (!keys.next()) {
                        connection.rollback();
                        throw new IllegalStateException("insert mail_event failed: no generated key");
                    }
                    eventId = keys.getLong(1);
                }
            }

            insertAttachments(connection, eventId, event.getAttachments());
            connection.commit();
            return true;
        } catch (Exception ex) {
            throw new RuntimeException("save mail event failed: " + ex.getMessage(), ex);
        }
    }

    private void insertAttachments(Connection connection, long eventId, List<MailAttachment> attachments) throws Exception {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO mail_attachment(event_id, file_name, content_type, size_bytes, saved_path) VALUES(?,?,?,?,?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (MailAttachment attachment : attachments) {
                stmt.setLong(1, eventId);
                stmt.setString(2, attachment.getFileName());
                stmt.setString(3, attachment.getContentType());
                stmt.setLong(4, attachment.getSizeBytes());
                stmt.setString(5, attachment.getSavedPath());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    @Override
    public void markProcessed(MailEvent event, String followRecordId) {
        event.setProcessStatus(ProcessStatus.PROCESSED);
        event.setFollowRecordId(followRecordId);
        event.setErrorMessage(null);
        event.setNextRetryAt(null);
        updateStatus(event);
    }

    @Override
    public void markFailed(MailEvent event, String error, Instant nextRetryAt) {
        event.setProcessStatus(ProcessStatus.FAILED);
        event.setErrorMessage(error);
        event.increaseRetryCount();
        event.setNextRetryAt(nextRetryAt);
        updateStatus(event);
    }

    @Override
    public void markDead(MailEvent event, String error) {
        event.setProcessStatus(ProcessStatus.DEAD);
        event.setErrorMessage(error);
        event.setNextRetryAt(null);
        updateStatus(event);
    }

    private void updateStatus(MailEvent event) {
        String sql = "UPDATE mail_event SET process_status=?, follow_record_id=?, error_message=?, retry_count=?, next_retry_at=? WHERE organization_id=? AND message_id=?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, event.getProcessStatus().name());
            stmt.setString(2, event.getFollowRecordId());
            stmt.setString(3, event.getErrorMessage());
            stmt.setInt(4, event.getRetryCount());
            stmt.setTimestamp(5, event.getNextRetryAt() == null ? null : Timestamp.from(event.getNextRetryAt()));
            stmt.setString(6, event.getOrganizationId());
            stmt.setString(7, event.getMessageId());
            stmt.executeUpdate();
        } catch (Exception ex) {
            throw new RuntimeException("update mail event status failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<MailEvent> findRetryable(Instant now) {
        String sql = "SELECT id, organization_id, message_id, thread_id, from_address, to_addresses, cc_addresses, subject, content_text, send_time, process_status, follow_record_id, error_message, retry_count, next_retry_at FROM mail_event WHERE process_status='FAILED' AND next_retry_at IS NOT NULL AND next_retry_at <= ?";
        List<MailEvent> result = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(now));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long eventId = rs.getLong("id");
                    List<String> toAddresses = splitCsv(rs.getString("to_addresses"));
                    String fallbackTarget = toAddresses.isEmpty() ? "" : toAddresses.get(0);
                    MailEvent event = new MailEvent(
                            rs.getString("organization_id"),
                            rs.getString("from_address"),
                            fallbackTarget,
                            rs.getString("message_id"),
                            rs.getString("thread_id"),
                            rs.getString("from_address"),
                            toAddresses,
                            splitCsv(rs.getString("cc_addresses")),
                            rs.getString("subject"),
                            rs.getString("content_text"),
                            rs.getTimestamp("send_time").toInstant(),
                            queryAttachmentsByEventId(connection, eventId)
                    );
                    event.setProcessStatus(ProcessStatus.valueOf(rs.getString("process_status")));
                    event.setFollowRecordId(rs.getString("follow_record_id"));
                    event.setErrorMessage(rs.getString("error_message"));
                    int retryCount = rs.getInt("retry_count");
                    for (int i = 0; i < retryCount; i++) {
                        event.increaseRetryCount();
                    }
                    Timestamp nextRetryAt = rs.getTimestamp("next_retry_at");
                    event.setNextRetryAt(nextRetryAt == null ? null : nextRetryAt.toInstant());
                    result.add(event);
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("query retryable events failed: " + ex.getMessage(), ex);
        }
        return result;
    }

    private List<MailAttachment> queryAttachmentsByEventId(Connection connection, long eventId) throws Exception {
        String sql = "SELECT file_name, content_type, size_bytes, saved_path FROM mail_attachment WHERE event_id = ?";
        List<MailAttachment> attachments = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, eventId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    attachments.add(new MailAttachment(
                            rs.getString("file_name"),
                            rs.getString("content_type"),
                            rs.getLong("size_bytes"),
                            rs.getString("saved_path")
                    ));
                }
            }
        }
        return attachments;
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] raw = value.split(",");
        List<String> result = new ArrayList<>();
        for (String item : raw) {
            if (item != null && !item.isBlank()) {
                result.add(item.trim());
            }
        }
        return result;
    }
}
