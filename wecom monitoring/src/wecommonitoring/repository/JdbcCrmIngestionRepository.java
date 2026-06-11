package wecommonitoring.repository;

import wecommonitoring.model.MessageDirection;
import wecommonitoring.model.WeComMediaItem;
import wecommonitoring.model.WeComNormalizedMessage;
import wecommonitoring.util.Ids;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public class JdbcCrmIngestionRepository implements CrmIngestionRepository {
    private static final String SYSTEM_USER = "wecom-monitor";
    private static final String MSG_TYPE_REVOKE = "revoke";
    private static final ZoneId CHAT_DATE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(CHAT_DATE_ZONE);

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public JdbcCrmIngestionRepository(String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception ignored) {
        }
        ensureMediaSdkFileIdCapacity();
    }

    @Override
    public Optional<String> insertPending(String organizationId, String corpId, WeComNormalizedMessage msg) {
        if (msg != null && MSG_TYPE_REVOKE.equalsIgnoreCase(trimToEmpty(msg.getMsgType()))) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        String chatDate = chatDate(msg.getSendTimeMillis());
        String sessionKey = sessionKey(msg);
        int mediaCount = msg.getMediaItems() == null ? 0 : msg.getMediaItems().size();
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            c.setAutoCommit(false);
            try {
                String sessionDayId = ensureSessionDay(c, organizationId, corpId, msg, chatDate, sessionKey, now);
                String messageId = insertMessageIfAbsent(c, sessionDayId, organizationId, corpId, msg, now);
                if (messageId == null) {
                    c.commit();
                    return Optional.of(sessionDayId);
                }
                insertMediaRows(c, messageId, organizationId, msg.getMediaItems(), now);
                updateSessionDayAggregate(c, sessionDayId, msg, mediaCount, now);
                c.commit();
                return Optional.of(sessionDayId);
            } catch (Exception ex) {
                rollbackQuietly(c);
                throw ex;
            }
        } catch (SQLException ex) {
            throw new RuntimeException("insert wecom daily ingestion failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new RuntimeException("insert wecom daily ingestion failed: " + ex.getMessage(), ex);
        }
    }

    private String ensureSessionDay(Connection c, String organizationId, String corpId, WeComNormalizedMessage msg,
                                    String chatDate, String sessionKey, long now) throws SQLException {
        String existing = findSessionDayId(c, organizationId, corpId, chatDate, sessionKey);
        if (existing != null) {
            return existing;
        }
        String id = Ids.newId();
        String sql = "INSERT INTO wecom_ingestion_session_day(id,organization_id,corp_id,chat_date,session_key,chat_type,"
                + "external_userid,specialist_userid,roomid,first_send_time,last_send_time,message_count,media_count,"
                + "merged_content,status,follow_record_id,error_message,create_user,update_user,create_time,update_time) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, organizationId);
            ps.setString(3, corpId);
            ps.setString(4, chatDate);
            ps.setString(5, sessionKey);
            ps.setString(6, msg.getChatType());
            ps.setString(7, customerExternalUserid(msg));
            ps.setString(8, specialistUserid(msg));
            ps.setString(9, msg.getRoomid());
            ps.setLong(10, msg.getSendTimeMillis());
            ps.setLong(11, msg.getSendTimeMillis());
            ps.setInt(12, 0);
            ps.setInt(13, 0);
            ps.setString(14, null);
            ps.setString(15, "PENDING");
            ps.setString(16, null);
            ps.setString(17, null);
            ps.setString(18, SYSTEM_USER);
            ps.setString(19, SYSTEM_USER);
            ps.setLong(20, now);
            ps.setLong(21, now);
            ps.executeUpdate();
            return id;
        } catch (SQLIntegrityConstraintViolationException dup) {
            String found = findSessionDayId(c, organizationId, corpId, chatDate, sessionKey);
            if (found != null) {
                return found;
            }
            throw dup;
        } catch (SQLException ex) {
            if (isDuplicateKey(ex)) {
                String found = findSessionDayId(c, organizationId, corpId, chatDate, sessionKey);
                if (found != null) {
                    return found;
                }
            }
            throw ex;
        }
    }

    private String insertMessageIfAbsent(Connection c, String sessionDayId, String organizationId,
                                         String corpId, WeComNormalizedMessage msg, long now) throws SQLException {
        String id = Ids.newId();
        String sql = "INSERT INTO wecom_ingestion_message(id,session_day_id,organization_id,corp_id,wecom_msg_id,message_direction,"
                + "sender_userid,sender_external_userid,peer_userid,chat_type,external_userid,roomid,matched_external_userid,"
                + "msg_type,content_text,send_time,extra_json,create_time) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, sessionDayId);
            ps.setString(3, organizationId);
            ps.setString(4, corpId);
            ps.setString(5, msg.getWecomMsgId());
            ps.setString(6, msg.getMessageDirection());
            ps.setString(7, msg.getSenderUserid());
            ps.setString(8, msg.getSenderExternalUserid());
            ps.setString(9, msg.getPeerUserid());
            ps.setString(10, msg.getChatType());
            ps.setString(11, msg.getExternalUserid());
            ps.setString(12, msg.getRoomid());
            ps.setString(13, msg.getMatchedExternalUserid());
            ps.setString(14, msg.getMsgType());
            ps.setString(15, msg.getContentText());
            ps.setLong(16, msg.getSendTimeMillis());
            ps.setString(17, msg.getExtraJson());
            ps.setLong(18, now);
            ps.executeUpdate();
            return id;
        } catch (SQLIntegrityConstraintViolationException dup) {
            return null;
        } catch (SQLException ex) {
            if (isDuplicateKey(ex)) {
                return null;
            }
            throw ex;
        }
    }

    private void updateSessionDayAggregate(Connection c, String sessionDayId, WeComNormalizedMessage msg,
                                           int mediaCount, long now) throws SQLException {
        String line = mergedContentLine(msg);
        String sql = "UPDATE wecom_ingestion_session_day SET "
                + "first_send_time=LEAST(COALESCE(first_send_time, ?), ?), "
                + "last_send_time=GREATEST(COALESCE(last_send_time, ?), ?), "
                + "message_count=message_count+1, "
                + "media_count=media_count+?, "
                + "merged_content=CASE WHEN merged_content IS NULL OR merged_content='' THEN ? ELSE CONCAT(merged_content, '\\n', ?) END, "
                + "error_message=CASE WHEN status='FAIL' THEN NULL ELSE error_message END, "
                + "status='PENDING', "
                + "follow_record_id=NULL, "
                + "update_user=?, update_time=? WHERE id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, msg.getSendTimeMillis());
            ps.setLong(2, msg.getSendTimeMillis());
            ps.setLong(3, msg.getSendTimeMillis());
            ps.setLong(4, msg.getSendTimeMillis());
            ps.setInt(5, mediaCount);
            ps.setString(6, line);
            ps.setString(7, line);
            ps.setString(8, SYSTEM_USER);
            ps.setLong(9, now);
            ps.setString(10, sessionDayId);
            ps.executeUpdate();
        }
    }

    private void insertMediaRows(Connection c, String messageId, String organizationId,
                                 List<WeComMediaItem> mediaItems, long now) throws SQLException {
        if (mediaItems == null || mediaItems.isEmpty()) {
            return;
        }
        try {
            insertMediaRowsWithMessageId(c, messageId, organizationId, mediaItems, now);
        } catch (SQLException ex) {
            if (ex.getErrorCode() != 1054) {
                throw ex;
            }
            insertMediaRowsLegacy(c, messageId, organizationId, mediaItems, now);
        }
    }

    private void insertMediaRowsWithMessageId(Connection c, String messageId, String organizationId,
                                              List<WeComMediaItem> mediaItems, long now) throws SQLException {
        String sql = "INSERT INTO wecom_ingestion_media(id,event_id,message_id,organization_id,media_index,msg_media_type,sdk_file_id,file_name,"
                + "mime_type,size_bytes,duration_ms,sha256_hex,fetch_status,crm_asset_ref,extra_json,create_time,update_time) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int index = 0;
            for (WeComMediaItem item : mediaItems) {
                if (item == null) {
                    continue;
                }
                ps.setString(1, Ids.newId());
                ps.setString(2, messageId);
                ps.setString(3, messageId);
                ps.setString(4, organizationId);
                fillMediaParams(ps, item, index, 5, now);
                ps.addBatch();
                index++;
            }
            ps.executeBatch();
        }
    }

    private void insertMediaRowsLegacy(Connection c, String messageId, String organizationId,
                                       List<WeComMediaItem> mediaItems, long now) throws SQLException {
        String sql = "INSERT INTO wecom_ingestion_media(id,event_id,organization_id,media_index,msg_media_type,sdk_file_id,file_name,"
                + "mime_type,size_bytes,duration_ms,sha256_hex,fetch_status,crm_asset_ref,extra_json,create_time,update_time) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int index = 0;
            for (WeComMediaItem item : mediaItems) {
                if (item == null) {
                    continue;
                }
                ps.setString(1, Ids.newId());
                ps.setString(2, messageId);
                ps.setString(3, organizationId);
                fillMediaParams(ps, item, index, 4, now);
                ps.addBatch();
                index++;
            }
            ps.executeBatch();
        }
    }

    private void fillMediaParams(PreparedStatement ps, WeComMediaItem item, int fallbackIndex,
                                 int startIndex, long now) throws SQLException {
        ps.setInt(startIndex, item.getMediaIndex() >= 0 ? item.getMediaIndex() : fallbackIndex);
        ps.setString(startIndex + 1, item.getMediaType());
        ps.setString(startIndex + 2, item.getSdkFileId());
        ps.setString(startIndex + 3, item.getFileName());
        ps.setString(startIndex + 4, item.getMimeType());
        if (item.getSizeBytes() == null) {
            ps.setNull(startIndex + 5, java.sql.Types.BIGINT);
        } else {
            ps.setLong(startIndex + 5, item.getSizeBytes());
        }
        if (item.getDurationMs() == null) {
            ps.setNull(startIndex + 6, java.sql.Types.INTEGER);
        } else {
            ps.setInt(startIndex + 6, item.getDurationMs());
        }
        ps.setString(startIndex + 7, item.getSha256Hex());
        ps.setString(startIndex + 8, "PENDING");
        ps.setString(startIndex + 9, null);
        ps.setString(startIndex + 10, item.getExtraJson());
        ps.setLong(startIndex + 11, now);
        ps.setLong(startIndex + 12, now);
    }

    private String findSessionDayId(Connection c, String organizationId, String corpId,
                                    String chatDate, String sessionKey) throws SQLException {
        String sql = "SELECT id FROM wecom_ingestion_session_day WHERE organization_id=? AND corp_id=? AND chat_date=? AND session_key=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, organizationId);
            ps.setString(2, corpId);
            ps.setString(3, chatDate);
            ps.setString(4, sessionKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    private static boolean isDuplicateKey(SQLException ex) {
        return "23000".equals(ex.getSQLState()) || ex.getErrorCode() == 1062;
    }

    private void ensureMediaSdkFileIdCapacity() {
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            if (!shouldExpandMediaSdkFileId(c)) {
                return;
            }
            expandMediaSdkFileIdColumn(c);
            System.out.println("[BOOT] expanded wecom_ingestion_media.sdk_file_id to MEDIUMTEXT");
        } catch (SQLException ex) {
            System.err.println("[WARN] cannot expand wecom_ingestion_media.sdk_file_id: " + ex.getMessage());
        }
    }

    private boolean shouldExpandMediaSdkFileId(Connection c) throws SQLException {
        String sql = "SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH "
                + "FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='wecom_ingestion_media' AND COLUMN_NAME='sdk_file_id'";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return false;
            }
            String dataType = rs.getString(1);
            long maxLength = rs.getLong(2);
            boolean lengthWasNull = rs.wasNull();
            if (dataType != null && dataType.toLowerCase().contains("text")) {
                return false;
            }
            return lengthWasNull || maxLength < 4096L;
        }
    }

    private void expandMediaSdkFileIdColumn(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE wecom_ingestion_media MODIFY COLUMN sdk_file_id MEDIUMTEXT");
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (Exception ignored) {
        }
    }

    private String chatDate(long sendTimeMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(sendTimeMillis), CHAT_DATE_ZONE).toString();
    }

    private String sessionKey(WeComNormalizedMessage msg) {
        if ("room".equalsIgnoreCase(msg.getChatType()) && notBlank(msg.getRoomid())) {
            return "room:" + msg.getRoomid();
        }
        return "single:" + defaultPart(customerExternalUserid(msg)) + ":" + defaultPart(specialistUserid(msg));
    }

    private String customerExternalUserid(WeComNormalizedMessage msg) {
        if (MessageDirection.isInbound(msg.getMessageDirection())) {
            return firstNonBlank(msg.getSenderExternalUserid(), msg.getMatchedExternalUserid(), msg.getExternalUserid());
        }
        return firstNonBlank(msg.getMatchedExternalUserid(), msg.getExternalUserid(), msg.getSenderExternalUserid());
    }

    private String specialistUserid(WeComNormalizedMessage msg) {
        if (MessageDirection.isInbound(msg.getMessageDirection())) {
            return firstNonBlank(msg.getPeerUserid(), msg.getSenderUserid());
        }
        return firstNonBlank(msg.getSenderUserid(), msg.getPeerUserid());
    }

    private String mergedContentLine(WeComNormalizedMessage msg) {
        String time = TIME_FORMATTER.format(Instant.ofEpochMilli(msg.getSendTimeMillis()));
        String content = msg.getContentText() == null ? "" : msg.getContentText();
        if (content.isBlank() && msg.getMsgType() != null) {
            content = "（非文本消息，类型=" + msg.getMsgType() + "）";
        }
        return "[" + time + "] " + msg.getMessageDirection() + " " + msg.getMsgType() + "\n" + content;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String defaultPart(String value) {
        return notBlank(value) ? value.trim() : "_";
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
