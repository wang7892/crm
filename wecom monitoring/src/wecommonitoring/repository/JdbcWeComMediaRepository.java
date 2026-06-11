package wecommonitoring.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class JdbcWeComMediaRepository {
    private static final String SYSTEM_USER = "wecom-monitor";

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public JdbcWeComMediaRepository(String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception ignored) {
        }
    }

    public List<PendingMedia> listPendingImageVideo(String organizationId, int limit) {
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            try {
                return listPendingImageVideo(c, organizationId, Math.max(1, limit), true);
            } catch (SQLException ex) {
                if (ex.getErrorCode() == 1054) {
                    return listPendingImageVideo(c, organizationId, Math.max(1, limit), false);
                }
                throw ex;
            }
        } catch (SQLException ex) {
            throw new RuntimeException("list pending wecom media failed: " + ex.getMessage(), ex);
        }
    }

    public void markSuccess(PendingMedia media, String attachmentId, String fileName,
                            String mimeType, long sizeBytes, String sha256Hex) {
        long now = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            c.setAutoCommit(false);
            try {
                insertAttachment(c, attachmentId, fileName, extensionOf(fileName), sizeBytes,
                        media.getFollowRecordId(), media.getOrganizationId(), now);
                updateMediaSuccess(c, media.getId(), attachmentId, fileName, mimeType, sizeBytes, sha256Hex, now);
                c.commit();
            } catch (Exception ex) {
                rollbackQuietly(c);
                throw ex;
            }
        } catch (SQLException ex) {
            throw new RuntimeException("mark wecom media success failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new RuntimeException("mark wecom media success failed: " + ex.getMessage(), ex);
        }
    }

    public void markFail(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            return;
        }
        String sql = "UPDATE wecom_ingestion_media SET fetch_status='FAIL', update_time=? WHERE id=?";
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, mediaId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            System.err.println("[WECOM_MEDIA] mark fail failed, mediaId=" + mediaId + ", err=" + ex.getMessage());
        }
    }

    private List<PendingMedia> listPendingImageVideo(Connection c, String organizationId,
                                                     int limit, boolean useMessageIdColumn) throws SQLException {
        String joinCondition = useMessageIdColumn
                ? "(media.message_id = msg.id OR media.event_id = msg.id)"
                : "media.event_id = msg.id";
        String sql = "SELECT media.id, media.organization_id, media.msg_media_type, media.sdk_file_id, "
                + "media.file_name, media.mime_type, media.size_bytes, media.duration_ms, media.extra_json, "
                + "msg.follow_record_id "
                + "FROM wecom_ingestion_media media "
                + "JOIN wecom_ingestion_message msg ON " + joinCondition + " "
                + "WHERE media.organization_id=? "
                + "AND media.fetch_status='PENDING' "
                + "AND (media.crm_asset_ref IS NULL OR TRIM(media.crm_asset_ref)='') "
                + "AND LOWER(media.msg_media_type) IN ('image','video','voice','file','emotion','1','2') "
                + "AND media.sdk_file_id IS NOT NULL AND TRIM(media.sdk_file_id)<>'' "
                + "AND msg.follow_record_id IS NOT NULL AND TRIM(msg.follow_record_id)<>'' "
                + "ORDER BY media.create_time ASC LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, organizationId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<PendingMedia> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new PendingMedia(
                            rs.getString("id"),
                            rs.getString("organization_id"),
                            normalizeMediaType(rs.getString("msg_media_type")),
                            rs.getString("sdk_file_id"),
                            rs.getString("file_name"),
                            rs.getString("mime_type"),
                            readNullableLong(rs, "size_bytes"),
                            readNullableInteger(rs, "duration_ms"),
                            rs.getString("extra_json"),
                            rs.getString("follow_record_id")
                    ));
                }
                return rows;
            }
        }
    }

    private void insertAttachment(Connection c, String attachmentId, String fileName, String type,
                                  long sizeBytes, String resourceId, String organizationId, long now) throws SQLException {
        String sql = "INSERT INTO sys_attachment(id,name,type,size,storage,resource_id,organization_id,"
                + "create_time,update_time,create_user,update_user) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, attachmentId);
            ps.setString(2, fileName);
            ps.setString(3, type);
            ps.setLong(4, sizeBytes);
            ps.setString(5, "LOCAL");
            ps.setString(6, resourceId);
            ps.setString(7, organizationId);
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.setString(10, SYSTEM_USER);
            ps.setString(11, SYSTEM_USER);
            ps.executeUpdate();
        }
    }

    private void updateMediaSuccess(Connection c, String mediaId, String attachmentId, String fileName,
                                    String mimeType, long sizeBytes, String sha256Hex, long now) throws SQLException {
        String sql = "UPDATE wecom_ingestion_media SET fetch_status='SUCCESS', crm_asset_ref=?, "
                + "file_name=?, mime_type=?, size_bytes=?, sha256_hex=?, "
                + "msg_media_type=CASE WHEN msg_media_type IN ('1','2') THEN 'emotion' ELSE msg_media_type END, "
                + "update_time=? WHERE id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, attachmentId);
            ps.setString(2, fileName);
            ps.setString(3, mimeType);
            ps.setLong(4, sizeBytes);
            ps.setString(5, sha256Hex);
            ps.setLong(6, now);
            ps.setString(7, mediaId);
            ps.executeUpdate();
        }
    }

    private static Long readNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer readNullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String normalizeMediaType(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        String value = mediaType.trim();
        if ("1".equals(value) || "2".equals(value)) {
            return "emotion";
        }
        return value;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx >= fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1);
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (Exception ignored) {
        }
    }

    public static final class PendingMedia {
        private final String id;
        private final String organizationId;
        private final String mediaType;
        private final String sdkFileId;
        private final String fileName;
        private final String mimeType;
        private final Long sizeBytes;
        private final Integer durationMs;
        private final String extraJson;
        private final String followRecordId;

        public PendingMedia(String id, String organizationId, String mediaType, String sdkFileId,
                            String fileName, String mimeType, Long sizeBytes, Integer durationMs,
                            String extraJson, String followRecordId) {
            this.id = id;
            this.organizationId = organizationId;
            this.mediaType = mediaType;
            this.sdkFileId = sdkFileId;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
            this.durationMs = durationMs;
            this.extraJson = extraJson;
            this.followRecordId = followRecordId;
        }

        public String getId() {
            return id;
        }

        public String getOrganizationId() {
            return organizationId;
        }

        public String getMediaType() {
            return mediaType;
        }

        public String getSdkFileId() {
            return sdkFileId;
        }

        public String getFileName() {
            return fileName;
        }

        public String getMimeType() {
            return mimeType;
        }

        public Long getSizeBytes() {
            return sizeBytes;
        }

        public Integer getDurationMs() {
            return durationMs;
        }

        public String getExtraJson() {
            return extraJson;
        }

        public String getFollowRecordId() {
            return followRecordId;
        }
    }
}
