package wecommonitoring.repository;

import wecommonitoring.model.WeComNormalizedMessage;
import wecommonitoring.util.Ids;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

public class JdbcWeComMessageEventRepository implements WeComMessageEventRepository {
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public JdbcWeComMessageEventRepository(String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception ignored) {
        }
    }

    @Override
    public String insertIfAbsent(String organizationId, String corpId, WeComNormalizedMessage msg) {
        String id = Ids.newId();
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO wecom_message_event(id,organization_id,corp_id,wecom_msg_id,message_direction,"
                + "sender_userid,sender_external_userid,peer_userid,chat_type,external_userid,roomid,room_external_snapshot,"
                + "msg_type,content_text,send_time,dedup_hash,create_time) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, organizationId);
            ps.setString(3, corpId);
            ps.setString(4, msg.getWecomMsgId());
            ps.setString(5, msg.getMessageDirection());
            ps.setString(6, msg.getSenderUserid());
            ps.setString(7, msg.getSenderExternalUserid());
            ps.setString(8, msg.getPeerUserid());
            ps.setString(9, msg.getChatType());
            ps.setString(10, msg.getExternalUserid());
            ps.setString(11, msg.getRoomid());
            ps.setString(12, msg.getRoomExternalSnapshotJson());
            ps.setString(13, msg.getMsgType());
            ps.setString(14, msg.getContentText());
            ps.setLong(15, msg.getSendTimeMillis());
            ps.setString(16, "");
            ps.setLong(17, now);
            ps.executeUpdate();
            return id;
        } catch (SQLIntegrityConstraintViolationException dup) {
            return null;
        } catch (SQLException ex) {
            if (isDuplicateKey(ex)) {
                return null;
            }
            throw new RuntimeException("insert wecom_message_event failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            if (ex.getCause() instanceof SQLIntegrityConstraintViolationException || isDuplicateKeySql(ex)) {
                return null;
            }
            throw new RuntimeException("insert wecom_message_event failed: " + ex.getMessage(), ex);
        }
    }

    private static boolean isDuplicateKey(SQLException ex) {
        return "23000".equals(ex.getSQLState()) || ex.getErrorCode() == 1062;
    }

    private static boolean isDuplicateKeySql(Throwable ex) {
        if (ex == null) {
            return false;
        }
        if (ex instanceof SQLException se) {
            return isDuplicateKey(se);
        }
        return isDuplicateKeySql(ex.getCause());
    }

    @Override
    public void updateCrmIngestionId(String eventRowId, String crmIngestionId) {
        String sql = "UPDATE wecom_message_event SET crm_ingestion_id=? WHERE id=?";
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, crmIngestionId);
            ps.setString(2, eventRowId);
            ps.executeUpdate();
        } catch (Exception ex) {
            throw new RuntimeException("updateCrmIngestionId failed: " + ex.getMessage(), ex);
        }
    }
}
