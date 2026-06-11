package mailmonitoring.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class JdbcDynamicMailboxAccountRepository {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public JdbcDynamicMailboxAccountRepository(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public List<DynamicMailboxAccount> loadMonitorableAccounts() {
        Map<String, UserMailboxRow> ownerToUser = loadUsers();
        Map<String, List<String>> ownerToTargets = loadCustomerTargets();

        List<DynamicMailboxAccount> accounts = new ArrayList<>();
        for (Map.Entry<String, UserMailboxRow> entry : ownerToUser.entrySet()) {
            String ownerId = entry.getKey();
            UserMailboxRow row = entry.getValue();
            if (row.sourceMailbox.isBlank()) {
                continue;
            }
            if (row.authCode.isBlank()) {
                System.out.printf("[WARN] skip mailbox due to missing auth code. ownerId=%s, email=%s, reason=missing_auth_code%n",
                        ownerId, row.sourceMailbox);
                continue;
            }
            List<String> targets = ownerToTargets.getOrDefault(ownerId, List.of());
            accounts.add(new DynamicMailboxAccount(ownerId, row.sourceMailbox, row.sourceMailbox, row.authCode, targets));
        }
        return accounts;
    }

    private Map<String, UserMailboxRow> loadUsers() {
        String sql = "SELECT su.id, su.email, su.email_auth_code FROM sys_user su "
                + "WHERE su.email IS NOT NULL AND su.email <> ''";
        Map<String, UserMailboxRow> result = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String ownerId = trim(rs.getString("id"));
                String mailbox = normalizeMailbox(rs.getString("email"));
                String authCode = trim(rs.getString("email_auth_code"));
                if (ownerId.isBlank() || mailbox.isBlank()) {
                    continue;
                }
                result.put(ownerId, new UserMailboxRow(mailbox, authCode));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load monitor users: " + ex.getMessage(), ex);
        }
        return result;
    }

    private Map<String, List<String>> loadCustomerTargets() {
        String sql = "SELECT c.owner, c.email FROM customer c "
                + "WHERE c.owner IS NOT NULL AND c.owner <> '' "
                + "AND c.email IS NOT NULL AND c.email <> ''";
        Map<String, List<String>> result = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String ownerId = trim(rs.getString("owner"));
                String mailbox = normalizeMailbox(rs.getString("email"));
                if (ownerId.isBlank() || mailbox.isBlank()) {
                    continue;
                }
                List<String> list = result.computeIfAbsent(ownerId, key -> new ArrayList<>());
                if (!list.contains(mailbox)) {
                    list.add(mailbox);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load customer target mailboxes: " + ex.getMessage(), ex);
        }
        return result;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeMailbox(String value) {
        String trimmed = trim(value);
        if (trimmed.isBlank()) {
            return "";
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static class UserMailboxRow {
        private final String sourceMailbox;
        private final String authCode;

        private UserMailboxRow(String sourceMailbox, String authCode) {
            this.sourceMailbox = sourceMailbox;
            this.authCode = authCode == null ? "" : authCode.trim();
        }
    }
}
