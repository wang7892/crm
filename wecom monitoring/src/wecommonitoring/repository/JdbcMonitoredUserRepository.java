package wecommonitoring.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class JdbcMonitoredUserRepository {
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public JdbcMonitoredUserRepository(String jdbcUrl, String dbUser, String dbPassword) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception ignored) {
        }
    }

    public List<String> listEnabledUserids() {
        String sql = "SELECT DISTINCT wecom_id FROM sys_user WHERE wecom_id IS NOT NULL AND TRIM(wecom_id)<>'' ORDER BY wecom_id";
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("listEnabledUserids failed: " + ex.getMessage(), ex);
        }
        return out;
    }
}
