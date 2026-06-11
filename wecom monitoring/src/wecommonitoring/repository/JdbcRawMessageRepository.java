package wecommonitoring.repository;

import wecommonitoring.util.Ids;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

public class JdbcRawMessageRepository implements RawMessageRepository {
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public JdbcRawMessageRepository(String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception ignored) {
        }
    }

    @Override
    public void insertRaw(String corpId, String wecomMsgId, long seq, String payload) {
        String sql = "INSERT INTO wecom_raw_message(id,corp_id,wecom_msg_id,seq,decrypted_payload,create_time) VALUES(?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            if (rawExists(c, corpId, wecomMsgId, seq)) {
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Ids.newId());
            ps.setString(2, corpId);
            ps.setString(3, wecomMsgId);
            ps.setLong(4, seq);
            ps.setString(5, payload);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            }
        } catch (SQLIntegrityConstraintViolationException dup) {
            // Another overlapping poll may have inserted the same raw seq first.
        } catch (SQLException ex) {
            if (!isDuplicateKey(ex)) {
                throw new RuntimeException("insertRaw failed: " + ex.getMessage(), ex);
            }
        } catch (Exception ex) {
            throw new RuntimeException("insertRaw failed: " + ex.getMessage(), ex);
        }
    }

    private boolean rawExists(Connection c, String corpId, String wecomMsgId, long seq) throws SQLException {
        if (seq >= 0) {
            String bySeq = "SELECT id FROM wecom_raw_message WHERE corp_id=? AND seq=? LIMIT 1";
            try (PreparedStatement ps = c.prepareStatement(bySeq)) {
                ps.setString(1, corpId);
                ps.setLong(2, seq);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
        }
        if (wecomMsgId == null || wecomMsgId.isBlank()) {
            return false;
        }
        String byMsg = "SELECT id FROM wecom_raw_message WHERE corp_id=? AND wecom_msg_id=? LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(byMsg)) {
            ps.setString(1, corpId);
            ps.setString(2, wecomMsgId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean isDuplicateKey(SQLException ex) {
        return "23000".equals(ex.getSQLState()) || ex.getErrorCode() == 1062;
    }
}
