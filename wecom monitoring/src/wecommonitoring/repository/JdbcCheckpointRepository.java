package wecommonitoring.repository;

import wecommonitoring.util.Ids;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class JdbcCheckpointRepository implements CheckpointRepository {
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public JdbcCheckpointRepository(String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception ex) {
            System.err.println("[BOOT] MySQL driver not found for checkpoint repository.");
        }
    }

    @Override
    public long getLastSeq(String corpId, String checkpointType) {
        String sql = "SELECT last_seq FROM wecom_sync_checkpoint WHERE corp_id=? AND checkpoint_type=?";
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, corpId);
            ps.setString(2, checkpointType);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                long v = rs.getLong(1);
                return rs.wasNull() ? 0L : v;
            }
        } catch (Exception ex) {
            throw new RuntimeException("getLastSeq failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void saveLastSeq(String corpId, String checkpointType, long lastSeq) {
        long now = System.currentTimeMillis();
        String selectId = "SELECT id FROM wecom_sync_checkpoint WHERE corp_id=? AND checkpoint_type=?";
        String insert = "INSERT INTO wecom_sync_checkpoint(id,corp_id,checkpoint_type,last_seq,create_time,update_time) VALUES(?,?,?,?,?,?)";
        String update = "UPDATE wecom_sync_checkpoint SET last_seq=?, update_time=? WHERE corp_id=? AND checkpoint_type=?";
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            String existingId = null;
            try (PreparedStatement ps = c.prepareStatement(selectId)) {
                ps.setString(1, corpId);
                ps.setString(2, checkpointType);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existingId = rs.getString(1);
                    }
                }
            }
            if (existingId == null) {
                try (PreparedStatement ps = c.prepareStatement(insert)) {
                    ps.setString(1, Ids.newId());
                    ps.setString(2, corpId);
                    ps.setString(3, checkpointType);
                    ps.setLong(4, lastSeq);
                    ps.setLong(5, now);
                    ps.setLong(6, now);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(update)) {
                    ps.setLong(1, lastSeq);
                    ps.setLong(2, now);
                    ps.setString(3, corpId);
                    ps.setString(4, checkpointType);
                    ps.executeUpdate();
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("saveLastSeq failed: " + ex.getMessage(), ex);
        }
    }
}
